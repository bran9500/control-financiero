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
    // Rotador + Diario dentro de la misma ventanita
    private com.ktrsis.rotador.Engine engine;
    private AutoTrades auto;
    private TextView bubbleTxt, whoTxt, stakeTxt, escTxt, autoTxt, autoBtn, resetBtn, undoTxt, sizeBtn;
    private View compactBox, expandBox, headRow;
    private TextView qZona, qTrazar, qBorrar, trazMsg;
    private final Runnable hideTrazMsg = () -> { if (trazMsg != null) trazMsg.setVisibility(View.GONE); };
    private int size = 1;              // 0 bolita · 1 compacta · 2 abierta
    private String lastAuto = "";
    private final Runnable engineChanged = () -> GuardService.this.ui.post(GuardService.this::render);
    private final Runnable fade = () -> { if (panel != null && state == 0) panel.setAlpha(size == 0 ? 0.55f : 0.62f); };
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

    // Trazador dentro de la misma ventanita (usa esta misma grabación de pantalla)
    private com.ktrsis.trazador.TrazadorCore traz;
    private View trazBox;
    private TextView trazToggle;

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
        com.ktrsis.rotador.Engine.get(this); // carga la moneda elegida en el Rotador
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
            if (intent.getBooleanExtra("traz", false)) prefs.edit().putBoolean("traz", true).apply();
            balance = Double.NaN; state = 0; alerted = 0; riskSince = 0; goalHits = 0;
            engine = com.ktrsis.rotador.Engine.get(this);
            engine.addListener(engineChanged);
            auto = new AutoTrades(new AutoTrades.Book() {
                @Override public int turn() { return engine.turn; }
                @Override public int lots() { return engine.nLots; }
                @Override public boolean alive(int lot) { return engine.alive(lot); }
                @Override public double stakeOf(int lot) { return engine.stakeOf(lot); }
                @Override public void apply(int lot, boolean win) { engine.resultFor(lot, win, true); }
                @Override public void extra(double stake, boolean win, double value) { engine.extra(stake, win, value, true); }
                @Override public void setTurn(int lot) { engine.setTurn(lot); }
            });
            auto.expiryMs = Math.max(1, prefs.getInt("expiry", 3)) * 1000L;
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
        if (engine != null) engine.removeListener(engineChanged);
        try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) {}
        if (traz != null) traz.release();
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
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, com.ktrsis.rotador.MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "guardian")
                .setContentTitle("Panel de trading activo")
                .setContentText("Rotador, Diario, Guardián y Trazador en una ventanita")
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
            if (autoOn() && auto != null) {
                auto.tick(System.currentTimeMillis());
                if (!auto.last.equals(lastAuto)) render();
            }
            ui.postDelayed(this, autoOn() ? 700 : 2000);
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

    /** Cuadro completo de la pantalla para el Trazador (se llama en el hilo de fondo). */
    private Bitmap fullFrame() {
        synchronized (frameLock) {
            if (latest == null) return null;
            try {
                Image.Plane p = latest.getPlanes()[0];
                ByteBuffer buf = p.getBuffer();
                int w = latest.getWidth(), h = latest.getHeight();
                int padW = p.getRowStride() / p.getPixelStride();
                Bitmap full = Bitmap.createBitmap(padW, h, Bitmap.Config.ARGB_8888);
                buf.rewind();
                full.copyPixelsFromBuffer(buf);
                if (padW == w) return full;
                Bitmap crop = Bitmap.createBitmap(full, 0, 0, w, h);
                full.recycle();
                return crop;
            } catch (Exception e) { return null; }
        }
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
        // Con la detección automática, un saldo nuevo cuenta solo si se lee igual dos veces (evita errores de lectura).
        if (pendingCount >= 2 || (!autoOn() && plausible && !Double.isNaN(balance) && pendingCount >= 1 && Math.abs(v - balance) < balance * 0.15)) {
            double prev = balance;
            balance = v; lastX = x; lastY = y; pendingCount = 0; status = "";
            if (autoOn() && auto != null && !Double.isNaN(prev) && plausible) auto.onBalance(prev, v, System.currentTimeMillis());
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

    boolean autoOn() { return prefs.getBoolean("auto", true); }

    private TextView chip(String s, int color, int fill) {
        TextView t = text(s, 12, color, true);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(6), dp(6), dp(6), dp(6));
        t.setBackground(rounded(fill, 7, 0)); return t;
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
        panel.setPadding(dp(7), dp(3), dp(7), dp(6));

        // ---- bolita: solo el monto de la siguiente operación ----
        bubbleTxt = text("$1", 15, FG, true);
        bubbleTxt.setGravity(Gravity.CENTER);
        bubbleTxt.setPadding(dp(4), dp(4), dp(4), dp(4));
        panel.addView(bubbleTxt, new LinearLayout.LayoutParams(-2, -2));

        // ---- compacta ----
        LinearLayout compact = new LinearLayout(this);
        compact.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        whoTxt = text("⠿ L1 · P1", 10, MUTED, true);
        sizeBtn = text("▾", 15, GOLD, true); sizeBtn.setPadding(dp(8), 0, dp(6), 0);
        TextView mini = text("–", 17, MUTED, true); mini.setPadding(dp(6), 0, dp(2), 0);
        head.addView(whoTxt, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(sizeBtn); head.addView(mini);
        headRow = head;
        compact.addView(head, new LinearLayout.LayoutParams(-1, dp(22)));
        stakeTxt = text("$1", 26, FG, true);
        stakeTxt.setIncludeFontPadding(false);
        // Al lado del monto: los botones del Trazador, para usarlos sin abrir la ventanita
        LinearLayout stakeRow = new LinearLayout(this);
        stakeRow.setGravity(Gravity.CENTER_VERTICAL);
        stakeRow.addView(stakeTxt, new LinearLayout.LayoutParams(0, -2, 1));
        qZona = chip("Zona", 0xFFFF5AF0, 0xFF232A35);
        qTrazar = chip("Trazar", 0xFFFFE14D, 0xFF232A35);
        qBorrar = chip("Borrar", FG, 0xFF232A35);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-2, -2); qp.leftMargin = dp(5);
        stakeRow.addView(qZona, qp); stakeRow.addView(qTrazar, qp); stakeRow.addView(qBorrar, qp);
        compact.addView(stakeRow, new LinearLayout.LayoutParams(-1, -2));
        escTxt = text("", 10, GOLD, true);
        escTxt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        compact.addView(escTxt);
        bar = new Bar(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(5)); bl.topMargin = dp(4); bl.bottomMargin = dp(2);
        compact.addView(bar, bl);
        stTxt = text("", 11, FG, true);
        compact.addView(stTxt);
        trazMsg = text("", 10, MUTED, false);
        trazMsg.setVisibility(View.GONE);
        trazMsg.setMaxLines(3);
        compact.addView(trazMsg, new LinearLayout.LayoutParams(-1, -2));
        compactBox = compact;
        compact.setMinimumWidth(dp(118));
        panel.addView(compact, new LinearLayout.LayoutParams(-1, -2));

        // ---- abierta ----
        LinearLayout ex = new LinearLayout(this);
        ex.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rowRes = new LinearLayout(this);
        TextView win = chip("Ganó", 0xFF0E1116, WIN), loss = chip("Perdió", 0xFFFFFFFF, LOSS), und = chip("↶", FG, 0xFF232A35);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.rightMargin = dp(4);
        rowRes.addView(win, rp); rowRes.addView(loss, rp);
        rowRes.addView(und, new LinearLayout.LayoutParams(dp(36), -2));
        LinearLayout.LayoutParams rrl = new LinearLayout.LayoutParams(-1, -2); rrl.topMargin = dp(6);
        ex.addView(rowRes, rrl);
        autoTxt = text("", 10, MUTED, false);
        autoTxt.setMaxWidth(dp(186));
        autoTxt.setPadding(0, dp(4), 0, 0);
        ex.addView(autoTxt);
        undoTxt = text("Se reinició el capital · toca aquí para DESHACER", 11, GOLD, true);
        undoTxt.setVisibility(View.GONE);
        undoTxt.setPadding(0, dp(4), 0, 0);
        ex.addView(undoTxt);

        // Trazador: Zona / Trazar / Borrar
        traz = new com.ktrsis.trazador.TrazadorCore(this, wm, bg, new com.ktrsis.trazador.TrazadorCore.FrameSource() {
            @Override public Bitmap frame() { return fullFrame(); }
            @Override public Rect hide() { return panelRect(); }
        });
        traz.setStatusListener(msg -> ui.post(() -> {
            if (size != 1 || trazMsg == null) return;   // en la abierta ya se ve abajo de sus botones
            trazMsg.setText(msg); trazMsg.setVisibility(View.VISIBLE);
            ui.removeCallbacks(hideTrazMsg); ui.postDelayed(hideTrazMsg, 9000);
        }));
        qZona.setOnClickListener(v -> { touched(); traz.zone(); });
        qTrazar.setOnClickListener(v -> { touched(); traz.trace(); });
        qBorrar.setOnClickListener(v -> { touched(); traz.clearLines(); });
        View sep = new View(this); sep.setBackgroundColor(LINE);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(-1, dp(1)); sepLp.topMargin = dp(6);
        ex.addView(sep, sepLp);
        trazBox = traz.controls();
        ex.addView(trazBox, new LinearLayout.LayoutParams(dp(186), -2));

        balTxt = text("", 9.5f, MUTED, false); balTxt.setTypeface(Typeface.MONOSPACE);
        plTxt = text("", 9, MUTED, true);
        lvlTxt = text("", 10, MUTED, false); lvlTxt.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams bt = new LinearLayout.LayoutParams(-1, -2); bt.topMargin = dp(5);
        ex.addView(balTxt, bt); ex.addView(plTxt); ex.addView(lvlTxt);

        LinearLayout foot = new LinearLayout(this);
        autoBtn = chip("", FG, 0xFF232A35);
        resetBtn = chip("⟳ mantén", MUTED, 0xFF232A35);
        TextView close = chip("× Cerrar", MUTED, 0xFF232A35);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1); fp.rightMargin = dp(4);
        foot.addView(autoBtn, fp); foot.addView(resetBtn, fp);
        foot.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2); fl.topMargin = dp(6);
        ex.addView(foot, fl);
        expandBox = ex;
        panel.addView(ex, new LinearLayout.LayoutParams(-2, -2));

        // ---- acciones ----
        win.setOnClickListener(v -> { touched(); engine.result(true); });
        loss.setOnClickListener(v -> { touched(); engine.result(false); });
        und.setOnClickListener(v -> { touched(); engine.undo(); });
        sizeBtn.setOnClickListener(v -> setSize(size == 2 ? 1 : 2));
        mini.setOnClickListener(v -> setSize(0));
        close.setOnClickListener(v -> stopSelf());
        autoBtn.setOnClickListener(v -> { touched(); prefs.edit().putBoolean("auto", !autoOn()).apply(); if (!autoOn()) auto.clear(); render(); });
        // ⟳ reinicia el capital de inicio solo si lo mantienes presionado 1.5 s, y se puede deshacer
        final Runnable doReset = () -> {
            if (Double.isNaN(balance)) return;
            final double before = start;
            start = balance; alerted = 0; state = 0; riskSince = 0; goalHits = 0;
            try { ((android.os.Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(60, 120)); } catch (Exception ignored) {}
            undoTxt.setVisibility(View.VISIBLE);
            undoTxt.setOnClickListener(x -> { start = before; undoTxt.setVisibility(View.GONE); render(); });
            ui.postDelayed(() -> undoTxt.setVisibility(View.GONE), 8000);
            render();
        };
        resetBtn.setOnTouchListener((v, e) -> {
            touched();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { resetBtn.setText("Sigue…"); ui.postDelayed(doReset, 1500); }
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) { ui.removeCallbacks(doReset); resetBtn.setText("⟳ mantén"); }
            return true;
        });
        Drag drag = new Drag();
        panel.setOnTouchListener(drag);
        bubbleTxt.setOnTouchListener(drag);
        headRow.setOnTouchListener(drag);
        whoTxt.setOnTouchListener(drag);
        stakeTxt.setOnTouchListener(drag);

        wm.addView(panel, lp);
        size = prefs.getInt("size", 1);
        setSize(size);
    }

    /** 0 bolita · 1 compacta · 2 abierta (con Trazador y botones). */
    private void setSize(int s) {
        size = s;
        prefs.edit().putInt("size", s).apply();
        bubbleTxt.setVisibility(s == 0 ? View.VISIBLE : View.GONE);
        compactBox.setVisibility(s == 0 ? View.GONE : View.VISIBLE);
        expandBox.setVisibility(s == 2 ? View.VISIBLE : View.GONE);
        sizeBtn.setText(s == 2 ? "▴" : "▾");
        int q = s == 1 ? View.VISIBLE : View.GONE;
        qZona.setVisibility(q); qTrazar.setVisibility(q); qBorrar.setVisibility(q);
        if (s != 1) trazMsg.setVisibility(View.GONE);
        if (s == 2) traz.attachLines(); else if (s == 0) traz.detachLines();
        panel.setPadding(dp(s == 0 ? 4 : 7), dp(s == 0 ? 2 : 3), dp(s == 0 ? 4 : 7), dp(s == 0 ? 2 : 6));
        touched();
        render();
        try { wm.updateViewLayout(panel, lp); } catch (Exception ignored) {}
    }

    /** Al tocarla se ve completa; si no la tocas unos segundos se vuelve casi transparente. */
    private void touched() {
        if (panel == null) return;
        panel.setAlpha(1f);
        ui.removeCallbacks(fade);
        ui.postDelayed(fade, 5000);
    }

    private static String fmt0(double v) { return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.US, "%.1f", v); }
    private static String short$(double v) { return com.ktrsis.rotador.Engine.stake(v); }

    private static String money(double v) { return com.ktrsis.rotador.Engine.money(v); }

    private void render() {
        if (panel == null || engine == null) return;
        // Rotador: siguiente operación
        double[] sq = engine.sequence();
        int t = engine.turn;
        int open = auto != null ? auto.pending().size() : 0;
        String stake = t < 0 ? "✕" : short$(sq[engine.step[t]]);
        bubbleTxt.setText(stake + (open > 0 ? " ⏳" : ""));
        stakeTxt.setText(stake);
        stakeTxt.setTextColor(t >= 0 && engine.step[t] == sq.length - 1 ? LOSS : FG);
        whoTxt.setText(t < 0 ? "⠿ LOTES QUEMADOS" : "⠿ L" + (t + 1) + " · P" + (engine.step[t] + 1) + "/" + sq.length + (open > 0 ? "  ⏳" + open : ""));
        whoTxt.setTextColor(t < 0 ? LOSS : MUTED);
        com.ktrsis.diario.Escalera esc = com.ktrsis.diario.Escalera.today(this);
        escTxt.setText(esc.line());
        escTxt.setVisibility(esc.ok ? View.VISIBLE : View.GONE);
        // Detección automática
        if (auto != null) lastAuto = auto.last;
        autoBtn.setText(autoOn() ? "Auto ✓" : "Auto ✕");
        autoBtn.setTextColor(autoOn() ? WIN : MUTED);
        autoTxt.setText(autoOn() ? "Auto (prueba): " + (auto == null || auto.last.isEmpty() ? "esperando tu primera operación…" : auto.last)
                : "Auto apagado: anota con Ganó / Perdió");

        // Guardián: riesgo y meta con el saldo real
        int frame;
        if (Double.isNaN(balance)) {
            balTxt.setText("Saldo —");
            plTxt.setText(status.isEmpty() ? "Leyendo tu saldo…" : "No veo el saldo"); plTxt.setTextColor(MUTED);
            stTxt.setText(status.isEmpty() ? "Leyendo saldo…" : "Abre el broker"); stTxt.setTextColor(GOLD);
            lvlTxt.setText(Double.isNaN(start) ? "−" + fmt0(riskPct()) + "% · +" + fmt0(goalPct()) + "%"
                    : "−" + short$(start - riskLevel()) + "  ·  +" + short$(goalLevel() - start));
            bar.set(0, 1, 1.5);
            frame = 0x66D9B45A;
        } else {
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
            else if (pl >= 0) { stTxt.setText((pl > 0.004 ? "+" + short$(pl) + " · " : "") + "faltan " + short$(goalAmt - pl)); stTxt.setTextColor(FG); }
            else { stTxt.setText(short$(pl) + " · margen " + short$(riskAmt + pl)); stTxt.setTextColor(GOLD); }
            frame = state == 1 ? WIN : state == -1 ? LOSS : 0x66D9B45A;
        }
        int fill = state == 1 ? 0xF2123A26 : state == -1 ? 0xF23A1414 : BG;
        panel.setBackground(rounded(fill, size == 0 ? 18 : 10, frame));
        if (state != 0) panel.setAlpha(1f);
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
                case MotionEvent.ACTION_DOWN: touched(); sx = lp.x; sy = lp.y; dx0 = e.getRawX(); dy0 = e.getRawY(); moved = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - dx0, dy = e.getRawY() - dy0;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved) { lp.x = sx + (int) dx; lp.y = sy + (int) dy; wm.updateViewLayout(panel, lp); }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                    else if (size == 0) setSize(1);          // tocar la bolita la abre
                    return true;
            }
            return false;
        }
    }
}
