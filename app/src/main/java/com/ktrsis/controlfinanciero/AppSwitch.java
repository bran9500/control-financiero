package com.ktrsis.controlfinanciero;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;

/**
 * Botón ⇄ que está en todas las apps: salta directo a otra sin volver a Inicio.
 * Se registra en cada WebView como "AppSwitch" (assets/apps.js dibuja el botón).
 */
public class AppSwitch {
    /** Una pantalla con pestañas propias (Rotador + Diario) cambia de pestaña en vez de abrirse otra vez. */
    public interface Tabs { void showTab(String tab); }
    private final Activity act;
    public AppSwitch(Activity act) { this.act = act; }

    static Class<?> target(String name) {
        switch (name == null ? "" : name) {
            case "inicio": return MainActivity.class;
            case "rotador": return com.ktrsis.rotador.MainActivity.class;
            case "diario": return com.ktrsis.rotador.MainActivity.class;
            case "guardian": return com.ktrsis.guard.GuardActivity.class;
            case "bot": return com.ktrsis.bot.MainActivity.class;
            case "notas": return com.ktrsis.notas.NotesActivity.class;
            case "ingles": return com.ktrsis.ingles.EnglishActivity.class;
            case "interes": return com.ktrsis.interes.InterestActivity.class;
        }
        return null;
    }

    @JavascriptInterface public void open(String name) { open2(name, ""); }

    /** Abre una app; "tab" le dice a cuál pantalla ir (por ejemplo los Ajustes del Diario). */
    @JavascriptInterface public void open2(final String name, final String tab0) {
        act.runOnUiThread(() -> go(name, tab0));
    }

    public void go(String name, String tab0) {
        Class<?> c = target(name);
        if (c == null) return;
        String t = tab0 != null && !tab0.isEmpty() ? tab0 : ("diario".equals(name) ? "diario" : "rotador".equals(name) ? "rotador" : "");
        if (c == act.getClass()) { if (act instanceof Tabs && !t.isEmpty()) ((Tabs) act).showTab(t); return; }
        Intent i = new Intent(act, c);
        if ("inicio".equals(name)) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        else i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!t.isEmpty()) i.putExtra("tab", t);
        act.startActivity(i);
        act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
