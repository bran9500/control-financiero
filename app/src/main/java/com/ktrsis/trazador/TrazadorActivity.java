package com.ktrsis.trazador;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.ktrsis.guard.GuardActivity;
import com.ktrsis.guard.GuardService;

/**
 * El Trazador ahora vive dentro de la ventanita del Guardián (una sola grabación de pantalla,
 * una sola ventanita flotante). Esta pantalla solo deja el Trazador encendido y abre el Guardián.
 */
public class TrazadorActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getSharedPreferences("guardian", MODE_PRIVATE).edit().putBoolean("traz", true).apply();
        if (GuardService.running) {
            Toast.makeText(this, "Ya está activo: usa Zona y Trazar en la ventanita del Guardián (botón 📐 para mostrarlos)", Toast.LENGTH_LONG).show();
        } else {
            Intent i = new Intent(this, GuardActivity.class);
            i.putExtra("autostart", true);
            startActivity(i);
        }
        finish();
    }
}
