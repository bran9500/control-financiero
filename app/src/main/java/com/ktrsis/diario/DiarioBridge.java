package com.ktrsis.diario;

import android.app.Activity;
import android.content.Context;
import android.webkit.JavascriptInterface;

import com.ktrsis.rotador.Engine;

import org.json.JSONArray;
import org.json.JSONObject;

/** Puente de la pantalla del Diario (vive dentro de la app "Rotador + Diario"). */
public class DiarioBridge {
    public interface Host { void showTab(String tab); void goHome(); void togglePanel(); }
    private final Activity act;
    private final Host host;

    public DiarioBridge(Activity act, Host host) { this.act = act; this.host = host; }

    @JavascriptInterface public void goHome() { act.runOnUiThread(host::goHome); }

    /** Operaciones del Rotador entre dos horas (ms). */
    @JavascriptInterface public String getRotadorOps(double from, double to) {
        return Engine.get(act).opsBetween((long) from, (long) to).toString();
    }

    /** Plan de hoy para que la ventanita del Rotador muestre el escalón. */
    @JavascriptInterface public void setPlan(String json) {
        act.getSharedPreferences("diario", Context.MODE_PRIVATE).edit().putString("plan", json).apply();
    }

    /** Ajustes del Rotador, para editarlos desde los Ajustes del Diario (un solo lugar de ajustes). */
    @JavascriptInterface public String getRotCfg() {
        Engine e = Engine.get(act);
        JSONObject o = new JSONObject();
        try {
            o.put("payout", e.payout); o.put("base", e.base); o.put("steps", e.steps); o.put("mode", e.mode);
            o.put("nLots", e.nLots); o.put("currency", Engine.curCode);
            JSONArray sq = new JSONArray(); for (double v : e.sequence()) sq.put(v);
            o.put("seq", sq); o.put("lotBudget", e.lotBudget());
        } catch (Exception ignored) {}
        return o.toString();
    }

    @JavascriptInterface public void setRotCfg(String json) {
        act.runOnUiThread(() -> {
            try {
                JSONObject o = new JSONObject(json);
                Engine e = Engine.get(act);
                e.setConfig(o.optDouble("payout", e.payout), o.optDouble("base", e.base), o.optInt("steps", e.steps), o.optString("mode", e.mode));
                if (o.has("nLots")) e.setLots(o.optInt("nLots", e.nLots));
                String cur = o.optString("currency", "");
                if (!cur.isEmpty() && !cur.equals(Engine.curCode)) e.setCurrency(cur);
            } catch (Exception ignored) {}
        });
    }

    /** Capital, riesgo y meta: los mismos para el Rotador. */
    @JavascriptInterface public void setRotPlan(String json) {
        act.runOnUiThread(() -> Engine.get(act).setPlan(json));
        // El Guardián usa el mismo riesgo y meta
        try {
            JSONObject o = new JSONObject(json);
            act.getSharedPreferences("guardian", Context.MODE_PRIVATE).edit()
                    .putFloat("risk", (float) o.optDouble("risk", 6)).putFloat("goal", (float) o.optDouble("goal", 9)).apply();
        } catch (Exception ignored) {}
    }

    @JavascriptInterface public void openRotador() { act.runOnUiThread(host::togglePanel); }

    // ---------- ventanita (panel de trading) ----------
    @JavascriptInterface public void togglePanel() { act.runOnUiThread(host::togglePanel); }

    /** Estado para la pantalla: si la ventanita está prendida, el lote de turno y el monto que sigue. */
    @JavascriptInterface public String panelState() {
        Engine e = Engine.get(act);
        JSONObject o = new JSONObject();
        try {
            o.put("running", com.ktrsis.guard.GuardService.running);
            double[] sq = e.sequence();
            o.put("turn", e.turn); o.put("seqLen", sq.length);
            o.put("stake", e.turn >= 0 ? sq[e.step[e.turn]] : 0);
            JSONArray lots = new JSONArray();
            for (int i = 0; i < e.nLots; i++) { JSONObject l = new JSONObject(); l.put("step", e.step[i]); l.put("dead", e.dead[i]); l.put("pl", e.pl[i]); lots.put(l); }
            o.put("lots", lots); o.put("net", e.net());
            o.put("balance", Double.isNaN(com.ktrsis.guard.GuardService.balance) ? JSONObject.NULL : com.ktrsis.guard.GuardService.balance);
        } catch (Exception ignored) {}
        return o.toString();
    }
    @JavascriptInterface public void reviveAll() { act.runOnUiThread(() -> Engine.get(act).reviveAll()); }
    @JavascriptInterface public void newSession() { act.runOnUiThread(() -> Engine.get(act).closeSession()); }
    @JavascriptInterface public void undo() { act.runOnUiThread(() -> Engine.get(act).undo()); }

    /** Ajustes de la ventanita (los que antes estaban en el Guardián). */
    @JavascriptInterface public String getGuard() {
        android.content.SharedPreferences g = act.getSharedPreferences("guardian", Context.MODE_PRIVATE);
        JSONObject o = new JSONObject();
        try {
            o.put("auto", g.getBoolean("auto", true)); o.put("expiry", g.getInt("expiry", 3));
            o.put("delay", g.getInt("delay", 6)); o.put("sound", g.getBoolean("sound", true));
        } catch (Exception ignored) {}
        return o.toString();
    }
    @JavascriptInterface public void setGuard(String json) {
        try {
            JSONObject o = new JSONObject(json);
            act.getSharedPreferences("guardian", Context.MODE_PRIVATE).edit()
                    .putBoolean("auto", o.optBoolean("auto", true)).putInt("expiry", Math.max(1, o.optInt("expiry", 3)))
                    .putInt("delay", Math.max(0, o.optInt("delay", 6))).putBoolean("sound", o.optBoolean("sound", true))
                    .putString("capMode", "auto").apply();
        } catch (Exception ignored) {}
    }
}
