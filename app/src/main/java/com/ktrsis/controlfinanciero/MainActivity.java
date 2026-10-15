package com.ktrsis.controlfinanciero;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Pantalla única: la app de control semanal cargada en un WebView con almacenamiento local
 *  activado (DOM storage), para que localStorage funcione igual que en un navegador real
 *  y los datos persistan en el dispositivo entre aperturas.
 *
 *  Además: selector de archivos (para subir extractos CSV), puente "Android" para guardar
 *  archivos en Descargas y programar recordatorios de pagos, y botón atrás que primero
 *  navega dentro de la app. */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 42;
    private static final int REQ_NOTIF = 43;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF12161C);
        getWindow().setNavigationBarColor(0xFF161B22);

        web = new WebView(this);
        web.setBackgroundColor(0xFF12161C);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowContentAccess(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "Elige los extractos (CSV)"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        // Margen para no quedar debajo de la barra de hora/íconos ni la barra de navegación.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF12161C);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        int top = sysDimen("status_bar_height"), bottom = sysDimen("navigation_bar_height");
        root.setPadding(0, top, 0, bottom);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, Math.max(bars.top, top), bars.right, bars.bottom);
                return android.view.WindowInsets.CONSUMED;
            });
        }
        setContentView(root);

        // Android 13+: pedir permiso para mostrar los recordatorios de pagos.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        result = new Uri[n];
                        for (int k = 0; k < n; k++) result[k] = data.getClipData().getItemAt(k).getUri();
                    } else if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) { finish(); return; }
        // La app web decide: cierra lo abierto o vuelve a Inicio; si ya está en Inicio, se sale.
        web.evaluateJavascript("(window.appBack && appBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) finish();
        });
    }

    /** Funciones que la página puede llamar como Android.saveFile(...) y Android.scheduleReminders(...). */
    private class Bridge {
        /** Abre una de las subaplicaciones: "rotador", "bot" o "notas". */
        @JavascriptInterface
        public void openApp(String name) {
            runOnUiThread(() -> {
                Class<?> c = null;
                if ("rotador".equals(name)) c = com.ktrsis.rotador.MainActivity.class;
                else if ("bot".equals(name)) c = com.ktrsis.bot.MainActivity.class;
                else if ("notas".equals(name)) c = com.ktrsis.notas.NotesActivity.class;
                if (c != null) startActivity(new Intent(MainActivity.this, c));
            });
        }

        /** Datos ya formateados para el widget de la pantalla de inicio. */
        @JavascriptInterface
        public void updateWidget(String json) {
            try { WeekWidget.saveAndRefresh(MainActivity.this, json); } catch (Exception ignored) { }
        }

        /** Guarda un archivo de texto en Descargas. Devuelve dónde quedó, o "" si falló. */
        @JavascriptInterface
        public String saveFile(String name, String mime, String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "";
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return "";
                        os.write(bytes);
                    }
                    return "Descargas/" + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "";
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "";
            }
        }

        /** Recibe [{title, text, at}] y programa una notificación para cada uno (reemplaza las anteriores). */
        @JavascriptInterface
        public void scheduleReminders(String json) {
            try {
                Context ctx = MainActivity.this;
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am == null) return;
                SharedPreferences prefs = getSharedPreferences("reminders", MODE_PRIVATE);
                int prev = prefs.getInt("count", 0);
                for (int k = 0; k < prev; k++) {
                    PendingIntent old = PendingIntent.getBroadcast(ctx, 1000 + k,
                            new Intent(ctx, ReminderReceiver.class),
                            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                    if (old != null) { am.cancel(old); old.cancel(); }
                }
                JSONArray arr = new JSONArray(json);
                int n = Math.min(arr.length(), 60);
                long now = System.currentTimeMillis();
                for (int k = 0; k < n; k++) {
                    JSONObject o = arr.getJSONObject(k);
                    long at = o.getLong("at");
                    if (at <= now) continue;
                    Intent i = new Intent(ctx, ReminderReceiver.class);
                    i.putExtra("title", o.optString("title"));
                    i.putExtra("text", o.optString("text"));
                    i.putExtra("id", 1000 + k);
                    PendingIntent pi = PendingIntent.getBroadcast(ctx, 1000 + k, i,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
                prefs.edit().putInt("count", n).apply();
            } catch (Exception ignored) {
            }
        }
    }
}
