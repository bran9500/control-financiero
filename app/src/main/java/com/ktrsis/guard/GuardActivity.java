package com.ktrsis.guard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

/** Pantalla del Guardián: riesgo %, meta %, capital y botón para activarlo encima del broker. */
public class GuardActivity extends Activity {
    private static final int REQ_CAPTURE = 9;
    private WebView web;
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("guardian", MODE_PRIVATE);
        getWindow().setStatusBarColor(0xFF12161C);
        getWindow().setNavigationBarColor(0xFF12161C);
        web = new WebView(this);
        web.setBackgroundColor(0xFF12161C);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/guardian/index.html");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF12161C);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
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
        if (getIntent() != null && getIntent().getBooleanExtra("autostart", false) && !GuardService.running) startGuard();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("autostart", false) && !GuardService.running) startGuard();
    }

    private int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void startGuard() {
        if (GuardService.running) { stopService(new Intent(this, GuardService.class)); return; }
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
        if (req != REQ_CAPTURE || res != RESULT_OK || data == null) return;
        Intent i = new Intent(this, GuardService.class);
        i.putExtra(GuardService.EXTRA_CODE, res);
        i.putExtra(GuardService.EXTRA_DATA, data);
        startForegroundService(i);
        moveTaskToBack(true);   // vuelve al broker
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { finish(); }

    private class Bridge {
        @JavascriptInterface public String getState() {
            JSONObject o = new JSONObject();
            try {
                o.put("running", GuardService.running);
                o.put("balance", Double.isNaN(GuardService.balance) ? JSONObject.NULL : GuardService.balance);
                o.put("start", Double.isNaN(GuardService.start) ? JSONObject.NULL : GuardService.start);
                o.put("lastRead", GuardService.lastRead); o.put("status", GuardService.status);
                o.put("state", GuardService.state); o.put("lastReadAt", GuardService.lastReadAt);
                o.put("risk", prefs.getFloat("risk", 6)); o.put("goal", prefs.getFloat("goal", 9));
                o.put("capMode", prefs.getString("capMode", "auto")); o.put("capital", prefs.getFloat("capital", 0));
                o.put("delay", prefs.getInt("delay", 6)); o.put("sound", prefs.getBoolean("sound", true));
                o.put("overlayOk", Settings.canDrawOverlays(GuardActivity.this));
            } catch (Exception ignored) {}
            return o.toString();
        }
        @JavascriptInterface public void save(String json) {
            try {
                JSONObject o = new JSONObject(json);
                prefs.edit().putFloat("risk", (float) o.optDouble("risk", 6)).putFloat("goal", (float) o.optDouble("goal", 9))
                        .putString("capMode", o.optString("capMode", "auto")).putFloat("capital", (float) o.optDouble("capital", 0))
                        .putInt("delay", o.optInt("delay", 6)).putBoolean("sound", o.optBoolean("sound", true)).apply();
            } catch (Exception ignored) {}
        }
        @JavascriptInterface public String getCurrency() { com.ktrsis.rotador.Engine.get(GuardActivity.this); return com.ktrsis.rotador.Engine.curCode; }
        @JavascriptInterface public void toggle() { runOnUiThread(GuardActivity.this::startGuard); }
        @JavascriptInterface public void goHome() { runOnUiThread(GuardActivity.this::finish); }
    }
}
