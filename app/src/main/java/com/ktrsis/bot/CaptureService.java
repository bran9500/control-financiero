package com.ktrsis.bot;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Ventana flotante + captura de pantalla. La captura queda "abierta" toda la
 * sesión (Android pide permiso una sola vez al iniciar), pero solo se analiza
 * una imagen cuando se presiona LEER.
 */
public class CaptureService extends Service {
    static final int BG = 0xF20E1116, FG = 0xFFE8EBF0, MUTED = 0xFF8B93A1, LINE = 0xFF262C36,
            GREEN = 0xFF2FC47A, RED = 0xFFEF5A5A, GOLD = 0xFFD9B45A, GOLD_DIM = 0x66D9B45A,
            GREEN_BG = 0x332FC47A, RED_BG = 0x33EF5A5A;

    public static boolean running = false;
    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data";

    private Engine engine;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout panel;
    private TextView bubble, tfChip, autoChip, readBtn, statusTxt, contextTxt;
    private final LinearLayout[] rows = new LinearLayout[3];
    private final TextView[] rowTop = new TextView[3], rowBottom = new TextView[3];
    private boolean minimized = false;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread bgThread;
    private Handler bg;
    private final Object frameLock = new Object();
    private Image latest;
    private int capW, capH, capDpi;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::render;
    private final Runnable tick = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 1000); }
    };
    private final SimpleDateFormat hhmmss = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = Engine.get(this);
        bgThread = new HandlerThread("captura");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (projection == null) {
            if (intent == null || !intent.hasExtra(EXTRA_DATA)) { stopSelf(); return START_NOT_STICKY; }
            int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            try {
                projection = mpm.getMediaProjection(code, data);
            } catch (Exception e) { projection = null; }
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { ui.post(CaptureService.this::stopSelf); }
            }, ui);
            startCapture();
            showOverlay();
            running = true;
            scheduleAuto();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(autoRead);
        engine.removeListener(refresh);
        try { if (wm != null) wm.removeView(minimized ? bubble : panel); } catch (Exception ignored) {}
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        synchronized (frameLock) { if (latest != null) { latest.close(); latest = null; } }
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        bgThread.quitSafely();
        engine.notifyChanged();
        super.onDestroy();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("captura", "Bot flotante", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "captura")
                .setContentTitle("Bot de señales activo")
                .setContentText("Toca para abrir la app")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(2, n);
    }

    // ---------------- captura ----------------

    private void readMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect r = w.getCurrentWindowMetrics().getBounds();
            capW = r.width(); capH = r.height();
            capDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            //noinspection deprecation
            w.getDefaultDisplay().getRealMetrics(dm);
            capW = dm.widthPixels; capH = dm.heightPixels; capDpi = dm.densityDpi;
        }
    }

    private ImageReader newReader() {
        ImageReader r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(rd -> {
            Image img;
            try { img = rd.acquireLatestImage(); } catch (Exception e) { return; }
            if (img == null) return;
            synchronized (frameLock) {
                if (latest != null) latest.close();
                latest = img;
            }
        }, bg);
        return r;
    }

    private void startCapture() {
        readMetrics();
        reader = newReader();
        display = projection.createVirtualDisplay("bot", capW, capH, capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (display == null) return;
        int ow = capW, oh = capH;
        readMetrics();
        if (ow == capW && oh == capH) return;
        ImageReader old = reader;
        reader = newReader();
        display.resize(capW, capH, capDpi);
        display.setSurface(reader.getSurface());
        synchronized (frameLock) { if (latest != null) { latest.close(); latest = null; } }
        bg.post(() -> { try { old.close(); } catch (Exception ignored) {} });
    }

    private final Runnable autoRead = () -> { if (engine.settings.auto) { onRead(); } scheduleAuto(); };

    /** Modo AUTO: lee sola al empezar cada vela (unos segundos después, cuando la vela anterior ya cerró). */
    private void scheduleAuto() {
        ui.removeCallbacks(autoRead);
        if (!engine.settings.auto) return;
        long tf = engine.settings.tfSec * 1000L;
        long now = System.currentTimeMillis() + engine.settings.clockOffsetSec * 1000L;
        long minElapsed = Math.min(2500, tf * 3 / 10);
        long next = (now / tf) * tf + minElapsed;
        if (next <= now + 300) next += tf;
        ui.postDelayed(autoRead, next - now);
    }

    /** LEER: espera al inicio de la vela si hace falta, oculta la ventana, toma el cuadro y lo analiza. */
    private void onRead() {
        if (engine.busy) return;
        engine.busy = true;
        long tf = engine.settings.tfSec * 1000L;
        long now = System.currentTimeMillis() + engine.settings.clockOffsetSec * 1000L;
        long elapsed = now % tf;
        long minElapsed = Math.min(2500, tf * 3 / 10); // una vela recién abierta todavía no se ve bien
        long wait = elapsed < minElapsed ? minElapsed - elapsed : 0;
        engine.status = wait > 0 ? "Esperando que arranque la vela…" : "Leyendo…";
        render();
        ui.postDelayed(() -> {
            setOverlayVisible(false);
            ui.postDelayed(this::grabFrame, 350);
        }, wait);
    }

    private void grabFrame() {
        bg.post(() -> {
            Bitmap bmp = null;
            long ms = System.currentTimeMillis();
            synchronized (frameLock) {
                if (latest != null) {
                    try { bmp = toBitmap(latest); } catch (Exception ignored) {}
                }
            }
            ui.post(() -> setOverlayVisible(true));
            if (bmp == null) {
                engine.status = "No llegó imagen de la pantalla. Cierra y vuelve a abrir la ventana.";
                engine.busy = false;
                engine.notifyChanged();
                return;
            }
            engine.analyze(bmp, ms);
            bmp.recycle();
            Analyzer.Output o = engine.last();
            if (o != null && o.captureMs == ms && !o.alerts.isEmpty()) {
                boolean strong = false;
                for (Rules.Verdict v : o.verdicts) if (v.type == Rules.V_DIR || v.type == Rules.V_BIRTH_REVERSE || v.type == Rules.V_BIRTH_SAME) strong = true;
                if (strong) vibrate();
            }
            if (engine.needsCalibration) ui.post(this::openApp);
        });
    }

    private void vibrate() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }

    private static Bitmap toBitmap(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int pixStride = p.getPixelStride(), rowStride = p.getRowStride();
        int w = img.getWidth(), h = img.getHeight();
        int padW = rowStride / pixStride;
        Bitmap b = Bitmap.createBitmap(padW, h, Bitmap.Config.ARGB_8888);
        buf.rewind();
        b.copyPixelsFromBuffer(buf);
        if (padW == w) return b;
        Bitmap c = Bitmap.createBitmap(b, 0, 0, w, h);
        b.recycle();
        return c;
    }

    // ---------------- ventana flotante ----------------

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private GradientDrawable rounded(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return t;
    }

    private void showOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = getSharedPreferences("bot", MODE_PRIVATE).getInt("ovx", dp(8));
        lp.y = getSharedPreferences("bot", MODE_PRIVATE).getInt("ovy", dp(120));
        buildPanel();
        buildBubble();
        wm.addView(panel, lp);
        engine.addListener(refresh);
        ui.post(tick);
    }

    private void setOverlayVisible(boolean v) {
        View cur = minimized ? bubble : panel;
        if (cur != null) cur.setVisibility(v ? View.VISIBLE : View.INVISIBLE);
    }

    private void buildPanel() {
        int w = dp(148);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(BG, 10, GOLD_DIM));
        panel.setPadding(dp(6), dp(2), dp(6), dp(6));

        // Barra: ⠿ arrastrar (toque = abrir app) · temporalidad · – · ×
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView grip = text("⠿", 15, GOLD, true);
        grip.setPadding(dp(2), 0, dp(6), 0);
        tfChip = text("M1", 11, FG, true);
        tfChip.setBackground(rounded(0xFF1B2029, 5, LINE));
        tfChip.setPadding(dp(6), 0, dp(6), 0);
        autoChip = text("AUTO", 10, MUTED, true);
        autoChip.setPadding(dp(5), 0, dp(5), 0);
        TextView min = text("–", 16, MUTED, true);
        TextView close = text("×", 16, MUTED, true);
        min.setPadding(dp(8), 0, dp(6), 0);
        close.setPadding(dp(6), 0, dp(1), 0);
        top.addView(grip);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(autoChip);
        LinearLayout.LayoutParams tfl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tfl.leftMargin = dp(4);
        top.addView(tfChip, tfl);
        top.addView(min);
        top.addView(close);
        panel.addView(top, new LinearLayout.LayoutParams(w, dp(24)));

        readBtn = text("📷 LEER", 12, 0xFF0E1116, true);
        readBtn.setGravity(Gravity.CENTER);
        readBtn.setBackground(rounded(GOLD, 7, 0));
        readBtn.setPadding(0, dp(5), 0, dp(5));
        panel.addView(readBtn, new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT));

        statusTxt = text("", 9, MUTED, false);
        statusTxt.setSingleLine(true);
        statusTxt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusTxt.setPadding(dp(1), dp(3), 0, 0);
        panel.addView(statusTxt, new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(6), dp(3), dp(6), dp(3));
            rowTop[i] = text("", 12, FG, true);
            rowTop[i].setSingleLine(true);
            rowBottom[i] = text("", 9, MUTED, false);
            rowBottom[i].setSingleLine(true);
            rowBottom[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(rowTop[i]);
            row.addView(rowBottom[i]);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
            l.topMargin = dp(3);
            panel.addView(row, l);
            rows[i] = row;
        }
        contextTxt = text("", 9, MUTED, false);
        contextTxt.setVisibility(View.GONE);

        attachDrag(grip, panel);
        readBtn.setOnClickListener(v -> onRead());
        tfChip.setOnClickListener(v -> { engine.cycleTimeframe(); render(); });
        autoChip.setOnClickListener(v -> { engine.setAuto(!engine.settings.auto); scheduleAuto(); render(); });
        min.setOnClickListener(v -> setMinimized(true));
        close.setOnClickListener(v -> stopSelf());
    }

    private void buildBubble() {
        bubble = text("📷", 16, FG, true);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(rounded(BG, 20, GOLD));
        int s = dp(40);
        bubble.setMinWidth(s);
        bubble.setMinHeight(s);
        attachDrag(bubble, bubble);
    }

    private void setMinimized(boolean m) {
        if (m == minimized) return;
        try { wm.removeView(minimized ? bubble : panel); } catch (Exception ignored) {}
        minimized = m;
        wm.addView(minimized ? bubble : panel, lp);
        render();
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    /** Arrastrar desde `handle` mueve la ventana; un toque corto en la burbuja la abre. */
    private void attachDrag(View handle, View target) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - tx), dy = (int) (e.getRawY() - ty);
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        if (moved) {
                            lp.x = sx + dx; lp.y = sy + dy;
                            try { wm.updateViewLayout(target, lp); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) getSharedPreferences("bot", MODE_PRIVATE).edit().putInt("ovx", lp.x).putInt("ovy", lp.y).apply();
                        else if (target == bubble) setMinimized(false);
                        else openApp(); // toque corto en ⠿ abre la app
                        return true;
                }
                return false;
            }
        });
    }

    private String countdown(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 60 ? String.format(Locale.US, "%d:%02d", s / 60, s % 60) : s + "s";
    }

    /** ¿La alerta todavía sirve? La de la vela en curso solo al principio de la vela. */
    private boolean usable(Analyzer.Output o, Rules.Alert a, long now) {
        long entry = o.entryMs(a);
        long late = Math.max(4000, o.tfMs / 6);
        return now <= entry + late;
    }

    private void render() {
        if (panel == null) return;
        if (minimized) return;
        tfChip.setText(Engine.tfLabel(engine.settings.tfSec));
        autoChip.setText(engine.settings.auto ? "AUTO ●" : "AUTO");
        autoChip.setTextColor(engine.settings.auto ? 0xFF0E1116 : MUTED);
        autoChip.setBackground(rounded(engine.settings.auto ? GREEN : 0xFF1B2029, 5, engine.settings.auto ? 0 : LINE));
        readBtn.setAlpha(engine.busy ? 0.5f : 1f);
        readBtn.setText(engine.busy ? "LEYENDO…" : "📷 LEER");

        Analyzer.Output o = engine.last();
        long now = System.currentTimeMillis();
        String st = engine.status == null ? "" : engine.status;
        if (o != null && !engine.busy && o.error == null)
            st = (o.det.candles.size() - 1) + " velas · " + hhmmss.format(new Date(o.captureMs)).substring(0, 5) + (o.lateral ? " · lateral" : "");
        statusTxt.setText(st);

        statusTxt.setTextColor(MUTED);
        // Una fila por vela: el veredicto que junta todas las estrategias de esa vela.
        int shown = 0;
        if (o != null) {
            for (Rules.Verdict v : o.verdicts) {
                if (shown >= 3) break;
                if (now > o.entryMs(v) + Math.max(4000, o.tfMs / 6)) continue; // ya pasó
                fillRow(shown, o, v, now);
                shown++;
            }
        }
        for (int i = shown; i < 3; i++) rows[i].setVisibility(View.GONE);
        if (o != null && shown == 0 && !engine.busy && o.error == null) {
            rows[0].setVisibility(View.VISIBLE);
            rows[0].setBackground(rounded(0xFF161A21, 7, LINE));
            rowTop[0].setText(o.alerts.isEmpty() ? "Sin estrategia" : "Ya pasaron");
            rowTop[0].setTextColor(MUTED);
            rowBottom[0].setText(engine.settings.auto ? "esperando la próxima vela" : "LEER otra vez");
        }
        contextTxt.setVisibility(View.GONE);
    }

    static String sq(char c) { return c == 'V' ? "🟩" : "🟥"; }

    private void fillRow(int i, Analyzer.Output o, Rules.Verdict v, long now) {
        long entry = o.entryMs(v), in = entry - now;
        String hora = hhmmss.format(new Date(entry)).substring(0, engine.settings.tfSec < 60 ? 8 : 5);
        String when = in > 0 ? "en " + countdown(in) : "¡AHORA!";
        String names = String.join(" + ", v.names);
        rows[i].setVisibility(View.VISIBLE);
        rowBottom[i].setTextColor(FG);
        if (v.type == Rules.V_DIR) {
            boolean buy = v.dir == 'V';
            rows[i].setBackground(rounded(buy ? GREEN_BG : RED_BG, 7, buy ? 0x882FC47A : 0x88EF5A5A));
            rowTop[i].setText((buy ? "▲ BUY " : "▼ SELL ") + hora + (v.need != 0 ? "  si nace " + sq(v.need) : ""));
            rowTop[i].setTextColor(buy ? GREEN : RED);
            rowBottom[i].setText(v.risk != 0 ? "⚠ si esta cierra " + sq(v.risk) + ", no entres · " + names : when + " · " + names);
            if (v.risk != 0) rowBottom[i].setTextColor(GOLD);
        } else if (v.type == Rules.V_NO) {
            rows[i].setBackground(rounded(0x22D9B45A, 7, GOLD_DIM));
            rowTop[i].setText("⛔ " + hora + " NO ENTRAR");
            rowTop[i].setTextColor(GOLD);
            rowBottom[i].setText("se contradicen · " + names);
        } else if (v.type == Rules.V_IF) {
            boolean buy = v.dir == 'V';
            rows[i].setBackground(rounded(0xFF161A21, 7, LINE));
            rowTop[i].setText("¿cierra " + sq(v.ifColor) + "? → " + (buy ? "▲" : "▼") + " " + hora);
            rowTop[i].setTextColor(buy ? GREEN : RED);
            rowBottom[i].setText("aviso · " + names);
            rowBottom[i].setTextColor(MUTED);
        } else {
            boolean rev = v.type == Rules.V_BIRTH_REVERSE;
            rows[i].setBackground(rounded(0x22D9B45A, 7, GOLD_DIM));
            rowTop[i].setText("⚡ " + hora + " mira cómo nace");
            rowTop[i].setTextColor(GOLD);
            rowBottom[i].setText((rev ? "nace 🟩→SELL · 🟥→BUY" : "nace 🟩→BUY · 🟥→SELL") + " · " + names);
        }
    }

    static String patternLabel(String p) {
        switch (p) {
            case "DOJI": return "➕ Doji";
            case "MARTILLO": return "🔨 Martillo";
            case "HOMBRE_COLGADO": return "🔨 Hombre colgado";
            case "MARTILLO_INVERTIDO": return "🔧 Martillo invertido";
            case "ESTRELLA_FUGAZ": return "🌠 Estrella fugaz";
            case "ENVOLVENTE_ALCISTA": return "🟢 Envolvente alcista";
            case "ENVOLVENTE_BAJISTA": return "🔴 Envolvente bajista";
            case "HARAMI_ALCISTA": return "🟩 Harami alcista";
            case "HARAMI_BAJISTA": return "🟥 Harami bajista";
            case "PINZAS_TECHO": return "✂️ Pinzas techo";
            case "PINZAS_SUELO": return "✂️ Pinzas suelo";
            case "TRES_SOLDADOS_BLANCOS": return "🟢 3 soldados";
            case "TRES_CUERVOS_NEGROS": return "🔴 3 cuervos";
            case "ESTRELLA_MANANA": return "🌅 Estrella mañana";
            case "ESTRELLA_NOCHE": return "🌆 Estrella noche";
            default: return p;
        }
    }
}
