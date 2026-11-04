package com.ktrsis.trazador;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;


/**
 * El Trazador ahora vive dentro de la ventanita del Guardián (una sola grabación de pantalla,
 * una sola ventanita flotante). Esta pantalla solo deja el Trazador encendido y abre el Guardián.
 */
public class TrazadorActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getSharedPreferences("guardian", MODE_PRIVATE).edit().putInt("size", 2).apply();
        Intent i = new Intent(this, com.ktrsis.rotador.MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
        finish();
    }
}
