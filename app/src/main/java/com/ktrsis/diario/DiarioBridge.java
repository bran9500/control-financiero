package com.ktrsis.diario;

import android.app.Activity;
import android.content.Context;
import android.webkit.JavascriptInterface;

import com.ktrsis.rotador.Engine;

import org.json.JSONArray;
import org.json.JSONObject;

/** Puente de la pantalla del Diario (vive dentro de la app "Rotador + Diario"). */
public class DiarioBridge {
    public interface Host { void showTab(String tab); void goHome(); }
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
    }

    @JavascriptInterface public void openRotador() { act.runOnUiThread(() -> host.showTab("rotador")); }
}
