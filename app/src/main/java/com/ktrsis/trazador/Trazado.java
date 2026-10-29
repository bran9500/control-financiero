package com.ktrsis.trazador;

import com.ktrsis.bot.Detector;
import com.ktrsis.bot.Market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lee las velas de una captura del broker y calcula:
 *  - los soportes y resistencias horizontales más fuertes (donde el precio rebotó más veces),
 *  - el canal diagonal del último movimiento grande (del máximo/mínimo más importante hasta ahora).
 * Todo en píxeles de la pantalla. Funciones puras (sin Android): se prueban en la PC.
 */
public final class Trazado {
    private Trazado() {}

    /** Zona donde están las velas (sin la barra de arriba, el panel de operar ni el eje de precios). */
    public static final class Region {
        public int x0, y0, x1, y1;
        public Region(int x0, int y0, int x1, int y1) { this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; }
        public int w() { return x1 - x0; }
        public int h() { return y1 - y0; }
    }

    public static final class Level {
        public double y;        // en pantalla
        public double score;
        public int touches;
        public boolean resistance;
        public boolean strongest;
    }

    public static final class Line {
        public double x1, y1, x2, y2;
        public String kind;   // "upper", "lower", "extreme"
    }

    public static final class Result {
        public int candles;
        public double currentY = Double.NaN;
        public List<Level> levels = new ArrayList<>();
        public List<Line> channel = new ArrayList<>();
        public boolean downMove;
        public String error;
    }

    /** Velas de la captura completa, ya en coordenadas de pantalla. */
    public static List<Market.Geo> candles(int[] full, int w, int h, Region r) {
        int rw = r.w(), rh = r.h();
        List<Market.Geo> out = new ArrayList<>();
        if (rw <= 10 || rh <= 10) return out;
        int[] crop = new int[rw * rh];
        for (int y = 0; y < rh; y++) System.arraycopy(full, (r.y0 + y) * w + r.x0, crop, y * rw, rw);
        Detector.Result d = Detector.detect(crop, rw, rh);
        for (Market.Geo g : d.candles) {
            if (g.totalLen() < 2) continue;
            g.x0 += r.x0; g.x1 += r.x0;
            g.totalTop += r.y0; g.totalBottom += r.y0; g.bodyTop += r.y0; g.bodyBottom += r.y0;
            out.add(g);
        }
        return out;
    }

    public static Result analyze(List<Market.Geo> c, Region r) {
        Result res = new Result();
        int n = c.size();
        res.candles = n;
        if (n < 8) { res.error = "Veo muy pocas velas (" + n + "). Aleja un poco el gráfico."; return res; }
        double[] hi = new double[n], lo = new double[n], xc = new double[n];
        for (int i = 0; i < n; i++) {
            Market.Geo g = c.get(i);
            hi[i] = g.totalTop; lo[i] = g.totalBottom; xc[i] = (g.x0 + g.x1) / 2.0;
        }
        Market.Geo last = c.get(n - 1);
        res.currentY = last.color == 'V' ? last.bodyTop : last.bodyBottom;
        levels(res, hi, lo, n, r);
        channel(res, hi, lo, xc, n, r);
        return res;
    }

    // ---------------- soportes y resistencias ----------------
    static void levels(Result res, double[] hi, double[] lo, int n, Region r) {
        int k = Math.max(2, Math.round(n / 30f));
        double tol = Math.max(3, r.h() * 0.012);
        List<double[]> pts = new ArrayList<>(); // {y, idx, type 0=alto 1=bajo}
        int gHi = 0, gLo = 0;
        for (int i = 0; i < n; i++) { if (hi[i] < hi[gHi]) gHi = i; if (lo[i] > lo[gLo]) gLo = i; }
        for (int i = 0; i < n; i++) {
            boolean ph = true, pl = true;
            for (int j = Math.max(0, i - k); j <= Math.min(n - 1, i + k); j++) {
                if (j == i) continue;
                if (hi[j] < hi[i]) ph = false;
                if (lo[j] > lo[i]) pl = false;
            }
            // la última vela todavía se está formando: no cuenta como pivote, salvo los extremos
            if (i >= n - 1) { ph = ph && i == gHi; pl = pl && i == gLo; }
            if (ph) pts.add(new double[]{hi[i], i, 0});
            if (pl) pts.add(new double[]{lo[i], i, 1});
        }
        pts.sort((a, b) -> Double.compare(a[0], b[0]));
        List<List<double[]>> clusters = new ArrayList<>();
        for (double[] p : pts) {
            List<double[]> cur = clusters.isEmpty() ? null : clusters.get(clusters.size() - 1);
            if (cur != null && Math.abs(p[0] - mean(cur)) <= tol) cur.add(p);
            else { List<double[]> nc = new ArrayList<>(); nc.add(p); clusters.add(nc); }
        }
        double cy = res.currentY;
        Level bestR = null, bestS = null;
        List<Level> all = new ArrayList<>();
        for (List<double[]> cl : clusters) {
            double y = mean(cl);
            int hs = 0, ls = 0; double rec = 0;
            boolean extreme = false;
            for (double[] p : cl) {
                if (p[2] == 0) hs++; else ls++;
                rec += 0.5 + 0.5 * p[1] / (n - 1);
                if ((p[2] == 0 && (int) p[1] == gHi) || (p[2] == 1 && (int) p[1] == gLo)) extreme = true;
            }
            // Cuántas velas cruzaron el nivel con el cuerpo (un nivel que el precio atraviesa mucho es débil)
            int touches = cl.size();
            if (touches < 2 && !extreme) continue;
            double score = rec + (hs > 0 && ls > 0 ? 1.0 : 0) + (extreme ? 1.0 : 0);
            Level L = new Level();
            L.y = y; L.touches = touches; L.score = score;
            if (y < cy - tol * 0.5) L.resistance = true;
            else if (y > cy + tol * 0.5) L.resistance = false;
            else continue; // el precio está justo encima del nivel: no se sabe si es soporte o resistencia
            all.add(L);
            if (L.resistance && (bestR == null || score > bestR.score)) bestR = L;
            if (!L.resistance && (bestS == null || score > bestS.score)) bestS = L;
        }
        if (bestR != null) bestR.strongest = true;
        if (bestS != null) bestS.strongest = true;
        all.sort((a, b) -> Double.compare(b.score, a.score));
        // las 2 más fuertes de cada lado
        int nr = 0, ns = 0;
        for (Level L : all) {
            if (L.resistance && nr < 2) { res.levels.add(L); nr++; }
            else if (!L.resistance && ns < 2) { res.levels.add(L); ns++; }
        }
    }

    static double mean(List<double[]> l) { double s = 0; for (double[] p : l) s += p[0]; return s / l.size(); }

    // ---------------- canal del último movimiento ----------------
    static void channel(Result res, double[] hi, double[] lo, double[] xc, int n, Region r) {
        int m = Math.min(n, Math.max(20, (int) Math.round(n * 0.7)));
        int s0 = n - m;
        int a = s0, b = s0; // a = máximo (y menor), b = mínimo (y mayor)
        for (int i = s0; i < n; i++) { if (hi[i] < hi[a]) a = i; if (lo[i] > lo[b]) b = i; }
        boolean down = a < b;
        res.downMove = down;
        // Doble techo / doble piso: la línea sale del último toque del extremo, que es donde arranca la caída/subida.
        double tol = Math.max(Math.max(3, r.h() * 0.012), Math.abs(lo[b] - hi[a]) * 0.15);
        if (down) { for (int i = b - 1; i > a; i--) if (hi[i] <= hi[a] + tol && hi[i] <= hi[i - 1] && hi[i] <= hi[i + 1]) { a = i; break; } }
        else { for (int i = a - 1; i > b; i--) if (lo[i] >= lo[b] - tol && lo[i] >= lo[i - 1] && lo[i] >= lo[i + 1]) { b = i; break; } }
        double xEnd = r.x1 + (r.x1 - r.x0) * 0.04;
        if (down) {
            Line up = edgeFrom(hi, xc, a, n, true, false);
            Line low = otherSide(lo, xc, a, b, n, false, up);
            if (up != null) { up.kind = "upper"; extend(up, xEnd); res.channel.add(up); }
            if (low != null) { low.kind = "lower"; extend(low, xEnd); res.channel.add(low); }
            Line ex = new Line(); ex.kind = "extreme"; ex.x1 = xc[b]; ex.y1 = lo[b]; ex.x2 = xEnd; ex.y2 = lo[b];
            res.channel.add(ex);
        } else {
            Line low = edgeFrom(lo, xc, b, n, false, false);
            Line up = otherSide(hi, xc, b, a, n, true, low);
            if (low != null) { low.kind = "lower"; extend(low, xEnd); res.channel.add(low); }
            if (up != null) { up.kind = "upper"; extend(up, xEnd); res.channel.add(up); }
            Line ex = new Line(); ex.kind = "extreme"; ex.x1 = xc[a]; ex.y1 = hi[a]; ex.x2 = xEnd; ex.y2 = hi[a];
            res.channel.add(ex);
        }
    }

    /**
     * Línea que sale del punto de inicio del movimiento y toca el extremo de las velas que siguen
     * sin que ninguna lo atraviese (techo en bajada: por encima de todos los máximos; piso en subida:
     * por debajo de todos los mínimos).
     */
    static Line edgeFrom(double[] v, double[] xc, int start, int n, boolean above, boolean unused) {
        double best = Double.NaN; int touch = -1;
        for (int k = start + 2; k < n; k++) {
            double dx = xc[k] - xc[start];
            if (dx <= 0) continue;
            double s = (v[k] - v[start]) / dx;
            if (above) { if (Double.isNaN(best) || s < best) { best = s; touch = k; } }   // y crece hacia abajo
            else { if (Double.isNaN(best) || s > best) { best = s; touch = k; } }
        }
        if (touch < 0) return null;
        Line L = new Line();
        L.x1 = xc[start]; L.y1 = v[start]; L.x2 = xc[touch]; L.y2 = v[touch];
        return L;
    }

    /**
     * El otro borde: pasa por el extremo final del movimiento (el mínimo en bajada) y por otro pivote
     * del mismo lado, sin que ninguna vela lo atraviese. Si no hay uno bueno, va paralelo al primero.
     */
    static Line otherSide(double[] v, double[] xc, int from, int end, int n, boolean above, Line ref) {
        Line best = null; double bestSpan = 0;
        for (int p = from; p < n; p++) {
            if (Math.abs(p - end) < 3) continue;
            double dx = xc[p] - xc[end];
            if (dx == 0) continue;
            double s = (v[p] - v[end]) / dx;
            boolean ok = true;
            for (int k = from; k < n && ok; k++) {
                double ly = v[end] + s * (xc[k] - xc[end]);
                ok = above ? v[k] >= ly - 1.5 : v[k] <= ly + 1.5;
            }
            if (!ok) continue;
            double span = Math.abs(xc[p] - xc[end]);
            if (span > bestSpan) {
                bestSpan = span;
                best = new Line();
                if (xc[p] < xc[end]) { best.x1 = xc[p]; best.y1 = v[p]; best.x2 = xc[end]; best.y2 = v[end]; }
                else { best.x1 = xc[end]; best.y1 = v[end]; best.x2 = xc[p]; best.y2 = v[p]; }
            }
        }
        if (best == null && ref != null) {
            double s = (ref.y2 - ref.y1) / (ref.x2 - ref.x1);
            best = new Line();
            best.x1 = xc[from]; best.y1 = v[end] + s * (xc[from] - xc[end]);
            best.x2 = xc[end]; best.y2 = v[end];
        }
        return best;
    }

    static void extend(Line L, double xEnd) {
        if (L.x2 == L.x1) return;
        double s = (L.y2 - L.y1) / (L.x2 - L.x1);
        L.y2 = L.y1 + s * (xEnd - L.x1);
        L.x2 = xEnd;
    }

    // ---------------- precios del eje ----------------
    /** Recta y = a*precio + b a partir de las etiquetas del eje {y, precio}. Mediana de pendientes: aguanta una etiqueta mal leída. */
    public static double[] priceMap(List<double[]> labels) {
        if (labels.size() < 2) return null;
        List<Double> slopes = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++)
            for (int j = i + 1; j < labels.size(); j++) {
                double dp = labels.get(j)[1] - labels.get(i)[1];
                if (Math.abs(dp) < 1e-12) continue;
                slopes.add((labels.get(j)[0] - labels.get(i)[0]) / dp);
            }
        if (slopes.isEmpty()) return null;
        Collections.sort(slopes);
        double a = slopes.get(slopes.size() / 2);
        if (!(a < 0)) return null; // más precio = más arriba en la pantalla
        List<Double> bs = new ArrayList<>();
        for (double[] l : labels) bs.add(l[0] - a * l[1]);
        Collections.sort(bs);
        double b = bs.get(bs.size() / 2);
        return new double[]{a, b};
    }

    public static double yToPrice(double[] map, double y) { return (y - map[1]) / map[0]; }
    public static double priceToY(double[] map, double p) { return map[0] * p + map[1]; }
}
