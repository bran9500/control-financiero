package com.ktrsis.bot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Estado compartido entre la ventana flotante y la app: ajustes, zona calibrada, historial y última lectura. */
public final class Engine {
    private static Engine instance;

    public static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    public static final int[] TF_SECONDS = {5, 10, 15, 30, 60, 120, 180, 300, 600, 900};

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();

    public final Analyzer.Settings settings = new Analyzer.Settings();
    private volatile Analyzer.Output last;
    private volatile String lastJson = "null";
    public volatile String status = "";
    public volatile boolean busy = false;
    public volatile boolean needsCalibration = false;

    private Engine(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("bot", Context.MODE_PRIVATE);
        settings.tfSec = prefs.getInt("tf", 60);
        settings.clockOffsetSec = prefs.getInt("clockOffset", 0);
        settings.auto = prefs.getBoolean("auto", false);
        String dis = prefs.getString("disabled", "");
        for (String k : dis.split(",")) if (!k.isEmpty()) settings.disabled.add(k);
        lastJson = prefs.getString("lastJson", "null");
    }

    // ---------- listeners ----------
    public synchronized void addListener(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    public synchronized void removeListener(Runnable r) { listeners.remove(r); }
    public void notifyChanged() {
        main.post(() -> {
            List<Runnable> copy;
            synchronized (this) { copy = new ArrayList<>(listeners); }
            for (Runnable r : copy) r.run();
        });
    }

    // ---------- ajustes ----------
    public void setTimeframe(int sec) {
        if (sec == settings.tfSec) return;
        settings.tfSec = sec;
        prefs.edit().putInt("tf", sec).apply();
        clearLast();
    }

    public void cycleTimeframe() {
        int idx = 0;
        for (int i = 0; i < TF_SECONDS.length; i++) if (TF_SECONDS[i] == settings.tfSec) idx = i;
        setTimeframe(TF_SECONDS[(idx + 1) % TF_SECONDS.length]);
    }

    public void setClockOffset(int sec) {
        settings.clockOffsetSec = sec;
        prefs.edit().putInt("clockOffset", sec).apply();
        notifyChanged();
    }

    public void setAuto(boolean on) {
        settings.auto = on;
        prefs.edit().putBoolean("auto", on).apply();
        notifyChanged();
    }

    public void setEnabled(String key, boolean on) {
        if (on) settings.disabled.remove(key); else settings.disabled.add(key);
        prefs.edit().putString("disabled", String.join(",", settings.disabled)).apply();
        notifyChanged();
    }

    public void clearLast() {
        last = null;
        lastJson = "null";
        prefs.edit().putString("lastJson", "null").apply();
        notifyChanged();
    }

    public static String tfLabel(int sec) {
        if (sec < 60) return "S" + sec;
        if (sec < 3600) return "M" + (sec / 60);
        return "H" + (sec / 3600);
    }

    // ---------- zona calibrada (fracciones de la pantalla, por orientación) ----------
    static String orient(int w, int h) { return w > h ? "l" : "p"; }

    public float[] region(String orient) {
        String s = prefs.getString("region_" + orient, null);
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
        } catch (NumberFormatException e) { return null; }
    }

    public void setRegion(String orient, float l, float t, float r, float b) {
        prefs.edit().putString("region_" + orient, String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", l, t, r, b)).apply();
        needsCalibration = false;
        clearLast();
        status = "Zona guardada. Ya puedes leer velas.";
        notifyChanged();
    }

    public String lastFullOrient() { return prefs.getString("fullOrient", "p"); }

    // ---------- análisis ----------
    public Analyzer.Output last() { return last; }

    /** Se llama desde un hilo de fondo con la captura completa de la pantalla. */
    public void analyze(Bitmap full, long captureMs) {
        try {
            String o = orient(full.getWidth(), full.getHeight());
            saveJpeg(scaled(full, 1080), "full.jpg", 80);
            prefs.edit().putString("fullOrient", o).apply();

            float[] reg = region(o);
            if (reg == null) {
                needsCalibration = true;
                status = "Falta calibrar: abre la app y marca la zona de las velas.";
                notifyChanged();
                return;
            }
            int W = full.getWidth(), H = full.getHeight();
            int x0 = clamp(Math.round(reg[0] * W), 0, W - 1), y0 = clamp(Math.round(reg[1] * H), 0, H - 1);
            int x1 = clamp(Math.round(reg[2] * W), x0 + 1, W), y1 = clamp(Math.round(reg[3] * H), y0 + 1, H);
            int cw = x1 - x0, chh = y1 - y0;
            int[] px = new int[cw * chh];
            full.getPixels(px, 0, cw, x0, y0, cw, chh);

            Detector.Result det = Detector.detect(px, cw, chh);
            Analyzer.Output out = Analyzer.run(det, settings, captureMs);
            last = out;
            lastJson = Analyzer.toJson(out);
            prefs.edit().putString("lastJson", lastJson).apply();

            Bitmap crop = Bitmap.createBitmap(full, x0, y0, cw, chh);
            saveJpeg(crop, "crop.jpg", 88);
            if (crop != full) crop.recycle();

            if (out.error != null) status = out.error;
            else status = (det.candles.size() - 1) + " velas leídas" + (out.alerts.isEmpty() ? " · sin estrategias" : " · " + out.alerts.size() + " alertas");
        } catch (Throwable t) {
            status = "Error al leer: " + t.getMessage();
        } finally {
            busy = false;
            notifyChanged();
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static Bitmap scaled(Bitmap b, int maxW) {
        if (b.getWidth() <= maxW) return b;
        int h = Math.round(b.getHeight() * (maxW / (float) b.getWidth()));
        return Bitmap.createScaledBitmap(b, maxW, h, true);
    }

    private void saveJpeg(Bitmap b, String name, int q) {
        try (FileOutputStream fo = new FileOutputStream(new File(ctx.getFilesDir(), name))) {
            b.compress(Bitmap.CompressFormat.JPEG, q, fo);
        } catch (Exception ignored) {}
    }

    public String imageDataUri(String name) {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    public String stateJson(boolean overlayRunning) {
        float[] rp = region("p"), rl = region("l");
        StringBuilder st = new StringBuilder("[");
        for (int i = 0; i < Rules.STRATEGIES.length; i++) {
            String[] r = Rules.STRATEGIES[i];
            if (i > 0) st.append(',');
            st.append("{\"key\":").append(Analyzer.q(r[0])).append(",\"name\":").append(Analyzer.q(r[1]))
                    .append(",\"desc\":").append(Analyzer.q(r[2])).append(",\"on\":").append(!settings.disabled.contains(r[0])).append("}");
        }
        st.append("]");
        return "{\"tf\":" + settings.tfSec +
                ",\"tfLabel\":" + Analyzer.q(tfLabel(settings.tfSec)) +
                ",\"clockOffset\":" + settings.clockOffsetSec +
                ",\"auto\":" + settings.auto +
                ",\"overlay\":" + overlayRunning +
                ",\"busy\":" + busy +
                ",\"needsCalibration\":" + needsCalibration +
                ",\"status\":" + Analyzer.q(status == null ? "" : status) +
                ",\"regionP\":" + arr(rp) + ",\"regionL\":" + arr(rl) +
                ",\"fullOrient\":" + Analyzer.q(lastFullOrient()) +
                ",\"strategies\":" + st +
                ",\"now\":" + System.currentTimeMillis() +
                ",\"last\":" + lastJson + "}";
    }

    private static String arr(float[] a) {
        if (a == null) return "null";
        return String.format(Locale.US, "[%.5f,%.5f,%.5f,%.5f]", a[0], a[1], a[2], a[3]);
    }
}
