package com.ktrsis.trazador;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trazador como pieza: botones Zona / Trazar / Borrar + capa de líneas encima del gráfico.
 * No tiene su propia grabación de pantalla: le pide el cuadro a quien lo usa (el Guardián),
 * así las dos cosas funcionan juntas en una sola ventanita flotante.
 *  - "Zona": en una temporalidad alta, encuentra la resistencia y el soporte más fuertes y los guarda en PRECIO.
 *  - "Trazar": en la temporalidad en la que operas, dibuja esas zonas guardadas y el canal diagonal del último movimiento.
 */
public class TrazadorCore {
    public interface FrameSource {
        /** Cuadro completo de la pantalla (se llama en el hilo de fondo). */
        Bitmap frame();
        /** Zona de la propia ventanita, para taparla y no leerse a sí mismo. */
        Rect hide();
    }

    private static final int FG = 0xFFE8EBF0, MUTED = 0xFF9AA3AE;
    // Colores que el detector de velas NO confunde con velas rojas o verdes.
    static final int C_RES = 0xFFFF5AF0, C_SUP = 0xFF3FB8FF, C_CHAN = 0xFFFFE14D, C_EXT = 0xFFFFFFFF;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FrameSource src;
    private final SharedPreferences prefs;
    private final TextRecognizer ocr;

    private LinesView lines;
    private TextView status;
    private volatile boolean busy = false;

    public TrazadorCore(Context ctx, WindowManager wm, Handler bg, FrameSource src) {
        this.ctx = ctx; this.wm = wm; this.bg = bg; this.src = src;
        prefs = ctx.getSharedPreferences("trazador", Context.MODE_PRIVATE);
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    // ---------------- vistas ----------------
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()); }
    private GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r)); return g;
    }
    private TextView btn(String s, int color) {
        TextView t = new TextView(ctx); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setGravity(Gravity.CENTER); t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setBackground(rounded(0xFF232A35, 8)); return t;
    }

    /** Botones y texto de estado, para meterlos dentro de otra ventanita. */
    public View controls() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(ctx);
        TextView zona = btn("Zona", C_RES), trazar = btn("Trazar", C_CHAN), borrar = btn("Borrar", FG);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1); bp.setMargins(0, dp(4), dp(4), 0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1); bl.setMargins(0, dp(4), 0, 0);
        row.addView(zona, bp); row.addView(trazar, bp); row.addView(borrar, bl);
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(ctx); status.setTextColor(MUTED); status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        status.setMaxWidth(dp(190)); status.setPadding(0, dp(4), 0, 0);
        status.setText("1) En M15 o M5 toca Zona.  2) En tu temporalidad toca Trazar.");
        box.addView(status, new LinearLayout.LayoutParams(-1, -2));

        zona.setOnClickListener(v -> run(true));
        trazar.setOnClickListener(v -> run(false));
        borrar.setOnClickListener(v -> { clear(); setStatus("Líneas borradas. Mantén presionado Borrar para olvidar las zonas guardadas."); });
        borrar.setOnLongClickListener(v -> {
            SharedPreferences.Editor e = prefs.edit();
            for (String k : prefs.getAll().keySet()) if (k.startsWith("z:")) e.remove(k);
            e.apply(); clear();
            Toast.makeText(ctx, "Olvidé todas las zonas guardadas", Toast.LENGTH_SHORT).show();
            setStatus("Zonas borradas.");
            return true;
        });
        return box;
    }

    /** Capa transparente de líneas (toda la pantalla, no recibe toques). */
    public void attachLines() {
        if (lines != null) return;
        lines = new LinesView(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { wm.addView(lines, lp); } catch (Exception e) { lines = null; }
    }

    public void detachLines() {
        if (lines != null) { try { wm.removeView(lines); } catch (Exception ignored) {} lines = null; }
    }

    public void clear() { if (lines != null) lines.set(new ArrayList<>()); }

    public void release() {
        detachLines();
        try { ocr.close(); } catch (Exception ignored) {}
    }

    private void setStatus(String s) { if (status != null) status.setText(s); }

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
        boolean pairGuessed;
        Trazado.Region region;
        int w, h;
    }

    private void run(boolean saveZone) {
        if (busy) return;
        busy = true;
        attachLines();
        setStatus("Leyendo el gráfico…");
        final Rect hide = src.hide();
        bg.post(() -> {
            Bitmap bmp = src.frame();
            if (bmp == null) { done("No veo la pantalla todavía. Intenta otra vez."); return; }
            if (hide != null) {   // tapa la propia ventanita para no leerse a sí mismo
                Paint pt = new Paint(); pt.setColor(0xFF000000);
                Bitmap m = bmp.isMutable() ? bmp : bmp.copy(Bitmap.Config.ARGB_8888, true);
                if (m != bmp) bmp.recycle();
                bmp = m;
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
        boolean topFound = false, otcSeen = false;
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
            String su = s.toUpperCase(Locale.US).replace('0', 'O');
            if (r.centerY() < h * 0.35 && su.matches(".*\\bOTC\\b.*")) otcSeen = true;
            Matcher am = ASSET.matcher(s.toUpperCase(Locale.US));
            if (am.find() && r.centerY() < h * 0.35 && rd.asset.isEmpty()) {
                String g = am.group(1).replace(" ", "");
                rd.asset = g.substring(0, 7) + (g.endsWith("OTC") ? " OTC" : "");
                if (!topFound || r.bottom + 12 > top) { top = Math.max(top, r.bottom + 12); topFound = true; }
                continue;
            }
            if (PANEL.matcher(s.trim()).matches() && r.top > h * 0.35) bottom = Math.min(bottom, r.top - 12);
        }
        if (!rd.asset.isEmpty() && otcSeen && !rd.asset.endsWith("OTC")) rd.asset += " OTC";
        int x1 = axisLeft < w ? axisLeft - 6 : (int) (w * 0.82);
        rd.region = new Trazado.Region(0, top, Math.max(10, x1), Math.max(top + 10, bottom));
        rd.map = Trazado.priceMap(labels);
        int[] px = new int[w * h];
        img.getPixels(px, 0, w, 0, 0, w, h);
        List<Market.Geo> c = Trazado.candles(px, w, h, rd.region);
        rd.res = Trazado.analyze(c, rd.region);
        return rd;
    }

    /** Par que se está viendo. Si esta vez no se leyó, usa el último leído hace poco (solo para Trazar). */
    private String pairFor(Read rd, boolean saving) {
        if (!rd.asset.isEmpty()) {
            prefs.edit().putString("lastPair", rd.asset).putLong("lastPairAt", System.currentTimeMillis()).apply();
            return rd.asset;
        }
        if (saving) return null;
        String last = prefs.getString("lastPair", "");
        long at = prefs.getLong("lastPairAt", 0);
        if (!last.isEmpty() && System.currentTimeMillis() - at < 15 * 60_000L) { rd.pairGuessed = true; return last; }
        return null;
    }

    private JSONArray zones(String asset) {
        try { return new JSONArray(prefs.getString("z:" + asset, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static int tfRank(String tf) {
        if (tf == null || tf.length() < 2) return 0;
        int n; try { n = Integer.parseInt(tf.substring(1)); } catch (Exception e) { return 0; }
        switch (tf.charAt(0)) { case 'S': return n; case 'M': return n * 60; case 'H': return n * 3600; case 'D': return n * 86400; }
        return 0;
    }

    private void onRead(Read rd, boolean saveZone) {
        if (rd.res.error != null) { done(rd.res.error); return; }
        List<LinesView.Item> items = new ArrayList<>();
        String asset = pairFor(rd, saveZone);
        String tf = rd.tf.isEmpty() ? "?" : rd.tf;
        if (saveZone) {
            if (asset == null) { done("No leí el par (ej. AUD/CAD OTC). Deja visible el nombre del par arriba y toca Zona otra vez, así no se mezclan las zonas entre divisas."); return; }
            if (rd.map == null) { done("No pude leer los precios del eje derecho. Deja visibles los números del eje."); return; }
            JSONArray z = zones(asset);
            // Tolerancia en precio: lo que mide ~1.5% del alto del gráfico
            double tolP = Math.abs(Trazado.yToPrice(rd.map, rd.region.y0) - Trazado.yToPrice(rd.map, rd.region.y0 + rd.region.h() * 0.015));
            List<JSONObject> all = new ArrayList<>();
            for (int i = 0; i < z.length(); i++) { JSONObject o = z.optJSONObject(i); if (o != null) all.add(o); }
            int added = 0, updated = 0;
            for (Trazado.Level L : rd.res.levels) {
                double p = Trazado.yToPrice(rd.map, L.y);
                JSONObject hit = null;
                for (JSONObject o : all) if (o.optBoolean("r") == L.resistance && Math.abs(o.optDouble("p") - p) <= tolP) { hit = o; break; }
                try {
                    if (hit != null) {
                        // misma zona: se queda con la temporalidad mayor y suma toques
                        if (tfRank(tf) >= tfRank(hit.optString("tf"))) hit.put("tf", tf);
                        hit.put("p", (hit.optDouble("p") + p) / 2);
                        hit.put("t", Math.max(hit.optInt("t"), L.touches));
                        hit.put("s", hit.optBoolean("s") || L.strongest);
                        hit.put("at", System.currentTimeMillis());
                        updated++;
                    } else {
                        JSONObject o = new JSONObject();
                        o.put("p", p); o.put("r", L.resistance); o.put("tf", tf); o.put("t", L.touches);
                        o.put("s", L.strongest); o.put("at", System.currentTimeMillis());
                        all.add(o); added++;
                    }
                } catch (Exception ignored) {}
            }
            // Máximo 4 resistencias y 4 soportes por par: quedan las más fuertes y recientes
            JSONArray nz = new JSONArray();
            for (int side = 0; side < 2; side++) {
                List<JSONObject> l = new ArrayList<>();
                for (JSONObject o : all) if (o.optBoolean("r") == (side == 0)) l.add(o);
                l.sort((x, y) -> {
                    int c = Boolean.compare(y.optBoolean("s"), x.optBoolean("s"));
                    if (c != 0) return c;
                    c = Integer.compare(tfRank(y.optString("tf")), tfRank(x.optString("tf")));
                    if (c != 0) return c;
                    c = Integer.compare(y.optInt("t"), x.optInt("t"));
                    return c != 0 ? c : Long.compare(y.optLong("at"), x.optLong("at"));
                });
                for (int i = 0; i < Math.min(4, l.size()); i++) nz.put(l.get(i));
            }
            prefs.edit().putString("z:" + asset, nz.toString()).apply();
            int nr = 0, ns = 0;
            for (int i = 0; i < nz.length(); i++) if (nz.optJSONObject(i).optBoolean("r")) nr++; else ns++;
            String msg = added + updated == 0 ? asset + " · no encontré una zona clara en " + tf + "."
                    : asset + " · guardé " + (added > 0 ? added + " zona" + (added == 1 ? "" : "s") + " nueva" + (added == 1 ? "" : "s") : "")
                    + (added > 0 && updated > 0 ? " y " : "") + (updated > 0 ? "reforcé " + updated : "") + " de " + tf
                    + ". Tienes " + nr + " resistencia" + (nr == 1 ? "" : "s") + " y " + ns + " soporte" + (ns == 1 ? "" : "s")
                    + " guardados. Cambia a tu temporalidad y toca Trazar.";
            addZones(items, nz, rd, new int[3]);
            post(items, msg);
            return;
        }
        // Trazar: zonas guardadas + canal diagonal + soportes/resistencias de esta temporalidad
        JSONArray z = asset == null ? new JSONArray() : zones(asset);
        int[] cnt = new int[3];   // dibujadas, fuera de vista, tocando
        String near = addZones(items, z, rd, cnt);
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
        StringBuilder msg = new StringBuilder();
        if (near != null) msg.append("⚠ El precio está llegando a ").append(near).append("\n");
        msg.append(asset == null ? "Par sin leer" : asset + (rd.pairGuessed ? " (el último que leí)" : ""))
           .append(" · ").append(tf).append(" · ").append(rd.res.downMove ? "canal de bajada" : "canal de subida");
        if (asset == null) msg.append(" · no muestro zonas para no mezclar divisas: deja visible el nombre del par");
        else if (z.length() == 0) msg.append(" · sin zonas guardadas (toca Zona en M5 o mayor)");
        else if (rd.map == null) msg.append(" · no leí los precios del eje, no puedo ubicar tus zonas");
        else {
            msg.append(" · ").append(cnt[0]).append(" zona").append(cnt[0] == 1 ? "" : "s").append(" en pantalla");
            if (cnt[1] > 0) msg.append(", ").append(cnt[1]).append(" fuera (flechas ▲▼)");
        }
        post(items, msg.toString());
    }

    /** Dibuja las zonas guardadas (en precio) donde caen en el gráfico de ahora; las que quedan fuera, como flecha en el borde. */
    private String addZones(List<LinesView.Item> items, JSONArray z, Read rd, int[] cnt) {
        if (rd.map == null) return null;
        double cur = Trazado.yToPrice(rd.map, rd.res.currentY);
        double tolP = Math.abs(Trazado.yToPrice(rd.map, rd.region.y0) - Trazado.yToPrice(rd.map, rd.region.y0 + rd.region.h() * 0.02));
        double span = Math.abs(Trazado.yToPrice(rd.map, rd.region.y0) - Trazado.yToPrice(rd.map, rd.region.y1));
        String near = null;
        int upSlot = 0, downSlot = 0;
        float dens = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < z.length(); i++) {
            JSONObject o = z.optJSONObject(i); if (o == null) continue;
            double p = o.optDouble("p", Double.NaN); if (Double.isNaN(p)) continue;
            // Protección: una zona a más de 10 pantallas del precio no es de este gráfico
            if (span > 0 && Math.abs(p - cur) > span * 10) continue;
            boolean r = o.optBoolean("r");
            String name = (r ? "RESISTENCIA " : "SOPORTE ") + o.optString("tf") + (o.optBoolean("s") ? " ★" : "");
            if (Math.abs(p - cur) <= tolP && near == null) near = name + " " + fmt(p);
            float y = (float) Trazado.priceToY(rd.map, p);
            if (y >= rd.region.y0 - 4 && y <= rd.region.y1 + 4) {
                items.add(hline(y, rd.w, r ? C_RES : C_SUP, o.optBoolean("s") ? 6 : 4, false, name + "  " + fmt(p)));
                cnt[0]++;
            } else {
                boolean above = y < rd.region.y0;
                float yy = above ? rd.region.y0 + (22 + 22 * upSlot++) * dens : rd.region.y1 - (6 + 22 * downSlot++) * dens;
                LinesView.Item it = new LinesView.Item();
                it.x1 = rd.w - 150 * dens; it.x2 = rd.w; it.y1 = yy; it.y2 = yy;
                it.color = r ? C_RES : C_SUP; it.width = 3; it.dashed = true;
                it.label = (above ? "▲ " : "▼ ") + name + "  " + fmt(p);
                items.add(it);
                cnt[1]++;
            }
        }
        return near;
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
        ui.post(() -> { if (lines != null) lines.set(items); setStatus(msg); busy = false; });
    }
    private void done(String msg) { ui.post(() -> { setStatus(msg); busy = false; }); }

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
