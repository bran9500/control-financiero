package com.ktrsis.bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Las estrategias de "Quema Cuenta Supremo" y "Quema Cuentas vol. 2" (Miguel Miranda),
 * traducidas a reglas exactas sobre la forma de las velas leídas de la pantalla.
 *
 * Índices: closed[0..n-1] son velas cerradas (la última = n-1). La vela en formación
 * es la "n". offset 0 = la entrada es la vela en formación (ya empezó);
 * offset 1 = la entrada es la vela siguiente (todavía no empieza).
 *
 * Java puro: se prueba en la PC.
 */
public final class Rules {
    private Rules() {}

    /** Dirección conocida, contraria al color con que nace, igual al color con que nace, o condicionada al cierre de la vela actual. */
    public static final int FIXED = 0, BIRTH_REVERSE = 1, BIRTH_SAME = 2, IF_CLOSE = 3;

    public static final class Alert {
        public String key, name, why;
        public int kind;
        public char dir;        // 'V' compra, 'R' venta (FIXED / IF_CLOSE)
        public char ifColor;    // IF_CLOSE: color con el que tiene que cerrar la vela actual
        public int offset;      // 0 = vela en formación, 1 = la siguiente
        public int firstTouch;  // índice (en closed) de la vela que arma la estrategia, para marcarla
        public int lastTouch;
        Alert(String key, String name, int kind, char dir, int offset, int first, int last, String why) {
            this.key = key; this.name = name; this.kind = kind; this.dir = dir; this.offset = offset;
            this.firstTouch = first; this.lastTouch = last; this.why = why;
        }
    }

    public static final String[][] STRATEGIES = {
            {"martillo_mecha", "Martillo con mecha", "1er toque: martillo con mecha. 3er toque: al revés del color con que nace."},
            {"cruz", "Vela en cruz", "1er toque: cruz (mechas iguales). 3er toque: mismo color con que nace."},
            {"martillo_verde", "Martillo normal verde", "1er toque: martillo verde sin mecha de un lado. 3er toque: venta."},
            {"martillo_rojo", "Martillo normal rojo", "1er toque: martillo rojo sin mecha de un lado. 3er toque: compra."},
            {"hermanos_martillo", "Dos hermanos martillo", "Dos martillos seguidos del mismo color. 3er toque a favor de su color."},
            {"rompe_pan", "Rompe Pan", "Martillo con mecha y al lado martillo normal. 3er toque: contra la tendencia."},
            {"hermanos_color", "Hermanos de distinto color", "Dos velas parecidas de distinto color. La 3ra del color de la 1ra."},
            {"volumen", "Volumen", "Vela grande y luego una chica de otro color. La 3ra del color de la chica."},
            {"tres_soldados", "Tres soldados", "3 velas del mismo color con fuerza. La 4ta de color contrario."},
            {"tres_soldados_tarde", "Tres soldados (1 vela tarde)", "Igual que Tres soldados, pero entra en la 5ta vela (prueba: suele acertar en el MG1)."},
            {"raton", "Ratón Trader", "3 velas del mismo color perdiendo fuerza. La 4ta de color contrario."},
            {"carambola", "Carambola", "Mercado lateral, 3 velas alternadas parecidas. La 4ta al revés de la 3ra."},
            {"power_ranger", "Power Ranger", "4 velas del mismo color con martillos normales e invertidos. La 5ta contraria."},
            {"gap", "Salto de GAP", "Salto entre una vela y la siguiente. 4to toque: contra el salto."},
            {"tercer_toque", "Tercer toque S/R", "Nivel tocado 2 veces antes; al 3er toque, rebote."},
    };

    // ---------------- forma de una vela ----------------

    static int rng(Market.Geo g) { return Math.max(1, g.totalLen()); }
    static boolean noWick(int w, Market.Geo g) { return w <= Math.max(1, Math.round(rng(g) * 0.06)); }

    /** Martillo normal: un lado sin mecha y el otro con mecha considerable. 'L' mecha abajo, 'U' mecha arriba, 0 no es. */
    public static char hammerNormal(Market.Geo g) {
        int b = g.bodyLen(), up = g.upperWick(), lo = g.lowerWick(), r = rng(g);
        if (r < 6 || b < 1) return 0;
        if (noWick(up, g) && lo >= Math.max(b * 0.8, r * 0.3)) return 'L';
        if (noWick(lo, g) && up >= Math.max(b * 0.8, r * 0.3)) return 'U';
        return 0;
    }

    /** Martillo con mecha: mecha chica de un lado y grande del otro (las dos existen). */
    public static char hammerWick(Market.Geo g) {
        int b = g.bodyLen(), up = g.upperWick(), lo = g.lowerWick(), r = rng(g);
        if (r < 6) return 0;
        if (noWick(up, g) || noWick(lo, g)) return 0;
        if (b > r * 0.5) return 0;
        int big = Math.max(up, lo), small = Math.min(up, lo);
        if (big < r * 0.4 || big < small * 3 || big < b) return 0;
        return lo > up ? 'L' : 'U';
    }

    /** Cruz: cuerpo chico, mecha arriba y abajo casi iguales. */
    public static boolean cross(Market.Geo g) {
        int b = g.bodyLen(), up = g.upperWick(), lo = g.lowerWick(), r = rng(g);
        if (r < 6) return false;
        if (b > r * 0.25) return false;
        if (up < r * 0.2 || lo < r * 0.2) return false;
        return Math.min(up, lo) >= Math.max(up, lo) * 0.6;
    }

    static boolean anyHammer(Market.Geo g) { return hammerNormal(g) != 0 || hammerWick(g) != 0; }

    static char opp(char c) { return c == 'R' ? 'V' : 'R'; }

    /** {open_y, close_y}: y más chico = precio más alto. */
    static int[] oc(Market.Geo g) { return Market.openClose(g); }

    static double median(double[] v) {
        if (v.length == 0) return 0;
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    static double medianBody(List<Market.Geo> c, int from, int to) {
        from = Math.max(0, from);
        double[] v = new double[Math.max(0, to - from)];
        for (int i = from; i < to; i++) v[i - from] = c.get(i).bodyLen();
        return median(v);
    }

    static double medianRange(List<Market.Geo> c, int from, int to) {
        from = Math.max(0, from);
        double[] v = new double[Math.max(0, to - from)];
        for (int i = from; i < to; i++) v[i - from] = rng(c.get(i));
        return median(v);
    }

    /** Tendencia de las velas [to-len, to): mayoría de colores. */
    static char trend(List<Market.Geo> c, int to, int len) {
        int r = 0, v = 0;
        for (int i = Math.max(0, to - len); i < to; i++) { if (c.get(i).color == 'R') r++; else v++; }
        return r > v ? 'R' : v > r ? 'V' : 0;
    }

    /** Mercado lateral: en las últimas `len` velas el precio casi no se desplazó comparado con su rango. */
    public static boolean lateral(List<Market.Geo> c, int len) {
        int n = c.size();
        if (n < len) return false;
        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        for (int i = n - len; i < n; i++) { top = Math.min(top, c.get(i).totalTop); bottom = Math.max(bottom, c.get(i).totalBottom); }
        int range = Math.max(1, bottom - top);
        int net = Math.abs(oc(c.get(n - 1))[1] - oc(c.get(n - len))[0]);
        return net <= range * 0.35;
    }

    // ---------------- reglas ----------------

    public static List<Alert> evaluate(List<Market.Geo> c) {
        List<Alert> out = new ArrayList<>();
        int n = c.size();
        if (n < 3) return out;
        double mb = Math.max(1, medianBody(c, n - 30, n));

        // --- 4 mandamientos + combinaciones de 2 velas: el 3er toque es i+2.
        //     i = n-1 -> 3er toque = vela siguiente (offset 1); i = n-2 -> 3er toque = vela en formación (offset 0)
        for (int i = n - 2; i <= n - 1; i++) {
            int off = i == n - 1 ? 1 : 0;
            Market.Geo g = c.get(i);
            char hn = hammerNormal(g), hw = hammerWick(g);
            if (hn != 0) {
                if (g.color == 'V') out.add(new Alert("martillo_verde", "Martillo normal verde", FIXED, 'R', off, i, i,
                        "Vela #" + (i + 1) + " es martillo verde (mecha " + (hn == 'L' ? "abajo" : "arriba") + "): 3er toque a la baja."));
                else out.add(new Alert("martillo_rojo", "Martillo normal rojo", FIXED, 'V', off, i, i,
                        "Vela #" + (i + 1) + " es martillo rojo (mecha " + (hn == 'L' ? "abajo" : "arriba") + "): 3er toque al alza."));
            } else if (hw != 0) {
                out.add(new Alert("martillo_mecha", "Martillo con mecha", BIRTH_REVERSE, (char) 0, off, i, i,
                        "Vela #" + (i + 1) + " es martillo con mecha: en el 3er toque entra al revés del color con que nace."));
            } else if (cross(g)) {
                out.add(new Alert("cruz", "Vela en cruz", BIRTH_SAME, (char) 0, off, i, i,
                        "Vela #" + (i + 1) + " es cruz: en el 3er toque entra del mismo color con que nace."));
            }
        }
        for (int i = n - 3; i <= n - 2; i++) {
            if (i < 0) continue;
            int off = i == n - 2 ? 1 : 0; // 3er toque = i+2
            Market.Geo a = c.get(i), b = c.get(i + 1);
            if (anyHammer(a) && anyHammer(b) && a.color == b.color) {
                out.add(new Alert("hermanos_martillo", "Dos hermanos martillo", FIXED, a.color, off, i, i + 1,
                        "Velas #" + (i + 1) + " y #" + (i + 2) + ": dos martillos " + (a.color == 'R' ? "rojos" : "verdes") + " seguidos."));
            }
            if (hammerWick(a) != 0 && hammerNormal(b) != 0) {
                char t = trend(c, i, 8);
                char d = t == 0 ? 'R' : opp(t);
                out.add(new Alert("rompe_pan", "Rompe Pan", FIXED, d, off, i, i + 1,
                        "Martillo con mecha (#" + (i + 1) + ") y al lado martillo normal (#" + (i + 2) + "), contra la tendencia."));
            }
            double ba = a.bodyLen(), bb = b.bodyLen();
            if (a.color != b.color && ba >= mb * 0.4 && bb >= mb * 0.4 && Math.min(ba, bb) >= Math.max(ba, bb) * 0.7) {
                out.add(new Alert("hermanos_color", "Hermanos de distinto color", FIXED, a.color, off, i, i + 1,
                        "Velas #" + (i + 1) + " y #" + (i + 2) + " parecidas de distinto color: la 3ra del color de la 1ra."));
            }
            if (a.color != b.color && ba >= mb * 1.3 && bb <= ba * 0.5) {
                out.add(new Alert("volumen", "Volumen", FIXED, b.color, off, i, i + 1,
                        "Vela grande (#" + (i + 1) + ") y luego una chica de otro color (#" + (i + 2) + "): la 3ra del color de la chica."));
            }
        }

        // --- Tres soldados / Ratón: 3 velas del mismo color -> la 4ta contraria (vela en formación)
        if (n >= 3) {
            Market.Geo g1 = c.get(n - 3), g2 = c.get(n - 2), g3 = c.get(n - 1);
            boolean before = n < 4 || c.get(n - 4).color != g1.color;
            if (before && g1.color == g2.color && g2.color == g3.color) {
                boolean losing = g3.bodyLen() < g1.bodyLen() * 0.7 && g2.bodyLen() <= g1.bodyLen();
                if (losing) out.add(new Alert("raton", "Ratón Trader", FIXED, opp(g1.color), 0, n - 3, n - 1,
                        "3 velas " + (g1.color == 'R' ? "rojas" : "verdes") + " perdiendo fuerza: la 4ta al revés."));
                else {
                    out.add(new Alert("tres_soldados", "Tres soldados", FIXED, opp(g1.color), 0, n - 3, n - 1,
                            "3 velas " + (g1.color == 'R' ? "rojas" : "verdes") + " seguidas: la 4ta al revés."));
                    out.add(new Alert("tres_soldados_tarde", "Tres soldados (1 vela tarde)", FIXED, opp(g1.color), 1, n - 3, n - 1,
                            "3 velas " + (g1.color == 'R' ? "rojas" : "verdes") + " seguidas: entra en la 5ta al revés."));
                }
            }
            // aviso previo: van 2 del mismo color
            boolean before2 = n < 3 || c.get(n - 3).color != g2.color;
            if (before2 && g2.color == g3.color) {
                Alert a = new Alert("tres_soldados", "Tres soldados / Ratón", IF_CLOSE, opp(g3.color), 1, n - 2, n - 1,
                        "Van 2 " + (g3.color == 'R' ? "rojas" : "verdes") + ". Si la vela actual cierra igual, la siguiente al revés.");
                a.ifColor = g3.color;
                out.add(a);
            }
        }

        // --- Carambola (solo lateral): 3 alternadas de cuerpo parecido -> la 4ta al revés de la 3ra
        if (n >= 12 && lateral(c, 12)) {
            Market.Geo g1 = c.get(n - 3), g2 = c.get(n - 2), g3 = c.get(n - 1);
            if (g1.color != g2.color && g2.color != g3.color && similar(g1, g2, g3)) {
                out.add(new Alert("carambola", "Carambola", FIXED, opp(g3.color), 0, n - 3, n - 1,
                        "Mercado lateral y 3 velas alternadas parecidas: la 4ta al revés de la 3ra (y la 5ta al revés de la 4ta)."));
            }
            if (g2.color != g3.color && similar(g2, g3, g3)) {
                Alert a = new Alert("carambola", "Carambola", IF_CLOSE, g3.color, 1, n - 2, n - 1,
                        "Lateral y van 2 alternadas. Si la vela actual cierra " + (g2.color == 'R' ? "roja" : "verde") + ", la siguiente al revés.");
                a.ifColor = g2.color;
                out.add(a);
            }
        }

        // --- Power Ranger: 4 velas del mismo color con martillos normales e invertidos -> la 5ta contraria
        if (n >= 4) {
            boolean same = true, upper = false, lower = false;
            int hams = 0;
            char col = c.get(n - 1).color;
            for (int i = n - 4; i < n; i++) {
                Market.Geo g = c.get(i);
                if (g.color != col) { same = false; break; }
                char s = hammerNormal(g) != 0 ? hammerNormal(g) : hammerWick(g);
                if (s != 0 || cross(g)) hams++;
                if (s == 'U') upper = true;
                if (s == 'L') lower = true;
            }
            if (same && hams >= 4 && upper && lower) {
                out.add(new Alert("power_ranger", "Power Ranger", FIXED, opp(col), 0, n - 4, n - 1,
                        "4 velas " + (col == 'R' ? "rojas" : "verdes") + " con martillos normales e invertidos: la 5ta al revés."));
            }
        }

        // --- Salto de GAP: la vela k abre separada del cierre de k-1. 4to toque (k+3) contra el salto.
        for (int k = n - 3; k <= n - 2; k++) {
            if (k < 1) continue;
            int off = k == n - 2 ? 1 : 0;
            int prevClose = oc(c.get(k - 1))[1], open = oc(c.get(k))[0];
            int gap = prevClose - open; // > 0: abrió más arriba (salto alcista)
            if (Math.abs(gap) >= Math.max(3, mb * 0.35)) {
                char d = gap > 0 ? 'R' : 'V';
                out.add(new Alert("gap", "Salto de GAP", FIXED, d, off, k, k,
                        "Salto " + (gap > 0 ? "alcista" : "bajista") + " en la vela #" + (k + 1) + ": 4to toque a " + (d == 'R' ? "la baja" : "el alza") + " (retesteo)."));
            }
        }

        // --- Tercer toque en soporte / resistencia (también cubre "Mechudas")
        Alert sr = thirdTouch(c);
        if (sr != null) out.add(sr);
        return out;
    }

    static boolean similar(Market.Geo a, Market.Geo b, Market.Geo d) {
        double lo = Math.min(a.bodyLen(), Math.min(b.bodyLen(), d.bodyLen()));
        double hi = Math.max(a.bodyLen(), Math.max(b.bodyLen(), d.bodyLen()));
        return hi > 0 && lo >= hi * 0.5;
    }

    /**
     * La última vela cerrada toca (con la mecha) un nivel que ya fue techo o piso
     * al menos 2 veces antes, sin que el precio haya cerrado del otro lado -> 3er toque, rebote.
     */
    static Alert thirdTouch(List<Market.Geo> c) {
        int n = c.size();
        if (n < 12) return null;
        double tol = Math.max(2, medianRange(c, n - 30, n) * 0.25);
        Market.Geo last = c.get(n - 1);
        List<Market.Swing> sw = swings(c.subList(0, n - 1), 2);
        // resistencia
        int touches = 0, firstIdx = -1;
        for (Market.Swing s : sw) {
            if (!s.high || s.index > n - 4) continue;
            if (Math.abs(s.level - last.totalTop) <= tol) { touches++; if (firstIdx < 0) firstIdx = s.index; }
        }
        if (touches >= 2 && !closedBeyond(c, firstIdx, n - 1, last.totalTop - tol, true)) {
            return new Alert("tercer_toque", "Tercer toque S/R", FIXED, 'R', 0, firstIdx, n - 1,
                    "La vela #" + n + " toca una resistencia que ya rechazó " + touches + " veces: rebote a la baja.");
        }
        touches = 0; firstIdx = -1;
        for (Market.Swing s : sw) {
            if (s.high || s.index > n - 4) continue;
            if (Math.abs(s.level - last.totalBottom) <= tol) { touches++; if (firstIdx < 0) firstIdx = s.index; }
        }
        if (touches >= 2 && !closedBeyond(c, firstIdx, n - 1, last.totalBottom + tol, false)) {
            return new Alert("tercer_toque", "Tercer toque S/R", FIXED, 'V', 0, firstIdx, n - 1,
                    "La vela #" + n + " toca un soporte que ya rechazó " + touches + " veces: rebote al alza.");
        }
        return null;
    }

    /**
     * Techos y pisos: como en el motor de PC, pero un empate con la vela de la derecha
     * cuenta (el cierre de una vela es la apertura de la siguiente, así que es común que
     * dos velas vecinas compartan el mismo extremo).
     */
    static List<Market.Swing> swings(List<Market.Geo> g, int lb) {
        List<Market.Swing> out = new ArrayList<>();
        int n = g.size();
        for (int i = lb; i < n - lb; i++) {
            Market.Geo c = g.get(i);
            boolean hi = true, lo = true;
            for (int j = i - lb; j <= i + lb; j++) {
                if (j == i) continue;
                Market.Geo o = g.get(j);
                if (j < i ? !(c.totalTop < o.totalTop) : !(c.totalTop <= o.totalTop)) hi = false;
                if (j < i ? !(c.totalBottom > o.totalBottom) : !(c.totalBottom >= o.totalBottom)) lo = false;
            }
            if (hi) out.add(new Market.Swing(i, true, c.totalTop));
            if (lo) out.add(new Market.Swing(i, false, c.totalBottom));
        }
        return out;
    }

    /** ¿Alguna vela en [from, to) cerró más allá del nivel? (above: por encima de una resistencia). */
    static boolean closedBeyond(List<Market.Geo> c, int from, int to, double level, boolean above) {
        if (from < 0) return false;
        for (int i = from; i < to; i++) {
            int close = oc(c.get(i))[1];
            if (above ? close < level : close > level) return true;
        }
        return false;
    }

    public static String strategyName(String key) {
        for (String[] s : STRATEGIES) if (s[0].equals(key)) return s[1];
        return key;
    }

    // ---------------- Veredicto: una sola respuesta por vela ----------------

    public static final int V_DIR = 0, V_NO = 1, V_BIRTH_REVERSE = 2, V_BIRTH_SAME = 3, V_IF = 4;

    public static final class Verdict {
        public int offset, type;
        public char dir;      // V_DIR / V_IF: dirección
        public char need;     // V_DIR: color con que tiene que NACER la vela para confirmar (0 = no hace falta)
        public char risk;     // V_DIR: si la vela actual cierra de este color, se contradice (0 = no)
        public char ifColor;  // V_IF: color con que tiene que cerrar la vela actual
        public String reason = "";
        public List<String> names = new ArrayList<>();
    }

    /**
     * Junta todas las alertas que entran en la misma vela y da una sola respuesta:
     * dirección (con condiciones si las hay), o NO ENTRAR si se contradicen.
     */
    public static List<Verdict> verdicts(List<Alert> alerts) {
        List<Verdict> out = new ArrayList<>();
        for (int off = 0; off <= 1; off++) {
            List<Alert> g = new ArrayList<>();
            for (Alert a : alerts) if (a.offset == off) g.add(a);
            if (g.isEmpty()) continue;
            Verdict v = new Verdict();
            v.offset = off;
            boolean buy = false, sell = false, br = false, bs = false;
            List<Alert> ifs = new ArrayList<>();
            for (Alert a : g) {
                if (!v.names.contains(a.name)) v.names.add(a.name);
                if (a.kind == FIXED) { if (a.dir == 'V') buy = true; else sell = true; }
                else if (a.kind == BIRTH_REVERSE) br = true;
                else if (a.kind == BIRTH_SAME) bs = true;
                else ifs.add(a);
            }
            if (buy && sell) { v.type = V_NO; v.reason = "Hay estrategias que dicen BUY y otras SELL."; out.add(v); continue; }
            if (buy || sell) {
                char d = buy ? 'V' : 'R';
                if (br && bs) { v.type = V_NO; v.reason = "Martillo con mecha y cruz piden colores de nacimiento opuestos."; out.add(v); continue; }
                v.type = V_DIR; v.dir = d;
                if (br) v.need = opp(d);   // al revés del nacimiento: SELL necesita que nazca verde
                if (bs) v.need = d;        // igual al nacimiento: SELL necesita que nazca roja
                for (Alert a : ifs) if (a.dir != d) v.risk = a.ifColor;
                out.add(v);
                continue;
            }
            if (br && bs) { v.type = V_NO; v.reason = "Martillo con mecha y cruz piden colores de nacimiento opuestos."; out.add(v); continue; }
            if (br || bs) {
                v.type = br ? V_BIRTH_REVERSE : V_BIRTH_SAME;
                for (Alert a : ifs) { v.ifColor = a.ifColor; v.dir = a.dir; } // aviso extra, se muestra como nota
                out.add(v);
                continue;
            }
            // solo avisos condicionados
            char d0 = ifs.get(0).dir, c0 = ifs.get(0).ifColor;
            boolean clash = false;
            for (Alert a : ifs) if (a.ifColor == c0 && a.dir != d0) clash = true;
            if (clash) { v.type = V_NO; v.reason = "Los avisos se contradicen."; }
            else { v.type = V_IF; v.dir = d0; v.ifColor = c0; }
            out.add(v);
        }
        return out;
    }
}
