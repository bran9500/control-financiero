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

    /** Operaciones de la sesión actual de hoy (lo que cuenta en Hoy). */
    @JavascriptInterface public String getSessionOps(double from) { return Engine.get(act).currentOps((long) from).toString(); }

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
            double cap = o.optDouble("cap", 0), risk = o.optDouble("risk", 6), goal = o.optDouble("goal", 9);
            // La ventanita usa los mismos montos que configuras: con $100 y 17% suena al perder $17.
            act.getSharedPreferences("guardian", Context.MODE_PRIVATE).edit()
                    .putFloat("risk", (float) risk).putFloat("goal", (float) goal)
                    .putFloat("riskAmt", (float) (cap > 0 ? cap * risk / 100.0 : 0)).putFloat("goalAmt", (float) (cap > 0 ? cap * goal / 100.0 : 0)).apply();
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
            double st = com.ktrsis.guard.GuardService.start;
            o.put("start", Double.isNaN(st) ? JSONObject.NULL : st);
            o.put("gstate", com.ktrsis.guard.GuardService.state);
        } catch (Exception ignored) {}
        return o.toString();
    }
    @JavascriptInterface public void reviveAll() { act.runOnUiThread(() -> Engine.get(act).reviveAll()); }
    @JavascriptInterface public void newSession() { act.runOnUiThread(() -> Engine.get(act).closeSession()); }
    @JavascriptInterface public void undo() { act.runOnUiThread(() -> Engine.get(act).undo()); }
    @JavascriptInterface public String sessions() { return Engine.get(act).sessionsJson(); }

    /** Reiniciar todo: lotes en cero (sin guardar) y el capital de inicio de la ventanita = saldo actual. */
    /** Borra todas las sesiones guardadas del Rotador. */
    @JavascriptInterface public void deleteAllSessions() { act.runOnUiThread(() -> Engine.get(act).setSessions("[]")); }

    @JavascriptInterface public void resetAll() {
        act.runOnUiThread(() -> { Engine.get(act).discardSession(); com.ktrsis.guard.GuardService.resetAll(); });
    }

    /**
     * Diagnóstico: junta lo que hace falta para entender un problema (ajustes, ventanita, Rotador, diario)
     * en un archivo en Descargas y abre "Compartir" para mandarlo.
     */
    @JavascriptInterface public String diagnostics(String pageJson) {
        try {
            Engine e = Engine.get(act);
            JSONObject o = new JSONObject();
            o.put("app", "Trading · Control Financiero");
            try { o.put("version", act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName); } catch (Exception ignored) {}
            o.put("fecha", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
            o.put("telefono", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE);
            o.put("ventanita", com.ktrsis.guard.GuardService.diag(act));
            JSONObject rot = new JSONObject(e.webState(com.ktrsis.guard.GuardService.running));
            JSONArray lg = rot.optJSONArray("log");
            if (lg != null && lg.length() > 120) { JSONArray cut = new JSONArray(); for (int i = 0; i < 120; i++) cut.put(lg.get(i)); rot.put("log", cut); }
            rot.put("sesionesGuardadas", new JSONArray(e.sessionsJson()).length());
            rot.put("plan", e.getPlan());
            o.put("rotador", rot);
            try { o.put("diario", new JSONObject(pageJson)); } catch (Exception x) { o.put("diario", pageJson); }
            String text = o.toString(2);
            String name = "diagnostico-trading-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(new java.util.Date()) + ".txt";
            android.net.Uri uri = null;
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
                uri = act.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) try (java.io.OutputStream os = act.getContentResolver().openOutputStream(uri)) { if (os != null) os.write(bytes); }
            }
            final android.net.Uri share = uri;
            act.runOnUiThread(() -> {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("text/plain");
                if (share != null) { i.putExtra(android.content.Intent.EXTRA_STREAM, share); i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                else i.putExtra(android.content.Intent.EXTRA_TEXT, text.length() > 90000 ? text.substring(0, 90000) : text);
                i.putExtra(android.content.Intent.EXTRA_SUBJECT, name);
                try { act.startActivity(android.content.Intent.createChooser(i, "Mandar diagnóstico")); } catch (Exception ignored) {}
            });
            return share != null ? "Descargas/" + name : "compartido como texto";
        } catch (Exception ex) { return ""; }
    }
    @JavascriptInterface public void restoreSession(int i) { act.runOnUiThread(() -> Engine.get(act).restoreSession(i)); }
    @JavascriptInterface public void deleteSession(int i) { act.runOnUiThread(() -> Engine.get(act).deleteSession(i)); }
    @JavascriptInterface public void discardSession() { act.runOnUiThread(() -> Engine.get(act).discardSession()); }
    @JavascriptInterface public String currentSession() {
        Engine e = Engine.get(act);
        JSONObject o = new JSONObject();
        try { o.put("ops", e.ops); o.put("w", e.wins); o.put("l", e.losses); o.put("net", e.net()); o.put("start", e.startedAt); o.put("burned", e.burned); } catch (Exception ignored) {}
        return o.toString();
    }

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
