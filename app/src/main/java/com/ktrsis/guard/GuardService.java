package com.ktrsis.guard;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.ToneGenerator;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guardián: lee el saldo real de la app del broker en la pantalla cada 2 segundos y
 * avisa en grande (pantalla completa, vibración y sonido) al tocar la meta o el riesgo.
 */
public class GuardService extends Service {
    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    static final int BG = 0xF20E1116, PANEL = 0xFF171B22, LINE = 0xFF262C36, FG = 0xFFE8EBF0,
            MUTED = 0xFF8B93A1, WIN = 0xFF2FC47A, LOSS = 0xFFEF5A5A, GOLD = 0xFFD9B45A;

    // Estado que la pantalla del Guardián lee
    public static volatile boolean running = false;
    public static volatile double balance = Double.NaN, start = Double.NaN;
    public static volatile String lastRead = "", status = "";
    public static volatile long lastReadAt = 0;
    public static volatile int state = 0;  // 1 meta, -1 riesgo, 0 en juego

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout panel;
    private TextView balTxt, plTxt, lvlTxt, stTxt;
    private Bar bar;
    private View alertView;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread bgThread;
    private Handler bg;
    private final Object frameLock = new Object();
    private Image latest;
    private int capW, capH, capDpi;
    private TextRecognizer ocr;
    private volatile boolean ocrBusy = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // filtro de lecturas
    private double pending = Double.NaN;
    private int pendingCount = 0;
    private float lastX = -1, lastY = -1;
    private long riskSince = 0;
    private int alerted = 0;   // último aviso mostrado (para no repetirlo)
    private int goalHits = 0;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("guardian", MODE_PRIVATE);
        bgThread = new HandlerThread("guardian");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (projection == null) {
            if (intent == null || !intent.hasExtra(EXTRA_DATA)) { stopSelf(); return START_NOT_STICKY; }
            int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            try { projection = mpm.getMediaProjection(code, data); } catch (Exception e) { projection = null; }
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { ui.post(GuardService.this::stopSelf); }
            }, ui);
            balance = Double.NaN; state = 0; alerted = 0; riskSince = 0; goalHits = 0;
            start = "manual".equals(prefs.getString("capMode", "auto")) ? prefs.getFloat("capital", 0) : Double.NaN;
            if (!(start > 0)) start = Double.NaN;
            status = "Buscando tu saldo en la pantalla…";
            startCapture();
            showPanel();
            running = true;
            ui.postDelayed(tick, 1200);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) {}
        hideAlert();
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        synchronized (frameLock) { if (latest != null) { latest.close(); latest = null; } }
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { ocr.close(); } catch (Exception ignored) {}
        bgThread.quitSafely();
        super.onDestroy();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("guardian", "Guardián de riesgo y meta", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, GuardActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "guardian")
                .setContentTitle("Guardián activo")
                .setContentText("Vigilando tu riesgo y tu meta")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(3, n);
    }

    // ---------------- niveles ----------------
    double riskPct() { return prefs.getFloat("risk", 6); }
    double goalPct() { return prefs.getFloat("goal", 9); }
    double riskLevel() { return start * (1 - riskPct() / 100.0); }
    double goalLevel() { return start * (1 + goalPct() / 100.0); }

    // ---------------- captura ----------------
    private void readMetrics() {
        WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect r = w.getCurrentWindowMetrics().getBounds();
            capW = r.width(); capH = r.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            //noinspection deprecation
            w.getDefaultDisplay().getRealMetrics(dm);
            capW = dm.widthPixels; capH = dm.heightPixels;
        }
        capDpi = getResources().getDisplayMetrics().densityDpi;
    }

    private void startCapture() {
        readMetrics();
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(rd -> {
            Image img;
            try { img = rd.acquireLatestImage(); } catch (Exception e) { return; }
            if (img == null) return;
            synchronized (frameLock) { if (latest != null) latest.close(); latest = img; }
        }, bg);
        display = projection.createVirtualDisplay("guardian", capW, capH, capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            readOnce();
            ui.postDelayed(this, 2000);
        }
    };

    /** Toma el cuadro actual, recorta la parte de arriba (donde el broker muestra el saldo) y la lee. */
    private void readOnce() {
        if (ocrBusy) return;
        final Rect hide = panelRect();
        bg.post(() -> {
            Bitmap crop;
            synchronized (frameLock) {
                if (latest == null) return;
                crop = topCrop(latest, hide);
            }
            if (crop == null) return;
            ocrBusy = true;
            final int cw = crop.getWidth();
            ocr.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener(t -> { ocrBusy = false; onText(t, cw); })
                    .addOnFailureListener(e -> ocrBusy = false);
        });
    }

    private Bitmap topCrop(Image img, Rect hide) {
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            int padW = p.getRowStride() / p.getPixelStride();
            Bitmap full = Bitmap.createBitmap(padW, h, Bitmap.Config.ARGB_8888);
            buf.rewind();
            full.copyPixelsFromBuffer(buf);
            int ch = (int) (h * (h > w ? 0.30f : 0.38f));   // vertical: 30% de arriba; horizontal: 38%
            Bitmap crop = Bitmap.createBitmap(full, 0, 0, w, ch);
            full.recycle();
            Bitmap m = crop.copy(Bitmap.Config.ARGB_8888, true);
            crop.recycle();
            if (hide != null) {   // tapa la propia ventana del Guardián para no leerse a sí mismo
                Paint pt = new Paint(); pt.setColor(0xFF000000);
                new Canvas(m).drawRect(hide, pt);
            }
            return m;
        } catch (Exception e) { return null; }
    }

    private static final Pattern MONEY = Pattern.compile("\\$\\s?([0-9]{1,3}(?:[,.\\s][0-9]{3})*(?:[.,][0-9]{2})?|[0-9]+(?:[.,][0-9]{2})?)");

    private void onText(Text t, int width) {
        double best = Double.NaN; float bestScore = -1e9f, bx = -1, by = -1;
        String bestTxt = "";
        java.util.List<Text.Line> lines = new java.util.ArrayList<>();
        for (Text.TextBlock b : t.getTextBlocks()) lines.addAll(b.getLines());
        for (Text.Line l : lines) {
            Matcher m = MONEY.matcher(l.getText());
            boolean bare = false;
            if (!m.find()) {
                // sin "$": solo números con 2 decimales y una etiqueta de saldo cerca (Real/Demo/QT)
                m = BARE.matcher(l.getText().trim());
                if (!m.matches()) continue;
                bare = true;
            }
            Rect r = l.getBoundingBox(); if (r == null) continue;
            double v = parse(m.group(1));
            if (!(v > 0)) continue;
            float score = r.height();
            for (Text.Line o : lines) {   // etiquetas típicas del saldo cerca
                Rect q = o.getBoundingBox(); if (q == null || o == l) continue;
                if (Math.abs(q.centerY() - r.centerY()) < r.height() * 2.2f && Math.abs(q.centerX() - r.centerX()) < width * 0.35f
                        && o.getText().matches("(?i).*(real|demo|qt|balance|saldo|cuenta|account).*")) score += 40;
            }
            if (l.getText().matches("(?i).*(real|demo|qt).*")) score += 30;
            if (bare && score < r.height() + 30) continue;   // número suelto sin etiqueta: no es el saldo
            if (lastX >= 0) {             // preferir la misma posición que la última vez
                float d = Math.abs(r.centerX() - lastX) + Math.abs(r.centerY() - lastY);
                if (d < r.height() * 2) score += 60;
            }
            if (score > bestScore) { bestScore = score; best = v; bestTxt = m.group(0); bx = r.centerX(); by = r.centerY(); }
        }
        final double val = best; final String txt = bestTxt; final float fx = bx, fy = by;
        ui.post(() -> accept(val, txt, fx, fy));
    }

    private static final Pattern BARE = Pattern.compile("([0-9]{1,3}(?:[,.\\s][0-9]{3})*[.,][0-9]{2})");

    static double parse(String s) {
        s = s.replaceAll("\\s", "");
        int lastDot = s.lastIndexOf('.'), lastComma = s.lastIndexOf(',');
        int dec = Math.max(lastDot, lastComma);
        if (dec >= 0 && s.length() - dec - 1 == 2) {
            String ip = s.substring(0, dec).replaceAll("[.,]", ""), fp = s.substring(dec + 1);
            try { return Double.parseDouble(ip + "." + fp); } catch (Exception e) { return Double.NaN; }
        }
        try { return Double.parseDouble(s.replaceAll("[.,]", "")); } catch (Exception e) { return Double.NaN; }
    }

    /** Filtra lecturas raras: un valor nuevo cuenta cuando se lee igual dos veces seguidas. */
    private void accept(double v, String txt, float x, float y) {
        lastReadAt = System.currentTimeMillis();
        if (Double.isNaN(v)) { status = "No veo el saldo. Abre la app del broker con el saldo visible arriba."; render(); return; }
        lastRead = txt;
        if (!Double.isNaN(balance) && Math.abs(v - balance) < 0.005) { pendingCount = 0; status = ""; evaluate(); render(); return; }
        if (!Double.isNaN(pending) && Math.abs(v - pending) < 0.005) pendingCount++;
        else { pending = v; pendingCount = 1; }
        boolean plausible = Double.isNaN(balance) || (v > balance * 0.4 && v < balance * 2.5);
        if (pendingCount >= 2 || (plausible && !Double.isNaN(balance) && pendingCount >= 1 && Math.abs(v - balance) < balance * 0.15)) {
            balance = v; lastX = x; lastY = y; pendingCount = 0; status = "";
            if (Double.isNaN(start)) start = v;     // capital inicial = primer saldo leído
            evaluate();
        }
        render();
    }

    private void evaluate() {
        if (Double.isNaN(start) || Double.isNaN(balance)) return;
        long now = System.currentTimeMillis();
        int st = 0;
        if (goalPct() > 0 && balance >= goalLevel() - 0.005) { goalHits++; if (goalHits >= 2) st = 1; } else goalHits = 0;
        if (riskPct() > 0 && balance <= riskLevel() + 0.005) {
            if (riskSince == 0) riskSince = now;
            // la apuesta abierta se descuenta del saldo al instante: esperar unos segundos
            if (now - riskSince >= prefs.getInt("delay", 6) * 1000L) st = -1;
        } else riskSince = 0;
        state = st;
        if (st != 0 && st != alerted) { alerted = st; showAlert(st); }
        if (st == 0 && alerted != 0 && balance > riskLevel() && balance < goalLevel()) alerted = 0;
    }

    // ---------------- ventana flotante ----------------
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private GradientDrawable rounded(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke); return g;
    }
    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); return t;
    }

    private Rect panelRect() {
        if (panel == null || lp == null) return null;
        return new Rect(lp.x - dp(4), lp.y - dp(4), lp.x + panel.getWidth() + dp(4), lp.y + panel.getHeight() + dp(4));
    }

    private void showPanel() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", dp(8));
        lp.y = prefs.getInt("y", (int) (getResources().getDisplayMetrics().heightPixels * 0.42f));
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(BG, 12, 0x66D9B45A));
        panel.setPadding(dp(8), dp(4), dp(8), dp(8));
        int w = dp(150);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("⠿ GUARDIÁN", 10, GOLD, true);
        TextView reset = text("⟳", 16, MUTED, true);
        TextView close = text("×", 18, MUTED, true);
        reset.setPadding(dp(6), 0, dp(6), 0); close.setPadding(dp(6), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(reset); top.addView(close);
        panel.addView(top, new LinearLayout.LayoutParams(w, dp(24)));

        balTxt = text("—", 22, FG, true);
        balTxt.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        plTxt = text("", 12, MUTED, true);
        bar = new Bar(this);
        lvlTxt = text("", 10, MUTED, false);
        lvlTxt.setTypeface(Typeface.MONOSPACE);
        stTxt = text("", 11, FG, true);
        // mismo orden que el recuadro del Rotador: −riesgo · +meta, barra, estado; abajo el saldo real
        lvlTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lvlTxt.setTextColor(MUTED);
        stTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        balTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        balTxt.setTypeface(Typeface.MONOSPACE);
        balTxt.setTextColor(MUTED);
        plTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        panel.addView(lvlTxt, new LinearLayout.LayoutParams(w, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(w, dp(10)); bl.topMargin = dp(6); bl.bottomMargin = dp(6);
        panel.addView(bar, bl);
        panel.addView(stTxt, new LinearLayout.LayoutParams(w, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(w, -2); sl.topMargin = dp(4);
        panel.addView(balTxt, sl);
        panel.addView(plTxt);

        close.setOnClickListener(v -> stopSelf());
        // ⟳: el saldo actual pasa a ser el capital de inicio (empezar de nuevo)
        reset.setOnClickListener(v -> {
            if (!Double.isNaN(balance)) { start = balance; alerted = 0; state = 0; riskSince = 0; goalHits = 0; render(); }
        });
        View.OnTouchListener drag = new Drag();
        title.setOnTouchListener(drag); balTxt.setOnTouchListener(drag); plTxt.setOnTouchListener(drag);
        panel.setOnTouchListener(drag);
        wm.addView(panel, lp);
        render();
    }

    private static String fmt0(double v) { return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.US, "%.1f", v); }
    private static String short$(double v) { return Math.abs(v - Math.rint(v)) < 0.005 ? "$" + String.format(Locale.US, "%,d", (long) Math.rint(v)) : money(v); }

    private static String money(double v) {
        return (v < 0 ? "−$" : "$") + String.format(Locale.US, "%,.2f", Math.abs(v));
    }

    private void render() {
        if (panel == null) return;
        if (Double.isNaN(balance)) {
            balTxt.setText("Saldo —");
            plTxt.setText(status.isEmpty() ? "Leyendo tu saldo…" : "No veo el saldo"); plTxt.setTextColor(MUTED);
            stTxt.setText(status.isEmpty() ? "…" : "Abre el broker"); stTxt.setTextColor(GOLD);
            lvlTxt.setText(Double.isNaN(start) ? "−" + fmt0(riskPct()) + "% · +" + fmt0(goalPct()) + "%"
                    : "−" + short$(start - riskLevel()) + "  ·  +" + short$(goalLevel() - start));
            bar.set(0, 1, 1.5);
            return;
        }
        double pl = balance - start;
        balTxt.setText("Saldo " + money(balance));
        plTxt.setText((pl >= 0 ? "+" : "") + money(pl) + "  (" + String.format(Locale.US, "%+.1f%%", pl / start * 100) + ")");
        plTxt.setTextColor(pl > 0.004 ? WIN : pl < -0.004 ? LOSS : MUTED);
        double riskAmt = start - riskLevel(), goalAmt = goalLevel() - start;
        bar.set(pl, riskAmt, goalAmt);
        lvlTxt.setText("−" + short$(riskAmt) + "  ·  +" + short$(goalAmt));
        if (state == 1) { stTxt.setText("✓ META · CIERRA"); stTxt.setTextColor(WIN); }
        else if (state == -1) { stTxt.setText("✕ RIESGO · PARA"); stTxt.setTextColor(LOSS); }
        else if (riskSince > 0) { stTxt.setText("⚠ En el límite…"); stTxt.setTextColor(GOLD); }
        else if (pl >= 0) { stTxt.setText("Faltan " + short$(goalAmt - pl)); stTxt.setTextColor(FG); }
        else { stTxt.setText("Margen " + short$(riskAmt + pl)); stTxt.setTextColor(GOLD); }
        panel.setBackground(rounded(state == 1 ? 0xF2123A26 : state == -1 ? 0xF23A1414 : BG, 12,
                state == 1 ? WIN : state == -1 ? LOSS : 0x66D9B45A));
    }

    // ---------------- aviso grande ----------------
    private ToneGenerator tone;
    private void showAlert(int st) {
        hideAlert();
        boolean win = st == 1;
        int color = win ? 0xF0157A45 : 0xF0A8202A;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(color);
        v.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView big = text(win ? "✓ META" : "✕ RIESGO", 54, 0xFFFFFFFF, true);
        big.setGravity(Gravity.CENTER);
        double pl = balance - start;
        TextView amt = text((pl >= 0 ? "+" : "") + money(pl), 34, 0xFFFFFFFF, true);
        amt.setGravity(Gravity.CENTER);
        TextView msg = text(win ? "Llegaste a tu meta.\nCIERRA Y RETIRA LA GANANCIA." : "Tocaste tu límite de riesgo.\nPARA POR HOY.", 20, 0xFFFFFFFF, true);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(16), 0, dp(24));
        TextView ok = text("Toca para cerrar", 14, 0xCCFFFFFF, false);
        ok.setGravity(Gravity.CENTER);
        v.addView(big); v.addView(amt); v.addView(msg); v.addView(ok);
        WindowManager.LayoutParams alp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        v.setOnClickListener(x -> hideAlert());
        try { wm.addView(v, alp); alertView = v; } catch (Exception ignored) {}
        // parpadeo
        final int[] k = {0};
        Runnable blink = new Runnable() { @Override public void run() {
            if (alertView != v) return;
            v.setBackgroundColor(k[0]++ % 2 == 0 ? color : (win ? 0xF0209E5A : 0xF0D02A36));
            if (k[0] < 16) ui.postDelayed(this, 350);
        }};
        ui.post(blink);
        try {
            Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib != null) vib.vibrate(VibrationEffect.createWaveform(win ? new long[]{0, 300, 150, 300, 150, 700} : new long[]{0, 700, 200, 700, 200, 1200}, -1));
        } catch (Exception ignored) {}
        if (prefs.getBoolean("sound", true)) {
            try {
                tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                tone.startTone(win ? ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE : ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 2500);
                ui.postDelayed(() -> { try { tone.release(); } catch (Exception ignored) {} }, 2800);
            } catch (Exception ignored) {}
        }
        render();
    }

    private void hideAlert() {
        if (alertView != null) { try { wm.removeView(alertView); } catch (Exception ignored) {} alertView = null; }
    }

    /** Barra de dos lados: rojo a la izquierda (riesgo), verde a la derecha (meta). */
    private class Bar extends View {
        private double pl, risk, goal;
        private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bar(android.content.Context c) { super(c); }
        void set(double p, double r, double g) { pl = p; risk = r; goal = g; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), rad = H / 2f;
            double tot = Math.max(0.01, risk + goal);
            float zero = (float) (W * risk / tot);
            pt.setColor(LINE); c.drawRoundRect(0, 0, W, H, rad, rad, pt);
            if (pl < 0 && risk > 0) { float x = (float) Math.max(0, zero - zero * Math.min(1, -pl / risk)); pt.setColor(LOSS); c.drawRoundRect(x, 0, zero, H, rad, rad, pt); }
            else if (pl > 0 && goal > 0) { float x = (float) Math.min(W, zero + (W - zero) * Math.min(1, pl / goal)); pt.setColor(WIN); c.drawRoundRect(zero, 0, x, H, rad, rad, pt); }
            pt.setColor(FG); c.drawRect(zero - dp(1), -1, zero + dp(1), H + 1, pt);
        }
    }

    private class Drag implements View.OnTouchListener {
        int sx, sy; float dx0, dy0; boolean moved;
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: sx = lp.x; sy = lp.y; dx0 = e.getRawX(); dy0 = e.getRawY(); moved = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - dx0, dy = e.getRawY() - dy0;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved) { lp.x = sx + (int) dx; lp.y = sy + (int) dy; wm.updateViewLayout(panel, lp); }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                    return true;
            }
            return false;
        }
    }
}
