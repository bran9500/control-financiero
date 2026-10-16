package com.ktrsis.ingles;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** Inglés: 6,000 palabras con repaso espaciado, voz en inglés y práctica de pronunciación. */
public class EnglishActivity extends Activity {
    private static final int REQ_SPEECH = 31;
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Locale EN = Locale.US;
    private Locale ES = new Locale("es", "US");

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
        web.loadUrl("file:///android_asset/ingles/index.html");

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

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(EN);
                tts.setSpeechRate(0.9f);
                int r = tts.isLanguageAvailable(ES);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) ES = new Locale("es");
                ttsReady = true;
            }
        });
    }

    private int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void startListening(String lang) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang == null || lang.isEmpty() ? "en-US" : lang);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say it in English…");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Este teléfono no tiene reconocimiento de voz (instala la app de Google).", Toast.LENGTH_LONG).show();
            send("");
        }
    }

    private void send(String text) {
        web.evaluateJavascript("window.onHeard&&onHeard(" + JSONObject.quote(text == null ? "" : text) + ")", null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SPEECH) return;
        String text = "";
        if (res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) text = r.get(0);
        }
        send(text);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.inglesBack&&inglesBack() ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    private void copyText(String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Práctica de inglés", text));
    }

    private class Bridge {
        @JavascriptInterface public void speak(String text, String lang) {
            runOnUiThread(() -> {
                if (!ttsReady) {
                    Toast.makeText(EnglishActivity.this, "La voz todavía se está cargando…", Toast.LENGTH_SHORT).show();
                    return;
                }
                tts.setLanguage("es".equals(lang) ? ES : EN);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w");
            });
        }
        @JavascriptInterface public void listen(String lang) { runOnUiThread(() -> startListening(lang)); }
        @JavascriptInterface public void share(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "Práctica de inglés");
                send.putExtra(Intent.EXTRA_TEXT, text);
                try { startActivity(Intent.createChooser(send, "Enviar a Claude")); }
                catch (Exception e) { copyText(text); }
            });
        }
        @JavascriptInterface public void copy(String text) { runOnUiThread(() -> copyText(text)); }
        @JavascriptInterface public void goHome() { runOnUiThread(EnglishActivity.this::finish); }
    }
}
