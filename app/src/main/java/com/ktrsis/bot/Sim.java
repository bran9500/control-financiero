package com.ktrsis.bot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Simulador en papel: toma las señales del bot y las opera con gestiones Masaniello,
 * en dos escenarios a la vez para compararlos. No toca dinero real.
 *  A: las gestiones de 1 acierto siguen al bot y las de 2 aciertos van al contrario.
 *  B: al revés.
 * Cada operación se resuelve con el color con que cerró la vela de entrada.
 */
public final class Sim {
    private static Sim inst;
    public static synchronized Sim get(Context c) { if (inst == null) inst = new Sim(c.getApplicationContext()); return inst; }

    private final SharedPreferences sp;
    private final File dir;
    public boolean on = false;
    public double payout = 1.92, base = 50;
    public int ops = 9;
    public boolean useNeed = true, useIf = true;

    // estadísticas de las señales del bot (sin gestión)
    int signals, hits, misses, cancelled, streakW, streakL, maxStreakL, maxStreakW, noData;
    long startedAt;
    JSONArray recent = new JSONArray();   // últimas operaciones (más reciente primero)

    // operación pendiente
    long pEntry = 0, pCond = 0; char pDir = 0, pIf = 0, pNeedC = 0, pRisk = 0, pBirth = 0; int pMode = 0; String pNames = "", pType = ""; boolean pNeed;
    java.util.Map<String, Integer> why = new java.util.LinkedHashMap<>();

    final class M {
        String id, label; int K; boolean follow;
        double c0, cap, T, injected; int n, k, wait, cyclesWon, cyclesLost, trades, wins;
        M(String id, String label, int K, boolean follow, int wait) { this.id = id; this.label = label; this.K = K; this.follow = follow; this.wait = wait; }
        void newCycle(double c) { c0 = c; cap = c; n = ops; k = K; T = c / F(ops, K); }
        double stake() { return (k <= 0 || k > n) ? 0 : Math.max(0, cap - T * F(n - 1, k)); }
        /** Devuelve [apuesta, ganancia/pérdida]. */
        double[] play(boolean win) {
            double s = stake(); trades++;
            if (win) { cap += s * (payout - 1); k--; wins++; } else cap -= s;
            n--;
            double pl = win ? s * (payout - 1) : -s;
            if (k <= 0) { cyclesWon++; newCycle(cap); }                       // meta cumplida: se renueva con lo ganado
            else if (k > n) { cyclesLost++; injected += base; newCycle(base); } // se quemó: vuelve a empezar con el capital base
            return new double[]{s, pl};
        }
        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("label", label); o.put("K", K); o.put("follow", follow);
            o.put("cap", r2(cap)); o.put("n", n); o.put("k", k); o.put("next", r2(stake())); o.put("wait", wait);
            o.put("cw", cyclesWon); o.put("cl", cyclesLost); o.put("trades", trades); o.put("wins", wins);
            o.put("injected", r2(injected)); o.put("c0", r2(c0)); o.put("T", r2(T));
            return o;
        }
        void load(JSONObject o) {
            cap = o.optDouble("cap", base); n = o.optInt("n", ops); k = o.optInt("k", K); wait = o.optInt("wait", 0);
            cyclesWon = o.optInt("cw"); cyclesLost = o.optInt("cl"); trades = o.optInt("trades"); wins = o.optInt("wins");
            injected = o.optDouble("injected", base); c0 = o.optDouble("c0", base); T = o.optDouble("T", c0 / F(ops, K));
        }
    }
    final List<M> A = new ArrayList<>(), B = new ArrayList<>();

    private Sim(Context c) {
        sp = c.getSharedPreferences("botsim", Context.MODE_PRIVATE);
        dir = new File(c.getFilesDir(), "botsim");
        dir.mkdirs();
        load();
    }

    static double r2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Factor Masaniello: capital necesario (con meta 1) quedando n operaciones y k aciertos. */
    double F(int n, int k) {
        if (k <= 0) return 1;
        if (k > n) return 0;
        double[][] t = new double[n + 1][k + 1];
        for (int i = 0; i <= n; i++) for (int j = 0; j <= k; j++) {
            if (j == 0) t[i][j] = 1;
            else if (j > i) t[i][j] = 0;
            else t[i][j] = (t[i - 1][j - 1] + (payout - 1) * t[i - 1][j]) / payout;
        }
        return t[n][k];
    }

    private void build() {
        A.clear(); B.clear();
        // A: 1 acierto sigue al bot · 2 aciertos al contrario. La 2ª de cada tipo arranca una operación después.
        A.add(new M("A1", "1 acierto · sigue", 1, true, 0));
        A.add(new M("A2", "1 acierto · sigue (desfasada)", 1, true, 1));
        A.add(new M("A3", "2 aciertos · contrario", 2, false, 0));
        A.add(new M("A4", "2 aciertos · contrario (desfasada)", 2, false, 1));
        B.add(new M("B1", "1 acierto · contrario", 1, false, 0));
        B.add(new M("B2", "1 acierto · contrario (desfasada)", 1, false, 1));
        B.add(new M("B3", "2 aciertos · sigue", 2, true, 0));
        B.add(new M("B4", "2 aciertos · sigue (desfasada)", 2, true, 1));
        for (M m : all()) { m.newCycle(base); m.injected = base; }
    }
    List<M> all() { List<M> l = new ArrayList<>(A); l.addAll(B); return l; }

    public synchronized void reset() {
        build();
        signals = hits = misses = cancelled = streakW = streakL = maxStreakL = maxStreakW = noData = 0;
        why.clear();
        startedAt = System.currentTimeMillis();
        recent = new JSONArray();
        pEntry = 0;
        try {
            StringBuilder h = new StringBuilder("fecha,hora,temporalidad,estrategias,tipo,señal,vela,acierto_bot");
            for (M m : all()) h.append(",").append(m.id).append("_lado,").append(m.id).append("_apuesta,").append(m.id).append("_resultado,").append(m.id).append("_capital");
            write("operaciones.csv", h + "\n", false);
            write("velas.csv", "fecha,hora,temporalidad,velas_cerradas,ultimas_velas(V=verde R=roja),ult_cuerpo,ult_mecha_arriba,ult_mecha_abajo,ult_total\n", false);
        } catch (Exception ignored) {}
        save();
    }

    public synchronized void config(JSONObject o) {
        boolean rebuild = o.has("base") && o.optDouble("base") != base || o.has("ops") && o.optInt("ops") != ops || o.has("payout") && o.optDouble("payout") != payout;
        base = o.optDouble("base", base); ops = Math.max(2, Math.min(30, o.optInt("ops", ops)));
        payout = Math.max(1.01, o.optDouble("payout", payout));
        useNeed = o.optBoolean("useNeed", useNeed); useIf = o.optBoolean("useIf", useIf);
        if (o.has("on")) { on = o.optBoolean("on"); if (on && startedAt == 0) reset(); }
        if (rebuild) reset(); else save();
    }

    // ------------------------------------------------------------ lectura de cada vela
    public synchronized void onRead(Analyzer.Output o) {
        if (!on || o == null || o.error != null || o.det == null) return;
        logCandles(o);
        settle(o);
        if (pEntry == 0) { pick(o); if (pEntry != 0) settle(o); }   // en la misma lectura: ver cómo nace la vela de entrada
        save();
    }

    private char colorAt(Analyzer.Output o, long start) {
        long tf = o.tfMs, fs = o.formingStartMs;
        if (fs < start + tf) return 0;                       // todavía no cerró
        int back = (int) ((fs - (start + tf)) / tf);
        int nClosed = o.det.candles.size() - 1;
        int idx = nClosed - 1 - back;
        if (idx < 0) return 'X';                              // ya no está en pantalla
        return o.det.candles.get(idx).color;
    }

    private void pick(Analyzer.Output o) {
        for (Rules.Verdict v : o.verdicts) {
            if (v.type == Rules.V_DIR && (v.need == 0 || useNeed)) {
                pEntry = o.formingStartMs + v.offset * o.tfMs; pDir = v.dir; pIf = 0; pCond = pEntry - o.tfMs;
                pNames = String.join(" + ", v.names); pType = v.need == 0 ? "directa" : "directa (pedía nacer " + (v.need == 'V' ? "verde" : "roja") + ")";
                pNeed = v.need != 0; pNeedC = v.need; pRisk = v.risk; pMode = 0; pBirth = 0;
                if (pRisk != 0) pType += " (no debía cerrar " + (pRisk == 'V' ? "verde" : "roja") + " la anterior)";
                return;
            }
            if ((v.type == Rules.V_BIRTH_REVERSE || v.type == Rules.V_BIRTH_SAME) && useNeed) {
                pEntry = o.formingStartMs + v.offset * o.tfMs; pDir = 0; pIf = 0; pCond = 0; pRisk = 0; pNeedC = 0; pBirth = 0;
                pMode = v.type == Rules.V_BIRTH_REVERSE ? 2 : 3; pNeed = true;
                pNames = String.join(" + ", v.names); pType = pMode == 2 ? "según nacimiento (al revés)" : "según nacimiento (igual)";
                return;
            }
            if (v.type == Rules.V_IF && useIf) {
                pEntry = o.formingStartMs + Math.max(1, v.offset) * o.tfMs; pDir = v.dir; pIf = v.ifColor; pCond = pEntry - o.tfMs;
                pNames = String.join(" + ", v.names); pType = "si cierra " + (v.ifColor == 'V' ? "verde" : "roja"); pNeed = false;
                pNeedC = 0; pRisk = 0; pMode = 0; pBirth = 0;
                return;
            }
        }
    }

    private void settle(Analyzer.Output o) {
        if (pEntry == 0) return;
        if (pIf != 0) {
            char c = colorAt(o, pCond);
            if (c == 0) return;
            if (c == 'X' || c != pIf) { cancel("no cerró " + (pIf == 'V' ? "verde" : "roja")); return; }
            pIf = 0;   // condición cumplida: ahora espera la vela de entrada
        }
        // la vela anterior a la entrada no debe cerrar del color que contradice la señal
        if (pRisk != 0) {
            char c = colorAt(o, pCond);
            if (c == 0) return;
            if (c == pRisk) { cancel("se contradijo (cerró " + (pRisk == 'V' ? "verde" : "roja") + ")"); return; }
            if (c == 'X') { noData++; pEntry = 0; return; }
            pRisk = 0;
        }
        // color con que NACE la vela de entrada: se ve en la lectura del comienzo de esa vela
        if ((pNeedC != 0 || pMode != 0) && pBirth == 0) {
            if (o.formingStartMs == pEntry && o.captureMs - pEntry <= Math.max(6000, o.tfMs * 4 / 10)) {
                pBirth = o.det.candles.get(o.det.candles.size() - 1).color;
                if (pNeedC != 0 && pBirth != pNeedC) { cancel("no nació " + (pNeedC == 'V' ? "verde" : "roja")); return; }
                if (pMode == 2) pDir = pBirth == 'V' ? 'R' : 'V';
                if (pMode == 3) pDir = pBirth;
                if (pMode != 0) pType += " · nació " + (pBirth == 'V' ? "verde" : "roja");
                return;   // el resultado se ve cuando esta vela cierre
            } else if (o.formingStartMs > pEntry) { noData++; why.merge("no se vio cómo nació", 1, Integer::sum); pEntry = 0; return; }
            else return;
        }
        char col = colorAt(o, pEntry);
        if (col == 0) return;
        if (col == 'X') { noData++; pEntry = 0; return; }
        boolean botWin = col == pDir;
        signals++;
        if (botWin) { hits++; streakW++; streakL = 0; maxStreakW = Math.max(maxStreakW, streakW); }
        else { misses++; streakL++; streakW = 0; maxStreakL = Math.max(maxStreakL, streakL); }
        StringBuilder row = new StringBuilder();
        Date d = new Date(pEntry);
        row.append(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)).append(',')
           .append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(d)).append(',')
           .append(Engine.tfLabel((int) (o.tfMs / 1000))).append(',')
           .append('"').append(pNames.replace("\"", "'")).append('"').append(',')
           .append('"').append(pType).append('"').append(',')
           .append(pDir == 'V' ? "BUY" : "SELL").append(',').append(col == 'V' ? "verde" : "roja").append(',').append(botWin ? "si" : "no");
        double netA = 0, netB = 0;
        for (M m : all()) {
            if (m.wait > 0) { m.wait--; row.append(",espera,0,0,").append(String.format(Locale.US, "%.2f", m.cap)); continue; }
            char side = m.follow ? pDir : (pDir == 'V' ? 'R' : 'V');
            double[] r = m.play(col == side);
            if (A.contains(m)) netA += r[1]; else netB += r[1];
            row.append(',').append(side == 'V' ? "BUY" : "SELL").append(',').append(String.format(Locale.US, "%.2f", r[0]))
               .append(',').append(String.format(Locale.US, "%.2f", r[1])).append(',').append(String.format(Locale.US, "%.2f", m.cap));
        }
        try { write("operaciones.csv", row + "\n", true); } catch (Exception ignored) {}
        addRecent(botWin ? "acierto" : "fallo", (pDir == 'V' ? "BUY" : "SELL") + " · vela " + (col == 'V' ? "verde" : "roja") + " · " + pNames,
                0, netA, netB);
        pEntry = 0;
    }

    private void cancel(String w) {
        cancelled++; why.merge(w, 1, Integer::sum);
        addRecent("cancelada", w + " · " + pNames, 0); pEntry = 0;
    }

    private void addRecent(String res, String txt, int x) { addRecent(res, txt, x, 0, 0); }
    private void addRecent(String res, String txt, int x, double a, double b) {
        try {
            JSONObject e = new JSONObject();
            e.put("t", pEntry > 0 ? pEntry : System.currentTimeMillis()); e.put("res", res); e.put("txt", txt);
            e.put("a", r2(a)); e.put("b", r2(b));
            JSONArray n = new JSONArray(); n.put(e);
            for (int i = 0; i < Math.min(30, recent.length()); i++) n.put(recent.get(i));
            recent = n;
        } catch (Exception ignored) {}
    }

    private void logCandles(Analyzer.Output o) {
        try {
            List<Market.Geo> c = o.det.candles;
            int nc = c.size() - 1;
            StringBuilder s = new StringBuilder();
            for (int i = Math.max(0, nc - 30); i < nc; i++) s.append(c.get(i).color);
            Market.Geo g = nc > 0 ? c.get(nc - 1) : null;
            Date d = new Date(o.formingStartMs);
            write("velas.csv", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d) + "," + new SimpleDateFormat("HH:mm:ss", Locale.US).format(d) + ","
                    + Engine.tfLabel((int) (o.tfMs / 1000)) + "," + nc + "," + s + ","
                    + (g == null ? ",,," : g.bodyLen() + "," + g.upperWick() + "," + g.lowerWick() + "," + g.totalLen()) + "\n", true);
        } catch (Exception ignored) {}
    }

    private void write(String name, String text, boolean append) throws Exception {
        try (FileWriter w = new FileWriter(new File(dir, name), append)) { w.write(text); }
    }

    String read(String name, int maxLines) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(new File(dir, name).toPath());
            if (lines.size() <= maxLines + 1) return String.join("\n", lines);
            List<String> out = new ArrayList<>(); out.add(lines.get(0));
            out.addAll(lines.subList(lines.size() - maxLines, lines.size()));
            return String.join("\n", out);
        } catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------ estado
    private double total(List<M> l) { double t = 0; for (M m : l) t += m.cap; return t; }
    private double injected(List<M> l) { double t = 0; for (M m : l) t += m.injected; return t; }

    public synchronized String json() {
        try {
            JSONObject o = new JSONObject();
            o.put("on", on); o.put("payout", payout); o.put("base", base); o.put("ops", ops); o.put("useNeed", useNeed); o.put("useIf", useIf);
            o.put("signals", signals); o.put("hits", hits); o.put("misses", misses); o.put("cancelled", cancelled); o.put("noData", noData);
            o.put("streakW", streakW); o.put("streakL", streakL); o.put("maxStreakL", maxStreakL); o.put("maxStreakW", maxStreakW);
            o.put("startedAt", startedAt);
            o.put("pending", pEntry == 0 ? JSONObject.NULL : new JSONObject().put("entry", pEntry).put("dir", String.valueOf(pDir)).put("names", pNames).put("type", pType));
            JSONObject sa = new JSONObject(), sb = new JSONObject();
            JSONArray ma = new JSONArray(), mb = new JSONArray();
            for (M m : A) ma.put(m.json());
            for (M m : B) mb.put(m.json());
            sa.put("m", ma); sa.put("total", r2(total(A))); sa.put("injected", r2(injected(A))); sa.put("net", r2(total(A) - injected(A)));
            sb.put("m", mb); sb.put("total", r2(total(B))); sb.put("injected", r2(injected(B))); sb.put("net", r2(total(B) - injected(B)));
            o.put("A", sa); o.put("B", sb); o.put("recent", recent);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    private void save() {
        try {
            JSONObject o = new JSONObject(json());
            o.put("pEntry", pEntry); o.put("pCond", pCond); o.put("pDir", String.valueOf(pDir == 0 ? ' ' : pDir));
            o.put("pIf", String.valueOf(pIf == 0 ? ' ' : pIf)); o.put("pNames", pNames); o.put("pType", pType);
            o.put("pNeedC", String.valueOf(pNeedC == 0 ? ' ' : pNeedC)); o.put("pRisk", String.valueOf(pRisk == 0 ? ' ' : pRisk));
            o.put("pBirth", String.valueOf(pBirth == 0 ? ' ' : pBirth)); o.put("pMode", pMode);
            sp.edit().putString("state", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void load() {
        build();
        String s = sp.getString("state", null);
        if (s == null) return;
        try {
            JSONObject o = new JSONObject(s);
            on = o.optBoolean("on"); payout = o.optDouble("payout", 1.92); base = o.optDouble("base", 50); ops = o.optInt("ops", 9);
            useNeed = o.optBoolean("useNeed", true); useIf = o.optBoolean("useIf", true);
            build();
            signals = o.optInt("signals"); hits = o.optInt("hits"); misses = o.optInt("misses"); cancelled = o.optInt("cancelled"); noData = o.optInt("noData");
            streakW = o.optInt("streakW"); streakL = o.optInt("streakL"); maxStreakL = o.optInt("maxStreakL"); maxStreakW = o.optInt("maxStreakW");
            startedAt = o.optLong("startedAt");
            recent = o.optJSONArray("recent") != null ? o.optJSONArray("recent") : new JSONArray();
            JSONArray ma = o.getJSONObject("A").getJSONArray("m"), mb = o.getJSONObject("B").getJSONArray("m");
            for (int i = 0; i < A.size() && i < ma.length(); i++) A.get(i).load(ma.getJSONObject(i));
            for (int i = 0; i < B.size() && i < mb.length(); i++) B.get(i).load(mb.getJSONObject(i));
            pEntry = o.optLong("pEntry"); pCond = o.optLong("pCond");
            String d = o.optString("pDir", " "), f = o.optString("pIf", " ");
            pDir = d.trim().isEmpty() ? 0 : d.charAt(0); pIf = f.trim().isEmpty() ? 0 : f.charAt(0);
            pNames = o.optString("pNames"); pType = o.optString("pType");
            pNeedC = ch(o.optString("pNeedC", " ")); pRisk = ch(o.optString("pRisk", " ")); pBirth = ch(o.optString("pBirth", " ")); pMode = o.optInt("pMode");
        } catch (Exception ignored) { build(); }
    }

    private static char ch(String s) { return s.trim().isEmpty() ? 0 : s.charAt(0); }

    /** Informe completo para exportar (resumen + operaciones + velas). */
    public synchronized String report() {
        StringBuilder r = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        r.append("# Informe del simulador Masaniello + Bot Señales\n");
        r.append("Generado: ").append(f.format(new Date())).append("  ·  desde: ").append(startedAt > 0 ? f.format(new Date(startedAt)) : "-").append("\n\n");
        r.append("Configuración: capital por gestión $").append(base).append(" · ").append(ops).append(" operaciones · payout ").append(payout)
         .append(" · señales con condición de nacimiento: ").append(useNeed ? "sí" : "no").append(" · señales 'si cierra': ").append(useIf ? "sí" : "no").append("\n\n");
        int tot = hits + misses;
        r.append("## Señales del bot\n");
        r.append("- Operadas: ").append(tot).append(" · aciertos ").append(hits).append(" · fallos ").append(misses)
         .append(" · efectividad ").append(tot > 0 ? String.format(Locale.US, "%.1f%%", hits * 100.0 / tot) : "-").append("\n");
        r.append("- Canceladas (condición no se cumplió): ").append(cancelled).append(why.isEmpty() ? "" : " " + why).append(" · sin dato: ").append(noData).append("\n");
        r.append("- Racha máxima de aciertos: ").append(maxStreakW).append(" · de fallos: ").append(maxStreakL).append("\n");
        r.append("- Efectividad mínima para no perder con payout ").append(payout).append(": ").append(String.format(Locale.US, "%.1f%%", 100 / payout)).append("\n\n");
        for (int s = 0; s < 2; s++) {
            List<M> l = s == 0 ? A : B;
            r.append(s == 0 ? "## Escenario A (1 acierto sigue al bot · 2 aciertos al contrario)\n" : "## Escenario B (1 acierto al contrario · 2 aciertos sigue al bot)\n");
            r.append(String.format(Locale.US, "- Capital ahora $%.2f · puesto $%.2f · resultado %s$%.2f\n", total(l), injected(l), total(l) - injected(l) >= 0 ? "+" : "-", Math.abs(total(l) - injected(l))));
            for (M m : l) r.append(String.format(Locale.US, "  - %s %s: capital $%.2f · ciclos ganados %d · quemados %d · operaciones %d (%d ganadas)\n",
                    m.id, m.label, m.cap, m.cyclesWon, m.cyclesLost, m.trades, m.wins));
            r.append("\n");
        }
        r.append("## Operaciones\n```csv\n").append(read("operaciones.csv", 2000)).append("\n```\n\n");
        r.append("## Velas leídas (últimas 600 lecturas)\n```csv\n").append(read("velas.csv", 600)).append("\n```\n");
        return r.toString();
    }
}
