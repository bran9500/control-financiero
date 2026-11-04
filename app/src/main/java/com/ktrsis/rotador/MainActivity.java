package com.ktrsis.rotador;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.WebView;
import android.widget.Toast;

import com.ktrsis.guard.GuardService;

/**
 * "Trading": una sola app con Hoy · Historial · Ajustes.
 * El botón "Empezar a operar" abre la ventanita flotante (Rotador + Diario + Guardián + Trazador),
 * que lee el saldo del broker y anota sola las operaciones.
 */
public class MainActivity extends Activity implements com.ktrsis.diario.DiarioBridge.Host, com.ktrsis.controlfinanciero.AppSwitch.Tabs {
    private static final int REQ_CAPTURE = 31;
    private Engine engine;
    private WebView web;
    private boolean askedOverlay = false;
    private final Runnable refresh = () -> {
        if (web != null) web.post(() -> web.evaluateJavascript("window.diarioResume&&diarioResume()", null));
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = Engine.get(this);
        getWindow().setStatusBarColor(0xFF12161C);
        getWindow().setNavigationBarColor(0xFF12161C);

        web = new WebView(this);
        web.setBackgroundColor(0xFF12161C);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new com.ktrsis.diario.DiarioBridge(this, this), "Android");
        web.addJavascriptInterface(new com.ktrsis.controlfinanciero.AppSwitch(this), "AppSwitch");
        web.setWebViewClient(new android.webkit.WebViewClient());
        String tab0 = getIntent() != null ? getIntent().getStringExtra("tab") : null;
        web.loadUrl("file:///android_asset/diario/index.html" + ("ajustes".equals(tab0) ? "#ajustes" : ""));

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF12161C);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
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

    private int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override public void showTab(String tab) {
        String t = "ajustes".equals(tab) ? "ajustes" : "hist".equals(tab) ? "hist" : "hoy";
        web.evaluateJavascript("window.diarioTab&&diarioTab('" + t + "')", null);
    }
    @Override public void goHome() { finish(); }

    /** Prende o apaga la ventanita flotante (pide los permisos la primera vez). */
    @Override public void togglePanel() {
        if (OverlayService.running) stopService(new Intent(this, OverlayService.class));
        if (GuardService.running) { stopService(new Intent(this, GuardService.class)); web.postDelayed(refresh, 300); return; }
        if (!Settings.canDrawOverlays(this)) {
            askedOverlay = true;
            Toast.makeText(this, "Permite que la app se muestre encima de otras apps", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE || res != RESULT_OK || data == null) return;
        Intent i = new Intent(this, GuardService.class);
        i.putExtra(GuardService.EXTRA_CODE, res);
        i.putExtra(GuardService.EXTRA_DATA, data);
        startForegroundService(i);
        moveTaskToBack(true);   // vuelve al broker
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String t = intent != null ? intent.getStringExtra("tab") : null;
        if (t != null) showTab(t);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.diarioBack&&diarioBack() ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) finish();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        engine.addListener(refresh); refresh.run();
        if (askedOverlay && Settings.canDrawOverlays(this)) { askedOverlay = false; togglePanel(); }
    }
    @Override protected void onPause() { engine.removeListener(refresh); super.onPause(); }
}
