package com.ktrsis.notas;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** Notas: escribir, dictar con la voz (reconocimiento de voz del teléfono) y escuchar la nota en voz alta. */
public class NotesActivity extends Activity {
    public static final String EXTRA_DICTATE = "dictar";
    private static final int REQ_SPEECH = 21;
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean dictateOnLoad = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF12161C);
        getWindow().setNavigationBarColor(0xFF12161C);
        dictateOnLoad = getIntent() != null && getIntent().getBooleanExtra(EXTRA_DICTATE, false);

        web = new WebView(this);
        web.setBackgroundColor(0xFF12161C);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (dictateOnLoad) {
                    dictateOnLoad = false;
                    view.evaluateJavascript("window.quickDictate&&quickDictate()", null);
                }
            }
        });
        web.loadUrl("file:///android_asset/notas/index.html");

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
                int r = tts.setLanguage(new Locale("es", "US"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(new Locale("es"));
                ttsReady = true;
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { done(); }
            @Override public void onError(String id) { done(); }
            private void done() { runOnUiThread(() -> web.evaluateJavascript("window.onSpeakDone&&onSpeakDone()", null)); }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_DICTATE, false) && web != null) {
            web.evaluateJavascript("window.quickDictate&&quickDictate()", null);
        }
    }

    private int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void startDictation() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-US");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla, te escucho…");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Este teléfono no tiene reconocimiento de voz (instala la app de Google).", Toast.LENGTH_LONG).show();
            sendDictation("");
        }
    }

    private void sendDictation(String text) {
        web.evaluateJavascript("window.onDictation&&onDictation(" + JSONObject.quote(text == null ? "" : text) + ")", null);
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
        sendDictation(text);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.notesBack&&notesBack() ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    private class Bridge {
        @JavascriptInterface public void dictate() { runOnUiThread(NotesActivity.this::startDictation); }
        @JavascriptInterface public void speak(String text) {
            runOnUiThread(() -> {
                if (!ttsReady) {
                    Toast.makeText(NotesActivity.this, "La voz todavía se está cargando, intenta en un segundo.", Toast.LENGTH_SHORT).show();
                    web.evaluateJavascript("window.onSpeakDone&&onSpeakDone()", null);
                    return;
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nota");
            });
        }
        @JavascriptInterface public void stopSpeaking() { runOnUiThread(() -> { if (tts != null) tts.stop(); }); }
        @JavascriptInterface public void goHome() { runOnUiThread(NotesActivity.this::finish); }
    }
}
