package com.ktrsis.bot;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;

/** Pantalla principal: diagnóstico de la captura, señales completas, mercado, calibración y ajustes. */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 7;
    private Engine engine;
    private WebView web;
    private final Runnable refresh = () -> {
        if (web != null) web.post(() -> web.evaluateJavascript("window.onNative&&window.onNative()", null));
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = Engine.get(this);
        getWindow().setStatusBarColor(0xFF0E1116);
        getWindow().setNavigationBarColor(0xFF0E1116);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E1116);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/bot/index.html");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);
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

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    private int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onNativeBack&&window.onNativeBack()", r -> {
            if (!"true".equals(r)) MainActivity.super.onBackPressed();
        });
    }

    @Override protected void onResume() { super.onResume(); engine.addListener(refresh); refresh.run(); }
    @Override protected void onPause() { engine.removeListener(refresh); super.onPause(); }

    private void toggleOverlay() {
        if (CaptureService.running) {
            stopService(new Intent(this, CaptureService.class));
            web.postDelayed(refresh, 300);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) {
            engine.status = "Sin permiso de captura no se pueden leer las velas.";
            engine.notifyChanged();
            return;
        }
        Intent i = new Intent(this, CaptureService.class);
        i.putExtra(CaptureService.EXTRA_CODE, res);
        i.putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(i);
        moveTaskToBack(true);
    }

    /** Guarda el informe en Descargas y abre "Compartir" para mandarlo (por ejemplo a Claude). */
    private void exportSim() {
        String text = Sim.get(this).report();
        String name = "informe-bot-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(new java.util.Date()) + ".md";
        android.net.Uri uri = null;
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/markdown");
            if (Build.VERSION.SDK_INT >= 29) v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
            uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri != null) try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) { uri = null; }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, name);
        if (uri != null) { send.putExtra(Intent.EXTRA_STREAM, uri); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        else send.putExtra(Intent.EXTRA_TEXT, text.length() > 90000 ? text.substring(0, 90000) : text);
        android.widget.Toast.makeText(this, uri != null ? "Guardado en Descargas: " + name : "No se pudo guardar; se comparte como texto", android.widget.Toast.LENGTH_LONG).show();
        try { startActivity(Intent.createChooser(send, "Enviar informe")); } catch (Exception ignored) {}
    }

    private class Bridge {
        @JavascriptInterface public String getState() { return engine.stateJson(CaptureService.running); }
        @JavascriptInterface public String getImage(String which) { return engine.imageDataUri("full".equals(which) ? "full.jpg" : "crop.jpg"); }
        @JavascriptInterface public void toggleOverlay() { runOnUiThread(MainActivity.this::toggleOverlay); }
        @JavascriptInterface public void setTimeframe(int sec) { runOnUiThread(() -> engine.setTimeframe(sec)); }
        @JavascriptInterface public void setClockOffset(int sec) { runOnUiThread(() -> engine.setClockOffset(sec)); }
        @JavascriptInterface public void setAuto(boolean on) { runOnUiThread(() -> engine.setAuto(on)); }
        @JavascriptInterface public void setEnabled(String key, boolean on) { runOnUiThread(() -> engine.setEnabled(key, on)); }
        @JavascriptInterface public void setRegion(String orient, double l, double t, double r, double b) {
            runOnUiThread(() -> engine.setRegion(orient, (float) l, (float) t, (float) r, (float) b));
        }
        @JavascriptInterface public void clearLast() { runOnUiThread(engine::clearLast); }
        @JavascriptInterface public void goToTrading() { runOnUiThread(() -> moveTaskToBack(true)); }
        // ---- simulador Masaniello ----
        @JavascriptInterface public String getSim() { return Sim.get(MainActivity.this).json(); }
        @JavascriptInterface public void simConfig(String json) {
            try { Sim.get(MainActivity.this).config(new org.json.JSONObject(json)); } catch (Exception ignored) {}
            if (Sim.get(MainActivity.this).on && !engine.settings.auto) runOnUiThread(() -> engine.setAuto(true));  // necesita leer cada vela
        }
        @JavascriptInterface public void simReset() { Sim.get(MainActivity.this).reset(); }
        @JavascriptInterface public void simExport() { runOnUiThread(MainActivity.this::exportSim); }
    }
}
