package com.ktrsis.diario;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/** Diario de trading: 9 escalones por día, martingala y estadísticas del mes. */
public class DiarioActivity extends Activity {
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF12161C);
        getWindow().setNavigationBarColor(0xFF12161C);

        web = new WebView(this);
        web.setBackgroundColor(0xFF12161C);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/diario/index.html");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF12161C);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        int top = sysDimen("status_bar_height"), bottom = sysDimen("navigation_bar_height");
        root.setPadding(0, top, 0, bottom);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout()
                        | android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, Math.max(bars.top, top), bars.right, bars.bottom);
                return android.view.WindowInsets.CONSUMED;
            });
        }
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.diarioResume&&diarioResume()", null);
    }

    private int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.diarioBack&&diarioBack() ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) finish();
        });
    }

    private class Bridge {
        @JavascriptInterface public void goHome() { runOnUiThread(DiarioActivity.this::finish); }
        /** Operaciones del Rotador entre dos horas (ms). */
        @JavascriptInterface public String getRotadorOps(double from, double to) {
            return com.ktrsis.rotador.Engine.get(DiarioActivity.this).opsBetween((long) from, (long) to).toString();
        }
        /** Plan de hoy para que la ventanita del Rotador muestre el escalón. */
        @JavascriptInterface public void setPlan(String json) {
            getSharedPreferences("diario", MODE_PRIVATE).edit().putString("plan", json).apply();
        }
        @JavascriptInterface public void openRotador() {
            runOnUiThread(() -> startActivity(new android.content.Intent(DiarioActivity.this, com.ktrsis.rotador.MainActivity.class)));
        }
    }
}
