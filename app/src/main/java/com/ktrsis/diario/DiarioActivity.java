package com.ktrsis.diario;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** El Diario ahora vive dentro de "Rotador + Diario": esta pantalla solo abre esa app en la pestaña Diario. */
public class DiarioActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = new Intent(this, com.ktrsis.rotador.MainActivity.class);
        String t = getIntent() != null ? getIntent().getStringExtra("tab") : null;
        i.putExtra("tab", t != null ? t : "diario");
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
        finish();
    }
}
