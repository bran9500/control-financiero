package com.ktrsis.controlfinanciero;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Muestra el recordatorio de un pago (programado desde la app con Android.scheduleReminders). */
public class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL = "pagos";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Recordatorios de pagos",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Avisos antes de que venza una deuda o un gasto fijo");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(intent.getStringExtra("title"))
                .setContentText(intent.getStringExtra("text"))
                .setContentIntent(pi)
                .setAutoCancel(true);
        nm.notify(intent.getIntExtra("id", 1000), b.build());
    }
}
