package com.ktrsis.controlfinanciero;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import com.ktrsis.notas.NotesActivity;

import org.json.JSONObject;

/** Widget de la pantalla de inicio: lo más importante de la semana + accesos a las otras apps.
 *  La app web le pasa los números ya calculados (Android.updateWidget) cada vez que guarda. */
public class WeekWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(ctx));
    }

    static void saveAndRefresh(Context ctx, String json) {
        ctx.getSharedPreferences("widget", Context.MODE_PRIVATE).edit().putString("data", json).apply();
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, WeekWidget.class));
        for (int id : ids) m.updateAppWidget(id, build(ctx));
    }

    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_week);
        SharedPreferences p = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE);
        String json = p.getString("data", null);
        if (json == null) {
            v.setTextViewText(R.id.w_label, "Abre Control Financiero una vez");
            v.setTextViewText(R.id.w_main, "—");
        } else {
            try {
                JSONObject o = new JSONObject(json);
                boolean covered = o.optBoolean("covered");
                v.setTextViewText(R.id.w_label, covered ? "Vas cubierto esta semana" : "Te falta esta semana");
                v.setTextViewText(R.id.w_main, o.optString("falta", "—"));
                v.setTextColor(R.id.w_main, covered ? 0xFF6FA98A : 0xFFC4693B);
                v.setTextViewText(R.id.w_in, o.optString("entro", "—"));
                v.setTextViewText(R.id.w_out, o.optString("gastado", "—"));
                v.setTextViewText(R.id.w_bal, o.optString("saldo", "—"));
                v.setTextViewText(R.id.w_next, o.optString("proximo", ""));
                v.setTextViewText(R.id.w_updated, o.optString("actualizado", ""));
            } catch (Exception e) {
                v.setTextViewText(R.id.w_label, "Abre la app para actualizar");
            }
        }
        v.setOnClickPendingIntent(R.id.w_root, open(ctx, MainActivity.class, 11, false));
        v.setOnClickPendingIntent(R.id.w_btn_rot, open(ctx, com.ktrsis.rotador.MainActivity.class, 12, false));
        v.setOnClickPendingIntent(R.id.w_btn_bot, open(ctx, com.ktrsis.bot.MainActivity.class, 13, false));
        v.setOnClickPendingIntent(R.id.w_btn_notes, open(ctx, NotesActivity.class, 14, false));
        v.setOnClickPendingIntent(R.id.w_btn_dict, open(ctx, NotesActivity.class, 15, true));
        return v;
    }

    private static PendingIntent open(Context ctx, Class<?> c, int req, boolean dictate) {
        Intent i = new Intent(ctx, c);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (dictate) i.putExtra(NotesActivity.EXTRA_DICTATE, true);
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
