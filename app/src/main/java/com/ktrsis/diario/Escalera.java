package com.ktrsis.diario;

import android.content.Context;
import android.content.SharedPreferences;

import com.ktrsis.rotador.Engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Escalones del día a partir de las operaciones del Rotador (misma regla que el Diario):
 * cada operación suma o resta; al juntar lo que vale el escalón se marca ✓ y se empieza de cero;
 * si se pierde el riesgo del escalón se marca ✕ y también se empieza de cero.
 */
public class Escalera {
    public int won, lost, cur, steps;
    public double acc, target, riskStep;
    public boolean ok;

    public static String todayKey() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Plan de hoy que guarda el Diario: {"date":"2026-11-02","target":20,"riskStep":20,"steps":9,"src":"rotador"}. */
    public static Escalera today(Context ctx) {
        Escalera e = new Escalera();
        SharedPreferences p = ctx.getSharedPreferences("diario", Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(p.getString("plan", "{}"));
            if (!todayKey().equals(o.optString("date")) || !"rotador".equals(o.optString("src"))) return e;
            e.target = o.optDouble("target", 0); e.riskStep = o.optDouble("riskStep", 0); e.steps = o.optInt("steps", 9);
            if (!(e.target > 0)) return e;
        } catch (Exception ex) { return e; }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long from = c.getTimeInMillis();
        JSONArray ops = Engine.get(ctx).opsBetween(from, from + 86_400_000L - 1);
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i); if (op == null || op.optBoolean("cut")) continue;
            e.acc += op.optDouble("v", 0);
            if (e.acc >= e.target - 0.005) { e.won++; e.acc = 0; }
            else if (e.riskStep > 0 && e.acc <= -e.riskStep + 0.005) { e.lost++; e.acc = 0; }
        }
        e.cur = e.won + e.lost;
        e.ok = true;
        return e;
    }

    public String line() {
        if (!ok) return "";
        int done = won + lost;
        String head = done >= steps ? "✓" + won + " ✕" + lost + " · listo" : "Esc " + (done + 1) + "/" + steps;
        return head + " · " + Engine.stake(Math.round(acc * 100) / 100.0) + "/" + Engine.stake(target);
    }
}
