package com.ktrsis.bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lee las velas de una captura (solo la zona calibrada del gráfico).
 *
 * Misma idea que el motor de PC (color por columna -> tramos -> velas), con
 * los ajustes que pide la pantalla de un celular:
 *  - Se borran las filas que son líneas horizontales largas (línea de precio,
 *    niveles), para que no unan todas las columnas en una sola vela.
 *  - Una columna ambigua (antialiasing) no corta la vela en dos: queda como
 *    parte del tramo y el color lo decide la mayoría.
 *  - Dos velas pegadas se separan: por cambio de color, y si son del mismo
 *    color, por el ancho típico de una vela.
 * Funciones puras (int[] ARGB), sin clases de Android: se prueban en la PC.
 */
public final class Detector {
    private Detector() {}

    public static final class Result {
        public int width, height;
        public List<Market.Geo> candles = new ArrayList<>(); // todas, incluida la última (en formación)
        public String columns = "";
        public int candleWidth;
        public int removedRows;
        public int splitCount; // velas que salieron de separar tramos pegados
        public int dropped;    // cosas descartadas por estar lejos de las velas (botones, etiquetas)
    }

    static boolean isRed(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return r > 140 && r - g > 50 && r - b > 30;
    }

    /** Rojo / verde oscuros: las mechas en Pocket Option se dibujan más oscuras que el cuerpo. Solo se usan para medir mechas. */
    static boolean isWeakRed(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return r >= 70 && r - g >= 40 && r - b >= 30;
    }

    static boolean isWeakGreen(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return g >= 45 && g - r >= 30 && g - b >= 15;
    }

    static boolean isGreen(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return g > 100 && g - r > 20 && g - b > 10;
    }

    public static Result detect(int[] px, int w, int h) {
        Result res = new Result();
        res.width = w; res.height = h;
        if (w <= 0 || h <= 0) return res;

        // 0 = fondo, 1 = rojo, 2 = verde
        byte[] m = new byte[w * h];
        for (int i = 0; i < m.length; i++) {
            int p = px[i];
            m[i] = isRed(p) ? (byte) 1 : isGreen(p) ? (byte) 2 : isWeakRed(p) ? (byte) 3 : isWeakGreen(p) ? (byte) 4 : (byte) 0;
        }

        // 1) Filas que son líneas horizontales largas -> se borran
        int longStretch = Math.max(12, w / 4);
        for (int y = 0; y < h; y++) {
            int cover = 0, run = 0, best = 0, base = y * w;
            for (int x = 0; x < w; x++) {
                byte v = m[base + x];
                if (v == 1 || v == 2) { cover++; run++; if (run > best) best = run; } else run = 0;
            }
            if (best >= longStretch || cover >= w * 0.6) {
                Arrays.fill(m, base, base + w, (byte) 0);
                res.removedRows++;
            }
        }

        // 1b) Columnas que son líneas verticales de lado a lado (marcas de operación): se
        // reemplazan por la columna de la izquierda, así no parten en dos una vela que cruzan.
        for (int x = 0; x < w; x++) {
            int cnt = 0;
            for (int y = 0; y < h; y++) { byte v = m[y * w + x]; if (v == 1 || v == 2) cnt++; }
            if (cnt >= h * 0.9) for (int y = 0; y < h; y++) m[y * w + x] = x > 0 ? m[y * w + x - 1] : 0;
        }

        // 2) Conteo por columna y clasificación
        int[] rc = new int[w], gc = new int[w];
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                byte v = m[base + x];
                if (v == 1) rc[x]++; else if (v == 2) gc[x]++;
            }
        }
        char[] col = new char[w];
        for (int x = 0; x < w; x++) {
            int r = rc[x], g = gc[x];
            if (r + g == 0) col[x] = '.';
            else if (r >= Math.max(1, Math.round(g * 1.5f))) col[x] = 'R';
            else if (g >= Math.max(1, Math.round(r * 1.5f))) col[x] = 'V';
            else col[x] = '?';
        }
        res.columns = new String(col);

        // 3) Tramos contiguos de columnas con color
        List<int[]> blocks = new ArrayList<>();
        for (int x = 0; x < w; ) {
            if (col[x] == '.') { x++; continue; }
            int s = x;
            while (x < w && col[x] != '.') x++;
            blocks.add(new int[]{s, x});
        }
        if (blocks.isEmpty()) return res;

        // 4) Dentro de cada tramo, segmentos por color
        List<int[]> segs = new ArrayList<>(); // {x0, x1, color}
        List<List<int[]>> perBlock = new ArrayList<>();
        for (int[] b : blocks) {
            List<int[]> s = colorSegments(col, b[0], b[1]);
            perBlock.add(s);
            segs.addAll(s);
        }

        // Ancho típico de una vela: percentil 35 de los segmentos de >= 3 px
        // (bajo a propósito: si hay velas pegadas, los segmentos dobles quedan arriba).
        int[] widths = segs.stream().mapToInt(s -> s[1] - s[0]).filter(v -> v >= 3).sorted().toArray();
        int cw = widths.length == 0 ? 3 : widths[(int) Math.floor((widths.length - 1) * 0.35)];
        res.candleWidth = cw;

        // 5) Limpieza de ruido + separación de velas pegadas
        List<int[]> narrow = new ArrayList<>(); // tramos angostos: ruido... o un doji al que se le borró el cuerpo
        for (List<int[]> bs : perBlock) {
            List<int[]> merged = absorbSmall(bs, Math.max(1, (int) Math.round(cw * 0.3)));
            for (int[] s : merged) {
                int width = s[1] - s[0];
                if (width < Math.max(2, Math.round(cw * 0.4))) { narrow.add(s); continue; }
                int k = width >= cw * 1.6 ? Math.max(1, Math.round((float) width / cw)) : 1;
                if (k > 1) res.splitCount += k - 1;
                for (int i = 0; i < k; i++) {
                    int a = s[0] + (int) Math.round((double) width * i / k);
                    int b = s[0] + (int) Math.round((double) width * (i + 1) / k);
                    Market.Geo g = geometry(m, w, h, a, b, (char) s[2]);
                    if (g != null) res.candles.add(g);
                }
            }
        }
        recoverNarrow(res, narrow, m, w, h, cw);
        keepMainGroup(res);
        return res;
    }

    /**
     * Deja solo el grupo principal de velas: si algo verde o rojo quedó dentro de la zona
     * pero lejos de las velas (botones, etiquetas de precio, íconos), queda separado por un
     * hueco de más de 3 velas y se descarta. Sin esto, un botón a la derecha pasaba por
     * "vela en formación" y la vela real en formación se contaba como cerrada.
     */
    static void keepMainGroup(Result res) {
        List<Market.Geo> c = res.candles;
        if (c.size() < 4) return;
        double[] d = new double[c.size() - 1];
        for (int i = 1; i < c.size(); i++) d[i - 1] = center(c.get(i)) - center(c.get(i - 1));
        double[] sd = d.clone();
        Arrays.sort(sd);
        double pitch = sd[sd.length / 2];
        if (pitch <= 0) return;
        int bestStart = 0, bestLen = 0, start = 0;
        for (int i = 1; i <= c.size(); i++) {
            if (i == c.size() || d[i - 1] > pitch * 3) {
                int len = i - start;
                if (len >= bestLen) { bestLen = len; bestStart = start; } // a igual tamaño, el de la derecha
                start = i;
            }
        }
        if (bestLen == c.size()) return;
        res.dropped = c.size() - bestLen;
        res.candles = new ArrayList<>(c.subList(bestStart, bestStart + bestLen));
    }

    /**
     * Un tramo angosto que cae justo donde, por el espaciado de las velas, debería
     * haber una vela (y no es una línea vertical de lado a lado) se toma como vela:
     * es un doji o una vela recién abierta cuyo cuerpo quedó sobre la línea de precio.
     */
    static void recoverNarrow(Result res, List<int[]> narrow, byte[] m, int w, int h, int cw) {
        List<Market.Geo> c = res.candles;
        if (c.size() < 3 || narrow.isEmpty()) return;
        double[] d = new double[c.size() - 1];
        for (int i = 1; i < c.size(); i++) d[i - 1] = center(c.get(i)) - center(c.get(i - 1));
        double[] sd = d.clone();
        Arrays.sort(sd);
        double pitch = sd[sd.length / 2];
        if (pitch <= 0) return;
        for (int[] s : narrow) {
            Market.Geo g = geometry(m, w, h, s[0], s[1], (char) s[2]);
            if (g == null || g.totalLen() < 2 || g.totalLen() > h * 0.85) continue;
            double x = (s[0] + s[1]) / 2.0;
            int idx = 0;
            while (idx < c.size() && center(c.get(idx)) < x) idx++;
            Market.Geo prev = idx > 0 ? c.get(idx - 1) : null, next = idx < c.size() ? c.get(idx) : null;
            boolean okPrev = prev == null || Math.abs(x - center(prev) - pitch) <= pitch * 0.35;
            boolean okNext = next == null || Math.abs(center(next) - x - pitch) <= pitch * 0.35;
            if (!okPrev || !okNext) continue;
            int x0 = Math.max(0, (int) Math.round(x - cw / 2.0)), x1 = Math.min(w, x0 + cw);
            Market.Geo full = geometry(m, w, h, x0, x1, g.color);
            if (full == null) full = g;
            c.add(idx, full);
            res.splitCount++;
        }
    }

    static double center(Market.Geo g) { return (g.x0 + g.x1) / 2.0; }

    /** Segmentos de un mismo color dentro de [x0, x1). Las columnas '?' se pegan al segmento en curso. */
    static List<int[]> colorSegments(char[] col, int x0, int x1) {
        List<int[]> out = new ArrayList<>();
        int s = x0; char cur = 0;
        // color del primer tramo: la primera columna decidida
        for (int x = x0; x < x1; x++) if (col[x] == 'R' || col[x] == 'V') { cur = col[x]; break; }
        if (cur == 0) { out.add(new int[]{x0, x1, '?'}); return out; }
        for (int x = x0; x < x1; x++) {
            char c = col[x];
            if ((c == 'R' || c == 'V') && c != cur) {
                out.add(new int[]{s, x, cur});
                s = x; cur = c;
            }
        }
        out.add(new int[]{s, x1, cur});
        return out;
    }

    /** Segmentos angostos (parpadeo de color en un borde) se absorben en el vecino más ancho; luego se unen los del mismo color. */
    static List<int[]> absorbSmall(List<int[]> segs, int minW) {
        List<int[]> list = new ArrayList<>();
        for (int[] s : segs) list.add(s.clone());
        boolean changed = true;
        while (changed && list.size() > 1) {
            changed = false;
            int idx = -1, smallest = Integer.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                int wd = list.get(i)[1] - list.get(i)[0];
                if (wd < minW && wd < smallest) { smallest = wd; idx = i; }
            }
            if (idx >= 0) {
                int[] s = list.get(idx);
                int[] left = idx > 0 ? list.get(idx - 1) : null, right = idx < list.size() - 1 ? list.get(idx + 1) : null;
                int[] into = left == null ? right : right == null ? left : ((left[1] - left[0]) >= (right[1] - right[0]) ? left : right);
                into[0] = Math.min(into[0], s[0]);
                into[1] = Math.max(into[1], s[1]);
                list.remove(idx);
                changed = true;
            }
            // unir vecinos del mismo color
            for (int i = list.size() - 1; i > 0; i--) {
                int[] a = list.get(i - 1), b = list.get(i);
                if (a[2] == b[2]) { a[1] = b[1]; list.remove(i); changed = true; }
            }
        }
        // un tramo que solo tenía columnas ambiguas: se decide por mayoría de píxeles más adelante
        return list;
    }

    /** Cuerpo vs. mechas de la vela en columnas [x0, x1), como extract_candle_geometry(). */
    static Market.Geo geometry(byte[] m, int w, int h, int x0, int x1, char color) {
        int width = x1 - x0;
        if (width <= 0) return null;
        if (color == '?') {
            int r = 0, g = 0;
            for (int y = 0; y < h; y++) for (int x = x0; x < x1; x++) { byte v = m[y * w + x]; if (v == 1) r++; else if (v == 2) g++; }
            color = r >= g ? 'R' : 'V';
        }
        byte want = color == 'R' ? (byte) 1 : (byte) 2;
        byte weak = (byte) (want + 2);
        int bodyThr = Math.max(1, Math.round(width * 0.55f));
        int cx0 = x0 + width / 5, cx1 = Math.max(cx0 + 1, x1 - width / 5); // centro de la vela (donde va la mecha)
        int[] cnt = new int[h], wk = new int[h];
        boolean any = false;
        for (int y = 0; y < h; y++) {
            int c = 0, k = 0, base = y * w;
            for (int x = x0; x < x1; x++) {
                byte v = m[base + x];
                if (v == want) c++;
                if ((v == want || v == weak) && x >= cx0 && x < cx1) k++;
            }
            cnt[y] = c; wk[y] = k;
            if (c > 0) any = true;
        }
        if (!any) return null;
        // Cuerpo: el tramo más largo de filas "anchas" (se tolera 1 fila suelta en el medio).
        int bTop = -1, bBottom = -1, best = 0;
        for (int y = 0; y < h; ) {
            if (cnt[y] < bodyThr) { y++; continue; }
            int s0 = y, last = y;
            while (y < h && (cnt[y] >= bodyThr || (y + 1 < h && cnt[y + 1] >= bodyThr && y - last <= 1))) { if (cnt[y] >= bodyThr) last = y; y++; }
            int len = last + 1 - s0;
            if (len > best) { best = len; bTop = s0; bBottom = last + 1; }
        }
        int coreTop, coreBottom;
        if (bTop < 0) {
            // doji: no hay filas anchas; el cuerpo es la fila con más color (largo 0)
            int r = 0;
            for (int y = 1; y < h; y++) if (cnt[y] > cnt[r]) r = y;
            coreTop = r; coreBottom = r + 1;
        } else { coreTop = bTop; coreBottom = bBottom; }
        // Mechas: solo el color PEGADO al cuerpo (huecos de hasta 2 filas). Lo que está suelto
        // en la misma columna (íconos, textos, fondo) ya no estira la vela.
        int top = coreTop, gap = 0;
        for (int y = coreTop - 1; y >= 0; y--) { if (cnt[y] > 0 || wk[y] > 0) { top = y; gap = 0; } else if (++gap > 2) break; }
        int bottom = coreBottom; gap = 0;
        for (int y = coreBottom; y < h; y++) { if (cnt[y] > 0 || wk[y] > 0) { bottom = y + 1; gap = 0; } else if (++gap > 2) break; }
        Market.Geo g = new Market.Geo();
        g.color = color; g.x0 = x0; g.x1 = x1;
        g.totalTop = top; g.totalBottom = bottom;
        if (bTop >= 0) { g.bodyTop = bTop; g.bodyBottom = bBottom; }
        else { int mid = (top + bottom) / 2; g.bodyTop = mid; g.bodyBottom = mid; }
        return g;
    }
}
