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

/**
 * "Rotador + Diario": una sola app con dos pestañas arriba.
 *  - Rotador: la vista completa del Rotador, conectada a la ventana flotante.
 *  - Diario: escalones, historial, estadísticas y los ajustes de las dos.
 */
public class MainActivity extends Activity implements com.ktrsis.diario.DiarioBridge.Host, com.ktrsis.controlfinanciero.AppSwitch.Tabs {
    private Engine engine;
    private WebView web, diary;
    private android.widget.TextView tabRot, tabDia;
    private String shown = "rotador";
    private final Runnable refresh = () -> {
        if (web != null) web.post(() -> web.evaluateJavascript("window.onNative&&window.onNative()", null));
        if (diary != null) diary.post(() -> diary.evaluateJavascript("window.diarioResume&&diarioResume()", null));
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
        web.addJavascriptInterface(new com.ktrsis.controlfinanciero.AppSwitch(this), "AppSwitch");
        web.loadUrl("file:///android_asset/rotador/index.html");

        diary = new WebView(this);
        diary.setBackgroundColor(0xFF12161C);
        diary.getSettings().setJavaScriptEnabled(true);
        diary.getSettings().setDomStorageEnabled(true);
        diary.addJavascriptInterface(new com.ktrsis.diario.DiarioBridge(this, this), "Android");
        diary.addJavascriptInterface(new com.ktrsis.controlfinanciero.AppSwitch(this), "AppSwitch");
        diary.setWebViewClient(new android.webkit.WebViewClient());
        String tab0 = getIntent() != null ? getIntent().getStringExtra("tab") : null;
        diary.loadUrl("file:///android_asset/diario/index.html" + ("ajustes".equals(tab0) ? "#ajustes" : ""));

        // Pestañas arriba: Rotador | Diario
        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setBackgroundColor(0xFF0E1116);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        tabRot = tabButton("🔄  Rotador");
        tabDia = tabButton("📒  Diario");
        android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(0, dp(38), 1);
        tl.rightMargin = dp(6);
        bar.addView(tabRot, tl);
        bar.addView(tabDia, new android.widget.LinearLayout.LayoutParams(0, dp(38), 1));
        tabRot.setOnClickListener(v -> showTab("rotador"));
        tabDia.setOnClickListener(v -> showTab("diario"));

        android.widget.FrameLayout pages = new android.widget.FrameLayout(this);
        pages.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        pages.addView(diary, new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.addView(bar, new android.widget.LinearLayout.LayoutParams(-1, -2));
        col.addView(pages, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));

        // La página va dentro de un marco con margen arriba y abajo, para no quedar debajo
        // de la hora, los íconos ni la barra de navegación (Android 15 dibuja hasta el borde).
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);
        root.addView(col, new android.widget.FrameLayout.LayoutParams(-1, -1));
        showTab(tab0 != null ? tab0 : getSharedPreferences("rotador", MODE_PRIVATE).getString("tabShown", "rotador"));
        int top = sysDimen("status_bar_height"), bottom = sysDimen("navigation_bar_height");
        root.setPadding(0, top, 0, bottom);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, Math.max(bars.top, top), bars.right, bars.bottom);
                return android.view.WindowInsets.CONSUMED;
            });
        }
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private android.widget.TextView tabButton(String s) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(s); t.setGravity(android.view.Gravity.CENTER); t.setTextSize(14);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private android.graphics.drawable.GradientDrawable pill(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(10)); g.setStroke(dp(1), stroke); return g;
    }

    /** Cambia entre Rotador y Diario. "ajustes" abre el Diario en Ajustes (un solo lugar de ajustes). */
    @Override public void showTab(String tab) {
        if (tab == null) tab = "rotador";
        boolean dia = !"rotador".equals(tab);
        shown = dia ? "diario" : "rotador";
        web.setVisibility(dia ? android.view.View.GONE : android.view.View.VISIBLE);
        diary.setVisibility(dia ? android.view.View.VISIBLE : android.view.View.GONE);
        tabRot.setBackground(dia ? pill(0xFF161B22, 0xFF262C36) : pill(0xFF2A2412, 0xFFD9B45A));
        tabDia.setBackground(dia ? pill(0xFF2A2412, 0xFFD9B45A) : pill(0xFF161B22, 0xFF262C36));
        tabRot.setTextColor(dia ? 0xFF8B93A1 : 0xFFD9B45A);
        tabDia.setTextColor(dia ? 0xFFD9B45A : 0xFF8B93A1);
        getSharedPreferences("rotador", MODE_PRIVATE).edit().putString("tabShown", shown).apply();
        if (dia) {
            String t = "ajustes".equals(tab) ? "ajustes" : null;
            diary.evaluateJavascript("window.diarioResume&&diarioResume()" + (t != null ? ";window.diarioTab&&diarioTab('ajustes')" : ""), null);
        } else refresh.run();
    }

    @Override public void goHome() { finish(); }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String t = intent != null ? intent.getStringExtra("tab") : null;
        if (t != null) showTab(t);
    }

    private int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if ("diario".equals(shown)) {
            diary.evaluateJavascript("window.diarioBack&&diarioBack() ? 'y' : 'n'", v -> {
                if (v == null || !v.contains("y")) showTab("rotador");
            });
            return;
        }
        web.evaluateJavascript("window.onNativeBack&&window.onNativeBack()", r -> {
            if (!"true".equals(r)) MainActivity.super.onBackPressed();
        });
    }

    @Override protected void onResume() { super.onResume(); engine.addListener(refresh); refresh.run(); }
    @Override protected void onPause() { engine.removeListener(refresh); super.onPause(); }

    /** La ventanita flotante ahora es una sola: Rotador + Diario + Guardián + Trazador (lee el saldo y anota solo). */
    private void toggleOverlay() {
        if (OverlayService.running) stopService(new Intent(this, OverlayService.class));
        if (com.ktrsis.guard.GuardService.running) {
            stopService(new Intent(this, com.ktrsis.guard.GuardService.class));
            web.postDelayed(refresh, 300);
            return;
        }
        Intent g = new Intent(this, com.ktrsis.guard.GuardActivity.class);
        g.putExtra("autostart", true);
        startActivity(g);
    }

    private void toggleOldOverlay() {
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
        @JavascriptInterface public String getState() { return engine.webState(OverlayService.running || com.ktrsis.guard.GuardService.running); }
        @JavascriptInterface public boolean overlayRunning() { return OverlayService.running || com.ktrsis.guard.GuardService.running; }
        @JavascriptInterface public void result(boolean win) { runOnUiThread(() -> engine.result(win)); }
        @JavascriptInterface public void undo() { runOnUiThread(engine::undo); }
        @JavascriptInterface public void revive(int k) { runOnUiThread(() -> engine.reviveLot(k)); }
        @JavascriptInterface public void reset() { runOnUiThread(engine::closeSession); }
        @JavascriptInterface public String getSessions() { return engine.sessionsJson(); }
        @JavascriptInterface public String getGoal() { return engine.getGoal(); }
        @JavascriptInterface public void setGoal(String json) { runOnUiThread(() -> engine.setGoal(json)); }
        @JavascriptInterface public String getPlan() { return engine.getPlan(); }
        @JavascriptInterface public String getCurrency() { return Engine.curCode; }
        @JavascriptInterface public void setCurrency(String code) { runOnUiThread(() -> engine.setCurrency(code)); }
        /** Pasa capital, riesgo y meta al Guardián y lo activa (ventana aparte que lee el saldo real). */
        @JavascriptInterface public void floatPlan(String json) {
            runOnUiThread(() -> {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    android.content.SharedPreferences g = getSharedPreferences("guardian", MODE_PRIVATE);
                    double cap = o.optDouble("cap", 0);
                    g.edit().putFloat("risk", (float) o.optDouble("risk", 6)).putFloat("goal", (float) o.optDouble("goal", 9))
                            .putString("capMode", o.optBoolean("readStart", false) || !(cap > 0) ? "auto" : "manual")
                            .putFloat("capital", (float) cap).apply();
                } catch (Exception ignored) {}
                Intent i = new Intent(MainActivity.this, com.ktrsis.guard.GuardActivity.class);
                i.putExtra("autostart", true);
                startActivity(i);
            });
        }
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
