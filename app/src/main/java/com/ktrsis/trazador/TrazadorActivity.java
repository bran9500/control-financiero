package com.ktrsis.trazador;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Pide los permisos (ventana flotante y captura de pantalla) y arranca el Trazador encima del broker. */
public class TrazadorActivity extends Activity {
    private static final int REQ_CAPTURE = 21;
    private boolean askedOverlay = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (TrazadorService.running) {
            Toast.makeText(this, "El Trazador ya está activo: abre el broker y usa la ventanita", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        begin();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (askedOverlay && Settings.canDrawOverlays(this)) { askedOverlay = false; begin(); }
    }

    private void begin() {
        if (!Settings.canDrawOverlays(this)) {
            askedOverlay = true;
            Toast.makeText(this, "Permite que Control Financiero se muestre encima de otras apps", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) { finish(); return; }
        Intent i = new Intent(this, TrazadorService.class);
        i.putExtra(TrazadorService.EXTRA_CODE, res);
        i.putExtra(TrazadorService.EXTRA_DATA, data);
        startForegroundService(i);
        Toast.makeText(this, "Listo. Abre el broker: en una temporalidad alta toca Zona; en la tuya, Trazar.", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
        finish();
    }
}
