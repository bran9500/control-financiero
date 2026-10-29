package com.ktrsis.trazador;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.ktrsis.bot.Market;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trazador: ventana flotante encima del broker.
 *  - "Zona": en una temporalidad alta, encuentra la resistencia y el soporte más fuertes y los guarda en PRECIO.
 *  - "Trazar": en la temporalidad en la que operas, dibuja esas zonas guardadas y el canal diagonal del último movimiento.
 * Lee la pantalla con la misma captura que el Guardián y los precios del eje derecho con ML Kit (sin internet).
 */
public class TrazadorService extends Service {
    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    public static volatile boolean running = false;

    private static final int BG = 0xEE14181F, FG = 0xFFE8EBF0, MUTED = 0xFF9AA3AE, GOLD = 0xFFD9B45A;
    // Colores que el detector de velas NO confunde con velas rojas o verdes.
    static final int C_RES = 0xFFFF5AF0, C_SUP = 0xFF3FB8FF, C_CHAN = 0xFFFFE14D, C_EXT = 0xFFFFFFFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread;
    private Handler bg;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private final Object frameLock = new Object();
    private Image latest;
    private int capW, capH, capDpi;
    private TextRecognizer ocr;
    private SharedPreferences prefs;

    private WindowManager wm;
    private LinesView lines;
    private WindowManager.LayoutParams lpLines, lpPanel;
    private LinearLayout panel;
    private TextView status;
    private volatile boolean busy = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("trazador", MODE_PRIVATE);
        bgThread = new HandlerThread("trazador");
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
                @Override public void onStop() { ui.post(TrazadorService.this::stopSelf); }
            }, ui);
            startCapture();
            showOverlays();
            running = true;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) {}
        try { if (lines != null) wm.removeView(lines); } catch (Exception ignored) {}
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
        nm.createNotificationChannel(new NotificationChannel("trazador", "Trazador de zonas", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, TrazadorActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "trazador")
                .setContentTitle("Trazador activo")
                .setContentText("Toca Zona o Trazar en la ventanita")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(5, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(5, n);
    }

    // ---------------- captura ----------------
    private void startCapture() {
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
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(rd -> {
            Image img;
            try { img = rd.acquireLatestImage(); } catch (Exception e) { return; }
            if (img == null) return;
            synchronized (frameLock) { if (latest != null) latest.close(); latest = img; }
        }, bg);
        display = projection.createVirtualDisplay("trazador", capW, capH, capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);
    }

    private Bitmap frame() {
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

    // ---------------- lectura y análisis ----------------
    private static final Pattern PRICE = Pattern.compile("^\\s*([0-9]{1,6})[.,]([0-9]{2,6})\\s*$");
    private static final Pattern TF = Pattern.compile("^\\s*([SMHD][0-9]{1,2})\\s*$");
    private static final Pattern ASSET = Pattern.compile("([A-Z]{3}\\s?/\\s?[A-Z]{3}(?:\\s?OTC)?)");
    private static final Pattern PANEL = Pattern.compile("(?i)^(hora|importe|tiempo|monto|time|amount|duraci[oó]n)\\b.*");

    /** Lo que se leyó de una pantalla: velas analizadas, mapa de precios, temporalidad y activo. */
    static final class Read {
        Trazado.Result res;
        double[] map;
        String tf = "", asset = "";
        Trazado.Region region;
        int w, h;
    }

    private void run(boolean saveZone) {
        if (busy) return;
        busy = true;
        setStatus("Leyendo el gráfico…");
        final Rect hide = panelRect();
        bg.post(() -> {
            Bitmap bmp = frame();
            if (bmp == null) { done("No veo la pantalla todavía. Intenta otra vez."); return; }
            if (hide != null) {   // tapa la propia ventanita para no leerse a sí mismo
                Paint pt = new Paint(); pt.setColor(0xFF000000);
                Bitmap m = bmp.copy(Bitmap.Config.ARGB_8888, true); bmp.recycle(); bmp = m;
                new Canvas(bmp).drawRect(hide, pt);
            }
            final Bitmap img = bmp;
            ocr.process(InputImage.fromBitmap(img, 0))
                    .addOnSuccessListener(bg::post, t -> {
                        try { onRead(analyze(img, t), saveZone); }
                        catch (Exception e) { done("No pude leer el gráfico."); }
                        finally { img.recycle(); }
                    })
                    .addOnFailureListener(bg::post, e -> { img.recycle(); done("No pude leer el texto de la pantalla."); });
        });
    }

    private Read analyze(Bitmap img, Text t) {
        int w = img.getWidth(), h = img.getHeight();
        Read rd = new Read(); rd.w = w; rd.h = h;
        List<double[]> labels = new ArrayList<>();
        int axisLeft = w, top = (int) (h * 0.16), bottom = (int) (h * 0.62);
        boolean topFound = false;
        for (Text.TextBlock b : t.getTextBlocks()) for (Text.Line l : b.getLines()) {
            Rect r = l.getBoundingBox(); if (r == null) continue;
            String s = l.getText();
            Matcher m = PRICE.matcher(s);
            if (m.matches() && r.centerX() > w * 0.62) {
                try {
                    labels.add(new double[]{r.centerY(), Double.parseDouble(m.group(1) + "." + m.group(2))});
                    if (r.centerX() > w * 0.72) axisLeft = Math.min(axisLeft, r.left);
                } catch (Exception ignored) {}
                continue;
            }
            Matcher tm = TF.matcher(s);
            if (tm.matches()) { rd.tf = tm.group(1); continue; }
            Matcher am = ASSET.matcher(s.toUpperCase(Locale.US));
            if (am.find() && r.centerY() < h * 0.35) {
                rd.asset = am.group(1).replace(" ", "");
                if (!topFound || r.bottom + 12 > top) { top = Math.max(top, r.bottom + 12); topFound = true; }
                continue;
            }
            if (PANEL.matcher(s.trim()).matches() && r.top > h * 0.35) bottom = Math.min(bottom, r.top - 12);
        }
        int x1 = axisLeft < w ? axisLeft - 6 : (int) (w * 0.82);
        rd.region = new Trazado.Region(0, top, Math.max(10, x1), Math.max(top + 10, bottom));
        rd.map = Trazado.priceMap(labels);
        int[] px = new int[w * h];
        img.getPixels(px, 0, w, 0, 0, w, h);
        List<Market.Geo> c = Trazado.candles(px, w, h, rd.region);
        rd.res = Trazado.analyze(c, rd.region);
        return rd;
    }

    private String assetKey(Read rd) { return rd.asset.isEmpty() ? "_" : rd.asset; }

    private JSONArray zones(String asset) {
        try { return new JSONArray(prefs.getString("z:" + asset, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void onRead(Read rd, boolean saveZone) {
        if (rd.res.error != null) { done(rd.res.error); return; }
        List<LinesView.Item> items = new ArrayList<>();
        String asset = assetKey(rd);
        JSONArray z = zones(asset);
        String tf = rd.tf.isEmpty() ? "?" : rd.tf;
        if (saveZone) {
            if (rd.map == null) { done("No pude leer los precios del eje derecho. Deja visibles los números del eje."); return; }
            JSONArray nz = new JSONArray();
            for (int i = 0; i < z.length(); i++) { JSONObject o = z.optJSONObject(i); if (o != null && !tf.equals(o.optString("tf"))) nz.put(o); }
            int saved = 0;
            for (Trazado.Level L : rd.res.levels) {
                if (!L.strongest) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("p", Trazado.yToPrice(rd.map, L.y)); o.put("r", L.resistance); o.put("tf", tf);
                    o.put("t", L.touches); o.put("at", System.currentTimeMillis());
                    nz.put(o); saved++;
                } catch (Exception ignored) {}
            }
            prefs.edit().putString("z:" + asset, nz.toString()).apply();
            z = nz;
            String msg = saved == 0 ? "No encontré una zona clara en esta temporalidad."
                    : "Guardé " + saved + " zona" + (saved == 1 ? "" : "s") + " de " + tf + (rd.asset.isEmpty() ? "" : " para " + rd.asset)
                    + ". Cambia a la temporalidad en la que operas y toca Trazar.";
            addZones(items, z, rd);
            post(items, msg);
            return;
        }
        // Trazar: zonas guardadas + canal diagonal + soportes/resistencias de esta temporalidad
        int drawn = addZones(items, z, rd);
        for (Trazado.Line L : rd.res.channel) {
            LinesView.Item it = new LinesView.Item();
            it.x1 = (float) L.x1; it.y1 = (float) L.y1; it.x2 = (float) L.x2; it.y2 = (float) L.y2;
            it.color = "extreme".equals(L.kind) ? C_EXT : C_CHAN;
            it.width = "extreme".equals(L.kind) ? 2 : 3;
            it.dashed = "extreme".equals(L.kind);
            items.add(it);
        }
        for (Trazado.Level L : rd.res.levels) {
            if (!L.strongest) continue;
            LinesView.Item it = hline((float) L.y, rd.region.x1, L.resistance ? C_RES : C_SUP, 2, true,
                    (L.resistance ? "R " : "S ") + tf + (rd.map != null ? "  " + fmt(Trazado.yToPrice(rd.map, L.y)) : ""));
            items.add(it);
        }
        String msg = (rd.res.downMove ? "Canal de bajada" : "Canal de subida") + " · " + rd.res.candles + " velas"
                + (z.length() > 0 ? (drawn > 0 ? " · " + drawn + " zona" + (drawn == 1 ? "" : "s") + " guardada" + (drawn == 1 ? "" : "s")
                : " · tus zonas quedan fuera de lo que se ve") : " · sin zonas guardadas (usa Zona en una temporalidad mayor)")
                + (rd.map == null && z.length() > 0 ? " · no leí los precios del eje" : "");
        post(items, msg);
    }

    /** Dibuja las zonas guardadas (en precio) en la posición que les toca en el gráfico de ahora. */
    private int addZones(List<LinesView.Item> items, JSONArray z, Read rd) {
        if (rd.map == null) return 0;
        int n = 0;
        for (int i = 0; i < z.length(); i++) {
            JSONObject o = z.optJSONObject(i); if (o == null) continue;
            double p = o.optDouble("p", Double.NaN); if (Double.isNaN(p)) continue;
            float y = (float) Trazado.priceToY(rd.map, p);
            if (y < rd.region.y0 - 4 || y > rd.region.y1 + 4) continue;
            boolean r = o.optBoolean("r");
            items.add(hline(y, rd.w, r ? C_RES : C_SUP, 5, false, (r ? "RESISTENCIA " : "SOPORTE ") + o.optString("tf") + "  " + fmt(p)));
            n++;
        }
        return n;
    }

    private LinesView.Item hline(float y, float x2, int color, float width, boolean dashed, String label) {
        LinesView.Item it = new LinesView.Item();
        it.x1 = 0; it.y1 = y; it.x2 = x2; it.y2 = y; it.color = color; it.width = width; it.dashed = dashed; it.label = label;
        return it;
    }

    private static String fmt(double p) {
        int dec = p >= 1000 ? 2 : p >= 100 ? 3 : 5;
        return String.format(Locale.US, "%." + dec + "f", p);
    }

    private void post(List<LinesView.Item> items, String msg) {
        ui.post(() -> { lines.set(items); setStatus(msg); busy = false; });
    }
    private void done(String msg) { ui.post(() -> { setStatus(msg); busy = false; }); }

    // ---------------- ventanas flotantes ----------------
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private GradientDrawable rounded(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke); return g;
    }
    private TextView btn(String s, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setGravity(Gravity.CENTER); t.setPadding(dp(10), dp(7), dp(10), dp(7));
        t.setBackground(rounded(0xFF232A35, 8, 0)); return t;
    }

    private Rect panelRect() {
        if (panel == null || lpPanel == null) return null;
        return new Rect(lpPanel.x - dp(4), lpPanel.y - dp(4), lpPanel.x + panel.getWidth() + dp(4), lpPanel.y + panel.getHeight() + dp(4));
    }

    private void setStatus(String s) { if (status != null) status.setText(s); }

    private void showOverlays() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        // Capa de líneas: toda la pantalla, no recibe toques
        lines = new LinesView(this);
        lpLines = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lpLines.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) lpLines.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(lines, lpLines);

        // Ventanita de botones
        lpPanel = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lpPanel.gravity = Gravity.TOP | Gravity.START;
        lpPanel.x = prefs.getInt("x", dp(8));
        lpPanel.y = prefs.getInt("y", (int) (getResources().getDisplayMetrics().heightPixels * 0.12f));
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(BG, 12, 0x66D9B45A));
        panel.setPadding(dp(8), dp(4), dp(8), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this); title.setText("⠿ TRAZADOR"); title.setTextColor(GOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10); title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        TextView close = new TextView(this); close.setText("×"); close.setTextColor(MUTED); close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setPadding(dp(10), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(close);
        panel.addView(head);

        LinearLayout row = new LinearLayout(this);
        TextView zona = btn("Zona", C_RES), trazar = btn("Trazar", C_CHAN), borrar = btn("Borrar", FG);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.setMargins(0, dp(4), dp(6), 0);
        row.addView(zona, bp); row.addView(trazar, bp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.setMargins(0, dp(4), 0, 0);
        row.addView(borrar, bl);
        panel.addView(row);

        status = new TextView(this); status.setTextColor(MUTED); status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setMaxWidth(dp(230)); status.setPadding(0, dp(6), 0, 0);
        status.setText("1) En M15 o M5 toca Zona.  2) Cambia a tu temporalidad y toca Trazar.");
        panel.addView(status);

        zona.setOnClickListener(v -> run(true));
        trazar.setOnClickListener(v -> run(false));
        borrar.setOnClickListener(v -> { lines.set(new ArrayList<>()); setStatus("Líneas borradas. Mantén presionado Borrar para olvidar las zonas guardadas."); });
        borrar.setOnLongClickListener(v -> {
            SharedPreferences.Editor e = prefs.edit();
            for (String k : prefs.getAll().keySet()) if (k.startsWith("z:")) e.remove(k);
            e.apply(); lines.set(new ArrayList<>());
            Toast.makeText(this, "Olvidé todas las zonas guardadas", Toast.LENGTH_SHORT).show();
            setStatus("Zonas borradas.");
            return true;
        });
        close.setOnClickListener(v -> stopSelf());
        head.setOnTouchListener(new Drag());
        title.setOnTouchListener(new Drag());
        wm.addView(panel, lpPanel);
    }

    /** Arrastra la ventanita desde el título. */
    private class Drag implements View.OnTouchListener {
        float sx, sy; int ox, oy;
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); ox = lpPanel.x; oy = lpPanel.y; return true;
                case MotionEvent.ACTION_MOVE:
                    lpPanel.x = ox + (int) (e.getRawX() - sx); lpPanel.y = oy + (int) (e.getRawY() - sy);
                    try { wm.updateViewLayout(panel, lpPanel); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    prefs.edit().putInt("x", lpPanel.x).putInt("y", lpPanel.y).apply();
                    return true;
            }
            return false;
        }
    }

    /** Capa transparente donde se dibujan las líneas, alineada con la pantalla capturada. */
    static class LinesView extends View {
        static class Item { float x1, y1, x2, y2, width = 3; int color; boolean dashed; String label; }
        private final List<Item> items = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG), bgp = new Paint();
        private final int[] loc = new int[2];
        private final float dens;
        LinesView(Context c) {
            super(c);
            dens = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            tp.setTextSize(11 * dens); tp.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            bgp.setColor(0xCC0E1116);
        }
        void set(List<Item> list) { items.clear(); items.addAll(list); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            getLocationOnScreen(loc);
            c.save(); c.translate(-loc[0], -loc[1]);   // las líneas están en coordenadas de la pantalla completa
            for (Item it : items) {
                p.setColor(it.color); p.setStrokeWidth(it.width * dens * 0.6f);
                p.setPathEffect(it.dashed ? new DashPathEffect(new float[]{10 * dens, 6 * dens}, 0) : null);
                c.drawLine(it.x1, it.y1, it.x2, it.y2, p);
                if (it.label != null) {
                    float tw = tp.measureText(it.label), x = Math.max(4 * dens, it.x2 - tw - 10 * dens), y = it.y1 - 4 * dens;
                    c.drawRect(x - 3 * dens, y - 12 * dens, x + tw + 3 * dens, y + 3 * dens, bgp);
                    tp.setColor(it.color); c.drawText(it.label, x, y, tp);
                }
            }
            c.restore();
        }
    }
}
