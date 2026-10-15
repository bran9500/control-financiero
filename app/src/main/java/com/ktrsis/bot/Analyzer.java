package com.ktrsis.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Velas detectadas -> estrategias de los PDF -> alertas con hora de entrada. Java puro. */
public final class Analyzer {
    private Analyzer() {}

    public static final class Settings {
        public int tfSec = 60;
        public int clockOffsetSec = 0;
        public boolean auto = false;
        public Set<String> disabled = new HashSet<>();
    }

    public static final class Output {
        public Detector.Result det;
        public long captureMs, formingStartMs, tfMs;
        public List<Rules.Alert> alerts = new ArrayList<>();
        public List<Rules.Verdict> verdicts = new ArrayList<>();
        public long entryMs(Rules.Verdict v) { return formingStartMs + v.offset * tfMs; }
        public boolean lateral;
        public String error;

        public long entryMs(Rules.Alert a) { return formingStartMs + a.offset * tfMs; }
        public long mgMs(Rules.Alert a) { return entryMs(a) + tfMs; }
    }

    public static Output run(Detector.Result det, Settings s, long captureMs) {
        Output o = new Output();
        o.det = det;
        o.captureMs = captureMs;
        o.tfMs = s.tfSec * 1000L;
        long t = captureMs + s.clockOffsetSec * 1000L;
        o.formingStartMs = (t / o.tfMs) * o.tfMs - s.clockOffsetSec * 1000L;

        List<Market.Geo> all = det.candles;
        if (all.size() < 4) {
            o.error = all.isEmpty() ? "No se vio ninguna vela en la zona calibrada." : "Se ven muy pocas velas.";
            return o;
        }
        List<Market.Geo> closed = new ArrayList<>(all.subList(0, all.size() - 1)); // la última está en formación
        o.lateral = Rules.lateral(closed, 12);
        for (Rules.Alert a : Rules.evaluate(closed)) if (!s.disabled.contains(a.key)) o.alerts.add(a);
        // primero la vela en curso, luego la siguiente; avisos condicionados al final
        o.alerts.sort((a, b) -> a.offset != b.offset ? Integer.compare(a.offset, b.offset)
                : Integer.compare(a.kind == Rules.IF_CLOSE ? 1 : 0, b.kind == Rules.IF_CLOSE ? 1 : 0));
        o.verdicts = Rules.verdicts(o.alerts);
        return o;
    }

    // ---------------- JSON ----------------

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(String.format(Locale.US, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static String ch(char c) { return c == 0 ? "null" : "\"" + c + "\""; }

    /** Forma de la vela para mostrarla en la app: "MN-L" martillo normal mecha abajo, "MM-U" martillo con mecha arriba, "X" cruz. */
    static String shape(Market.Geo g) {
        char hn = Rules.hammerNormal(g);
        if (hn != 0) return "MN-" + hn;
        char hw = Rules.hammerWick(g);
        if (hw != 0) return "MM-" + hw;
        return Rules.cross(g) ? "X" : "";
    }

    public static String toJson(Output o) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"captureMs\":").append(o.captureMs)
                .append(",\"formingStartMs\":").append(o.formingStartMs)
                .append(",\"tfMs\":").append(o.tfMs)
                .append(",\"lateral\":").append(o.lateral)
                .append(",\"error\":").append(o.error == null ? "null" : q(o.error));
        Detector.Result d = o.det;
        if (d != null) {
            b.append(",\"crop\":{\"w\":").append(d.width).append(",\"h\":").append(d.height)
                    .append(",\"candleWidth\":").append(d.candleWidth).append("}");
            b.append(",\"candles\":[");
            for (int i = 0; i < d.candles.size(); i++) {
                Market.Geo g = d.candles.get(i);
                if (i > 0) b.append(',');
                b.append("[").append(ch(g.color)).append(',').append(g.x0).append(',').append(g.x1).append(',')
                        .append(g.totalTop).append(',').append(g.totalBottom).append(',').append(g.bodyTop).append(',').append(g.bodyBottom)
                        .append(',').append(q(shape(g))).append("]");
            }
            b.append("]");
        }
        b.append(",\"alerts\":[");
        for (int i = 0; i < o.alerts.size(); i++) {
            Rules.Alert a = o.alerts.get(i);
            if (i > 0) b.append(',');
            b.append("{\"key\":").append(q(a.key)).append(",\"name\":").append(q(a.name))
                    .append(",\"why\":").append(q(a.why)).append(",\"kind\":").append(a.kind)
                    .append(",\"dir\":").append(ch(a.dir)).append(",\"ifColor\":").append(ch(a.ifColor))
                    .append(",\"offset\":").append(a.offset)
                    .append(",\"first\":").append(a.firstTouch).append(",\"last\":").append(a.lastTouch)
                    .append(",\"entryMs\":").append(o.entryMs(a)).append(",\"mgMs\":").append(o.mgMs(a)).append("}");
        }
        b.append("],\"verdicts\":[");
        for (int i = 0; i < o.verdicts.size(); i++) {
            Rules.Verdict v = o.verdicts.get(i);
            if (i > 0) b.append(',');
            b.append("{\"offset\":").append(v.offset).append(",\"type\":").append(v.type)
                    .append(",\"dir\":").append(ch(v.dir)).append(",\"need\":").append(ch(v.need))
                    .append(",\"risk\":").append(ch(v.risk)).append(",\"ifColor\":").append(ch(v.ifColor))
                    .append(",\"reason\":").append(q(v.reason))
                    .append(",\"entryMs\":").append(o.entryMs(v)).append(",\"mgMs\":").append(o.entryMs(v) + o.tfMs)
                    .append(",\"names\":[");
            for (int k = 0; k < v.names.size(); k++) { if (k > 0) b.append(','); b.append(q(v.names.get(k))); }
            b.append("]}");
        }
        return b.append("]}").toString();
    }
}
