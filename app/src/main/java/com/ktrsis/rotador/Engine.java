package com.ktrsis.rotador;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lógica de los 3 lotes con martingala rotativa. Compartida por la ventana flotante y la pantalla principal. */
public class Engine {
    public static final int MAX = 6;   // máximo de lotes
    public int nLots = 3;              // lotes activos (1 a 6)

    public double payout = 92;   // %
    public double base = 1;      // $
    public int steps = 5;
    public String mode = "quick"; // quick = duplicar con último ajustado, double = ×2, exact = recuperación exacta

    public int[] step = new int[MAX];
    public double[] pl = new double[MAX];
    public boolean[] dead = new boolean[MAX];
    public int turn = 0;
    public int ops = 0, wins = 0, losses = 0;
    public JSONArray log = new JSONArray(); // más reciente primero
    public long startedAt = 0;              // hora de la primera operación de la sesión
    public int burned = 0;                  // lotes quemados en la sesión
    public int cuts = 0;                    // ciclos cortados a mano para asegurar ganancia
    private JSONArray sessions = new JSONArray(); // sesiones archivadas, la más reciente primero

    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final SharedPreferences prefs;

    private static Engine instance;

    public static synchronized Engine get(Context ctx) {
        if (instance == null) instance = new Engine(ctx.getApplicationContext());
        return instance;
    }

    private Engine(Context ctx) {
        prefs = ctx.getSharedPreferences("rotador", Context.MODE_PRIVATE);
        load();
    }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    private void changed() { save(); for (Runnable r : new ArrayList<>(listeners)) r.run(); }

    static double r2(double x) { return Math.ceil(x * 100 - 1e-9) / 100.0; }

    public static String money(double v) {
        return (v < 0 ? "−$" : "$") + String.format(Locale.US, "%.2f", Math.abs(v));
    }

    public double[] sequence() {
        double p = payout / 100.0, lost = 0;
        double[] out = new double[steps];
        for (int i = 0; i < steps; i++) {
            double s;
            if (mode.equals("double")) {
                s = r2(base * Math.pow(2, i));
            } else if (mode.equals("quick")) {
                s = i < steps - 1 ? r2(base * Math.pow(2, i)) : Math.ceil(lost / p / base - 1e-9) * base;
            } else {
                s = i == 0 ? r2(base) : r2((lost + base * p) / p);
            }
            out[i] = s;
            lost += s;
        }
        return out;
    }

    public double lotBudget() {
        double t = 0;
        for (double s : sequence()) t += s;
        return r2(t);
    }

    public double currentStake() {
        if (turn < 0) return 0;
        return sequence()[step[turn]];
    }

    public double net() {
        double t = 0;
        for (double v : pl) t += v;
        return r2(t);
    }

    private int nextAlive(int from) {
        for (int k = 1; k <= nLots; k++) {
            int i = (from + k) % nLots;
            if (!dead[i]) return i;
        }
        return -1;
    }

    public void result(boolean win) {
        int i = turn;
        if (i < 0 || dead[i]) return;
        pushHistory();
        double[] sq = sequence();
        double stake = sq[step[i]], p = payout / 100.0;
        ops++;
        long now = System.currentTimeMillis();
        if (startedAt == 0) startedAt = now;
        boolean burns = !win && step[i] >= sq.length - 1;
        if (burns) burned++;
        String tag = "L" + (i + 1) + " p" + (step[i] + 1);
        double value = win ? r2(stake * p) : -stake;
        try {
            JSONObject e = new JSONObject();
            e.put("t", tag); e.put("r", win ? "Ganó" : "Perdió"); e.put("v", value); e.put("stake", stake);
            e.put("ts", now); e.put("lot", i); e.put("s", step[i]); e.put("win", win);
            if (burns) e.put("burn", true);
            JSONArray nl = new JSONArray(); nl.put(e);
            for (int k = 0; k < Math.min(log.length(), 1999); k++) nl.put(log.get(k));
            log = nl;
        } catch (Exception ignored) {}
        if (win) {
            pl[i] = r2(pl[i] + r2(stake * p));
            wins++;
            step[i] = 0;
        } else {
            pl[i] = r2(pl[i] - stake);
            losses++;
            if (step[i] >= sq.length - 1) { dead[i] = true; step[i] = 0; }
            else step[i]++;
        }
        turn = nextAlive(i);
        changed();
    }

    /** Corta el ciclo del lote actual: asume lo perdido, vuelve a la base y pasa al siguiente lote. */
    public void cut() {
        int i = turn;
        if (i < 0 || dead[i] || step[i] == 0) return;
        pushHistory();
        double[] sq = sequence();
        double lost = 0;
        for (int k = 0; k < step[i]; k++) lost += sq[k];
        long now = System.currentTimeMillis();
        if (startedAt == 0) startedAt = now;
        try {
            JSONObject e = new JSONObject();
            e.put("t", "L" + (i + 1) + " p" + (step[i] + 1)); e.put("r", "Cortó"); e.put("v", 0);
            e.put("stake", sq[step[i]]); e.put("lost", r2(lost));
            e.put("ts", now); e.put("lot", i); e.put("s", step[i]); e.put("cut", true);
            JSONArray nl = new JSONArray(); nl.put(e);
            for (int k = 0; k < Math.min(log.length(), 1999); k++) nl.put(log.get(k));
            log = nl;
        } catch (Exception ignored) {}
        cuts++;
        step[i] = 0;
        turn = nextAlive(i);
        changed();
    }

    /** Meta de volumen (bonos del broker): {"target":3000,"since":ms} o vacío. */
    public String getGoal() { return prefs.getString("goal", ""); }

    public void setGoal(String json) {
        prefs.edit().putString("goal", json == null ? "" : json).apply();
        changed();
    }

    /** Riesgo y meta del día: {"cap":1200,"risk":6,"goal":9} (porcentajes del capital) o vacío. */
    public String getPlan() { return prefs.getString("plan", ""); }

    public void setPlan(String json) {
        prefs.edit().putString("plan", json == null ? "" : json).apply();
        changed();
    }

    /** {capital, riesgo $, meta $} o null si no hay plan. */
    public double[] plan() {
        try {
            String s = getPlan();
            if (s == null || s.isEmpty()) return null;
            JSONObject o = new JSONObject(s);
            double cap = o.optDouble("cap", 0), risk = o.optDouble("risk", 0), goal = o.optDouble("goal", 0);
            if (cap <= 0 || (risk <= 0 && goal <= 0)) return null;
            return new double[]{ cap, r2(cap * risk / 100.0), r2(cap * goal / 100.0) };
        } catch (Exception e) { return null; }
    }

    /** Cambia cuántos lotes rotan (1 a 6). Los lotes que se apagan conservan su resultado en el neto. */
    public void setLots(int n) {
        n = Math.max(1, Math.min(MAX, n));
        if (n == nLots) return;
        pushHistory();
        for (int i = n; i < MAX; i++) { step[i] = 0; dead[i] = false; }
        nLots = n;
        if (turn >= nLots || turn < 0 || dead[turn]) turn = nextAlive(nLots - 1);
        changed();
    }

    /** Reemplaza todas las sesiones guardadas (se usa al fusionar). */
    public void setSessions(String json) {
        try {
            sessions = new JSONArray(json);
            prefs.edit().putString("sessions", sessions.toString()).apply();
        } catch (Exception ignored) {}
        changed();
    }

    public boolean canUndo() { return !history.isEmpty(); }

    public void undo() {
        String h = history.pollLast();
        if (h == null) return;
        try { applyState(new JSONObject(h)); } catch (Exception ignored) {}
        changed();
    }

    public void reviveAll() {
        pushHistory();
        for (int i = 0; i < nLots; i++) { if (dead[i]) { dead[i] = false; step[i] = 0; } }
        if (turn < 0) turn = 0;
        changed();
    }

    public void reviveLot(int k) {
        if (k < 0 || k >= nLots || !dead[k]) return;
        pushHistory();
        dead[k] = false; step[k] = 0;
        if (turn < 0) turn = k;
        changed();
    }

    /** Estado en el formato que usa la pantalla principal (index.html). */
    public String webState(boolean overlayOn) {
        JSONObject o = new JSONObject();
        try {
            JSONObject cfg = new JSONObject();
            cfg.put("payout", payout); cfg.put("base", base); cfg.put("steps", steps); cfg.put("mode", mode); cfg.put("v", 2);
            JSONArray lots = new JSONArray();
            for (int i = 0; i < nLots; i++) {
                JSONObject l = new JSONObject();
                l.put("step", step[i]); l.put("pl", pl[i]); l.put("dead", dead[i]); l.put("losses", 0);
                lots.put(l);
            }
            o.put("cfg", cfg); o.put("lots", lots); o.put("turn", turn);
            o.put("ops", ops); o.put("w", wins); o.put("l", losses);
            o.put("log", log); o.put("hist", new JSONArray()); o.put("view", 0);
            o.put("start", startedAt); o.put("burned", burned); o.put("cuts", cuts);
            double off = 0;
            for (int i = nLots; i < MAX; i++) off += pl[i];
            o.put("plOff", r2(off)); o.put("nLots", nLots);
        } catch (Exception ignored) {}
        return o.toString();
    }

    /** Archiva la sesión actual (si tiene operaciones) y arranca una nueva. */
    public void closeSession() {
        if (ops > 0) {
            try {
                JSONObject o = new JSONObject();
                o.put("start", startedAt); o.put("end", System.currentTimeMillis());
                o.put("ops", ops); o.put("w", wins); o.put("l", losses);
                o.put("net", net()); o.put("burned", burned); o.put("cuts", cuts);
                JSONObject cfg = new JSONObject();
                cfg.put("payout", payout); cfg.put("base", base); cfg.put("steps", steps); cfg.put("mode", mode);
                o.put("cfg", cfg);
                JSONArray lp = new JSONArray();
                for (int i = 0; i < MAX; i++) if (i < nLots || pl[i] != 0) lp.put(pl[i]);
                o.put("lots", lp);
                o.put("log", new JSONArray(log.toString()));
                JSONArray ns = new JSONArray(); ns.put(o);
                for (int k = 0; k < sessions.length(); k++) ns.put(sessions.get(k));
                sessions = ns;
                prefs.edit().putString("sessions", sessions.toString()).apply();
            } catch (Exception ignored) {}
        }
        resetSession();
    }

    public String sessionsJson() { return sessions.toString(); }

    public void deleteSession(int index) {
        if (index < 0 || index >= sessions.length()) return;
        sessions.remove(index);
        prefs.edit().putString("sessions", sessions.toString()).apply();
        changed();
    }

    public void resetSession() {
        history.clear();
        log = new JSONArray();
        startedAt = 0; burned = 0; cuts = 0;
        step = new int[MAX]; pl = new double[MAX]; dead = new boolean[MAX];
        turn = 0; ops = 0; wins = 0; losses = 0;
        changed();
    }

    public void setConfig(double payout, double base, int steps, String mode) {
        this.payout = Math.max(1, Math.min(100, payout));
        this.base = Math.max(0.01, base);
        this.steps = Math.max(1, Math.min(8, steps));
        this.mode = mode;
        for (int i = 0; i < MAX; i++) if (step[i] >= this.steps) step[i] = this.steps - 1;
        changed();
    }

    private void pushHistory() {
        history.addLast(stateJson().toString());
        while (history.size() > 50) history.pollFirst();
    }

    private JSONObject stateJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray s = new JSONArray(), p = new JSONArray(), d = new JSONArray();
            for (int i = 0; i < MAX; i++) { s.put(step[i]); p.put(pl[i]); d.put(dead[i]); }
            o.put("nLots", nLots);
            o.put("step", s); o.put("pl", p); o.put("dead", d);
            o.put("turn", turn); o.put("ops", ops); o.put("wins", wins); o.put("losses", losses);
            o.put("log", new JSONArray(log.toString()));
            o.put("start", startedAt); o.put("burned", burned); o.put("cuts", cuts);
        } catch (Exception ignored) {}
        return o;
    }

    private void applyState(JSONObject o) throws Exception {
        JSONArray s = o.getJSONArray("step"), p = o.getJSONArray("pl"), d = o.getJSONArray("dead");
        step = new int[MAX]; pl = new double[MAX]; dead = new boolean[MAX];
        for (int i = 0; i < Math.min(MAX, s.length()); i++) { step[i] = s.getInt(i); pl[i] = p.getDouble(i); dead[i] = d.getBoolean(i); }
        nLots = Math.max(1, Math.min(MAX, o.optInt("nLots", nLots)));
        turn = o.getInt("turn"); ops = o.getInt("ops"); wins = o.getInt("wins"); losses = o.getInt("losses");
        JSONArray lg = o.optJSONArray("log");
        log = lg != null ? lg : new JSONArray();
        startedAt = o.optLong("start", 0); burned = o.optInt("burned", 0); cuts = o.optInt("cuts", 0);
    }

    private void save() {
        JSONObject o = stateJson();
        try {
            o.put("payout", payout); o.put("base", base); o.put("steps", steps); o.put("mode", mode);
        } catch (Exception ignored) {}
        prefs.edit().putString("state", o.toString()).apply();
    }

    private void load() {
        try { sessions = new JSONArray(prefs.getString("sessions", "[]")); } catch (Exception e) { sessions = new JSONArray(); }
        String raw = prefs.getString("state", null);
        if (raw == null) return;
        try {
            JSONObject o = new JSONObject(raw);
            payout = o.optDouble("payout", 92); base = o.optDouble("base", 1);
            steps = o.optInt("steps", 5); mode = o.optString("mode", "quick");
            applyState(o);
        } catch (Exception ignored) {}
    }
}
