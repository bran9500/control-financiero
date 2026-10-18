package com.ktrsis.rotador;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/** Pantalla principal: la misma vista completa de la versión web, conectada a los datos de la ventana flotante. */
public class MainActivity extends Activity {
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
        web.loadUrl("file:///android_asset/rotador/index.html");
        // La página va dentro de un marco con margen arriba y abajo, para no quedar debajo
        // de la hora, los íconos ni la barra de navegación (Android 15 dibuja hasta el borde).
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
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
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent i = new Intent(this, OverlayService.class);
        if (OverlayService.running) {
            stopService(i);
            web.postDelayed(refresh, 300);
        } else {
            startForegroundService(i);
            moveTaskToBack(true);
        }
    }

    /** Lo que la página puede pedirle a la app. Todo pasa por el hilo principal. */
    private class Bridge {
        @JavascriptInterface public String getState() { return engine.webState(OverlayService.running); }
        @JavascriptInterface public boolean overlayRunning() { return OverlayService.running; }
        @JavascriptInterface public void result(boolean win) { runOnUiThread(() -> engine.result(win)); }
        @JavascriptInterface public void undo() { runOnUiThread(engine::undo); }
        @JavascriptInterface public void revive(int k) { runOnUiThread(() -> engine.reviveLot(k)); }
        @JavascriptInterface public void reset() { runOnUiThread(engine::closeSession); }
        @JavascriptInterface public String getSessions() { return engine.sessionsJson(); }
        @JavascriptInterface public String getGoal() { return engine.getGoal(); }
        @JavascriptInterface public void setGoal(String json) { runOnUiThread(() -> engine.setGoal(json)); }
        @JavascriptInterface public String getPlan() { return engine.getPlan(); }
        @JavascriptInterface public void setPlan(String json) { runOnUiThread(() -> engine.setPlan(json)); }
        @JavascriptInterface public void setLots(int n) { runOnUiThread(() -> engine.setLots(n)); }
        @JavascriptInterface public void cut() { runOnUiThread(engine::cut); }
        @JavascriptInterface public void setSessions(String json) { runOnUiThread(() -> engine.setSessions(json)); }
        @JavascriptInterface public void deleteSession(int i) { runOnUiThread(() -> engine.deleteSession(i)); }
        @JavascriptInterface public void setConfig(double payout, double base, double steps, String mode) {
            runOnUiThread(() -> engine.setConfig(payout, base, (int) Math.round(steps), mode));
        }
        @JavascriptInterface public void toggleOverlay() { runOnUiThread(MainActivity.this::toggleOverlay); }
    }
}
