package com.ktrsis.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * Patrones clásicos de velas y estructura de mercado (swings, zonas de
 * liquidez, Order Blocks con ruptura de estructura), portados 1:1 desde
 * catalogador_engine.py. Todo en píxeles: y más chico = precio más alto.
 */
public final class Market {
    private Market() {}

    /** Forma de una vela en la imagen. */
    public static final class Geo {
        public char color;
        public int x0, x1; // columnas [x0, x1)
        public int totalTop, totalBottom, bodyTop, bodyBottom;

        public int bodyLen() { return bodyBottom - bodyTop; }
        public int upperWick() { return bodyTop - totalTop; }
        public int lowerWick() { return totalBottom - bodyBottom; }
        public int totalLen() { return totalBottom - totalTop; }
    }

    public static final class Pattern {
        public final String name;
        public final int candles;
        Pattern(String n, int c) { name = n; candles = c; }
    }

    // ---------------- Patrones ----------------

    static boolean smallBody(Geo g, double ratio) {
        if (g.totalLen() == 0) return false;
        return g.bodyLen() <= g.totalLen() * ratio;
    }

    static String doji(Geo g) {
        if (g.totalLen() == 0) return null;
        // 0.15 en vez de 0.08: en capturas reales el antialiasing infla el cuerpo un par de píxeles
        return g.bodyLen() <= Math.max(1, g.totalLen() * 0.15) ? "DOJI" : null;
    }

    static String hammerFamily(Geo g, char trend) {
        if (!smallBody(g, 0.35)) return null;
        int b = Math.max(g.bodyLen(), 1);
        boolean longLower = g.lowerWick() >= 2 * b;
        boolean longUpper = g.upperWick() >= 2 * b;
        boolean tinyUpper = g.upperWick() <= g.bodyLen() + 1;
        boolean tinyLower = g.lowerWick() <= g.bodyLen() + 1;
        if (longLower && tinyUpper) {
            if (trend == 'V') return "HOMBRE_COLGADO";
            return "MARTILLO";
        }
        if (longUpper && tinyLower) {
            if (trend == 'R') return "MARTILLO_INVERTIDO";
            return "ESTRELLA_FUGAZ";
        }
        return null;
    }

    static String engulfing(Geo p, Geo c) {
        if (p.color == c.color || c.bodyLen() == 0 || p.bodyLen() == 0) return null;
        if (!(c.bodyTop <= p.bodyTop && c.bodyBottom >= p.bodyBottom)) return null;
        return c.color == 'V' ? "ENVOLVENTE_ALCISTA" : "ENVOLVENTE_BAJISTA";
    }

    static String harami(Geo p, Geo c) {
        if (p.color == c.color || c.bodyLen() == 0 || p.bodyLen() == 0) return null;
        if (!(c.bodyTop >= p.bodyTop && c.bodyBottom <= p.bodyBottom)) return null;
        return c.color == 'V' ? "HARAMI_ALCISTA" : "HARAMI_BAJISTA";
    }

    static String tweezer(Geo p, Geo c) {
        double tol = Math.max(Math.max(p.totalLen(), c.totalLen()), 1) * 0.1;
        boolean sameTop = Math.abs(p.totalTop - c.totalTop) <= tol;
        boolean sameBottom = Math.abs(p.totalBottom - c.totalBottom) <= tol;
        if (sameTop && p.color != c.color) return "PINZAS_TECHO";
        if (sameBottom && p.color != c.color) return "PINZAS_SUELO";
        return null;
    }

    /** {open_y, close_y} */
    static int[] openClose(Geo g) {
        return g.color == 'V' ? new int[]{g.bodyBottom, g.bodyTop} : new int[]{g.bodyTop, g.bodyBottom};
    }

    static String soldiersCrows(Geo g1, Geo g2, Geo g3) {
        if (g1.color != g2.color || g2.color != g3.color) return null;
        for (Geo g : new Geo[]{g1, g2, g3}) if (smallBody(g, 0.25)) return null;
        int[] a = openClose(g1), b = openClose(g2), c = openClose(g3);
        boolean progressing = g1.color == 'V' ? (b[1] < a[1] && c[1] < b[1]) : (b[1] > a[1] && c[1] > b[1]);
        if (!progressing) return null;
        if (!(opensWithin(b[0], g1) && opensWithin(c[0], g2))) return null;
        return g1.color == 'V' ? "TRES_SOLDADOS_BLANCOS" : "TRES_CUERVOS_NEGROS";
    }

    private static boolean opensWithin(int openY, Geo prev) {
        double m = prev.bodyLen() * 0.5;
        return prev.bodyTop - m <= openY && openY <= prev.bodyBottom + m;
    }

    static String star(Geo g1, Geo g2, Geo g3) {
        if (g1.color == g3.color) return null;
        if (smallBody(g1, 0.3) || smallBody(g3, 0.3)) return null;
        if (!smallBody(g2, 0.4)) return null;
        int c3 = openClose(g3)[1];
        double mid = (g1.bodyTop + g1.bodyBottom) / 2.0;
        if (g1.color == 'R' && g3.color == 'V' && c3 < mid) return "ESTRELLA_MANANA";
        if (g1.color == 'V' && g3.color == 'R' && c3 > mid) return "ESTRELLA_NOCHE";
        return null;
    }

    /** geos: velas YA CERRADAS, la última es la más reciente. trend: 'R','V' o 0. */
    public static List<Pattern> patterns(List<Geo> geos, char trend) {
        List<Pattern> out = new ArrayList<>();
        int n = geos.size();
        if (n == 0) return out;
        Geo last = geos.get(n - 1);
        String s;
        if ((s = doji(last)) != null) out.add(new Pattern(s, 1));
        if ((s = hammerFamily(last, trend)) != null) out.add(new Pattern(s, 1));
        if (n >= 2) {
            Geo p = geos.get(n - 2);
            if ((s = engulfing(p, last)) != null) out.add(new Pattern(s, 2));
            if ((s = harami(p, last)) != null) out.add(new Pattern(s, 2));
            if ((s = tweezer(p, last)) != null) out.add(new Pattern(s, 2));
        }
        if (n >= 3) {
            Geo g1 = geos.get(n - 3), g2 = geos.get(n - 2);
            if ((s = soldiersCrows(g1, g2, last)) != null) out.add(new Pattern(s, 3));
            if ((s = star(g1, g2, last)) != null) out.add(new Pattern(s, 3));
        }
        return out;
    }

    /** Dirección que sugiere un patrón: 'V' compra, 'R' venta, 0 neutral. */
    public static char patternTone(String p) {
        switch (p) {
            case "MARTILLO": case "MARTILLO_INVERTIDO": case "ENVOLVENTE_ALCISTA": case "HARAMI_ALCISTA":
            case "PINZAS_SUELO": case "TRES_SOLDADOS_BLANCOS": case "ESTRELLA_MANANA":
                return 'V';
            case "HOMBRE_COLGADO": case "ESTRELLA_FUGAZ": case "ENVOLVENTE_BAJISTA": case "HARAMI_BAJISTA":
            case "PINZAS_TECHO": case "TRES_CUERVOS_NEGROS": case "ESTRELLA_NOCHE":
                return 'R';
            default:
                return 0;
        }
    }

    // ---------------- Estructura de mercado ----------------

    public static final class Swing {
        public final int index; public final boolean high; public final int level;
        public Swing(int i, boolean h, int l) { index = i; high = h; level = l; }
    }

    public static final class Zone {
        public final boolean high; public final double level; public final int touches;
        Zone(boolean h, double l, int t) { high = h; level = l; touches = t; }
    }

    public static final class Block {
        public final boolean bullish; public final int index, top, bottom;
        Block(boolean b, int i, int t, int bo) { bullish = b; index = i; top = t; bottom = bo; }
    }

    public static final class Bias {
        public char dir; // 'V', 'R' o 0
        public List<Zone> zones = new ArrayList<>();
        public List<Block> blocks = new ArrayList<>();
        public List<Swing> swings = new ArrayList<>();
        public Zone nearZone;
    }

    static List<Swing> swings(List<Geo> g, int lookback) {
        List<Swing> out = new ArrayList<>();
        int n = g.size();
        for (int i = lookback; i < n - lookback; i++) {
            Geo c = g.get(i);
            boolean isHigh = true, isLow = true;
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j == i) continue;
                Geo o = g.get(j);
                if (!(c.totalTop < o.totalTop)) isHigh = false;
                if (!(c.totalBottom > o.totalBottom)) isLow = false;
            }
            if (isHigh) out.add(new Swing(i, true, c.totalTop));
            if (isLow) out.add(new Swing(i, false, c.totalBottom));
        }
        return out;
    }

    static List<Zone> zones(List<Swing> sw, double tolRatio, double refLen, int minTouches) {
        double tol = refLen * tolRatio;
        List<Zone> out = new ArrayList<>();
        for (boolean kindHigh : new boolean[]{true, false}) {
            List<Swing> pts = new ArrayList<>();
            for (Swing s : sw) if (s.high == kindHigh) pts.add(s);
            pts.sort((a, b) -> Integer.compare(a.level, b.level)); // estable, como sorted() de Python
            boolean[] used = new boolean[pts.size()];
            for (int i = 0; i < pts.size(); i++) {
                if (used[i]) continue;
                Swing p = pts.get(i);
                used[i] = true;
                int count = 1; double sum = p.level;
                for (int j = i + 1; j < pts.size(); j++) {
                    if (used[j]) continue;
                    if (Math.abs(pts.get(j).level - p.level) <= tol) { used[j] = true; count++; sum += pts.get(j).level; }
                }
                if (count >= minTouches) out.add(new Zone(kindHigh, sum / count, count));
            }
        }
        return out;
    }

    static List<Block> orderBlocks(List<Geo> g, List<Swing> sw, double impulseRatio, int lookbackAvg, int structureWindow) {
        List<Block> out = new ArrayList<>();
        int n = g.size();
        if (n < lookbackAvg + 2) return out;
        for (int i = lookbackAvg; i < n; i++) {
            Geo imp = g.get(i), org = g.get(i - 1);
            double sum = 0; int cnt = 0;
            for (int j = Math.max(0, i - lookbackAvg); j < i; j++) { sum += g.get(j).bodyLen(); cnt++; }
            double avg = cnt > 0 ? sum / cnt : 0;
            if (avg <= 0) continue;
            if (!(imp.bodyLen() >= impulseRatio * avg)) continue;
            int ws = Math.max(0, i - structureWindow);
            int close = openClose(imp)[1];
            if (imp.color == 'V' && org.color == 'R') {
                boolean br = false;
                for (Swing s : sw) if (s.high && s.index >= ws && s.index < i && close < s.level) { br = true; break; }
                if (br) out.add(new Block(true, i - 1, org.totalTop, org.totalBottom));
            }
            if (imp.color == 'R' && org.color == 'V') {
                boolean br = false;
                for (Swing s : sw) if (!s.high && s.index >= ws && s.index < i && close > s.level) { br = true; break; }
                if (br) out.add(new Block(false, i - 1, org.totalTop, org.totalBottom));
            }
        }
        return out;
    }

    /** Equivalente a compute_market_bias(). Devuelve null con menos de 10 velas. */
    public static Bias bias(List<Geo> g) {
        int n = g.size();
        if (n < 10) return null;
        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        for (Geo x : g) { top = Math.min(top, x.totalTop); bottom = Math.max(bottom, x.totalBottom); }
        double refLen = Math.max(1, bottom - top);
        double tolRatio = 0.15;

        Bias b = new Bias();
        b.swings = swings(g, 2);
        b.zones = zones(b.swings, tolRatio, refLen, 2);
        List<Block> blocks = orderBlocks(g, b.swings, 1.8, 10, 60);
        int recentStart = Math.max(0, n - 40);
        int vv = 0, vr = 0;
        for (Block k : blocks) if (k.index >= recentStart) { b.blocks.add(k); if (k.bullish) vv++; else vr++; }

        Geo last = g.get(n - 1);
        for (Zone z : b.zones) {
            if (Math.abs(z.level - last.totalTop) <= refLen * tolRatio || Math.abs(z.level - last.totalBottom) <= refLen * tolRatio) {
                b.nearZone = z;
                break;
            }
        }
        if (vv == vr && b.nearZone == null) { b.dir = 0; return b; }
        if (vv > vr) b.dir = 'V';
        else if (vr > vv) b.dir = 'R';
        else b.dir = b.nearZone.high ? 'R' : 'V';
        return b;
    }

    /** Tendencia previa para distinguir Martillo de Hombre Colgado: mayoría de las últimas 10. */
    public static char trend(char[] seq) {
        int r = 0, v = 0;
        for (int i = Math.max(0, seq.length - 10); i < seq.length; i++) { if (seq[i] == 'R') r++; else v++; }
        return r > v ? 'R' : (v > r ? 'V' : 0);
    }
}
