package com.ktrsis.rotador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Ventana flotante encima de otras apps (Pocket Option). */
public class OverlayService extends Service {
    static final int BG = 0xF20E1116, PANEL = 0xFF171B22, LINE = 0xFF262C36, FG = 0xFFE8EBF0,
            MUTED = 0xFF8B93A1, WIN = 0xFF2FC47A, LOSS = 0xFFEF5A5A, ACCENT = 0xFFD9B45A, DEAD = 0xFF4A505B, GOLD_DIM = 0x66D9B45A;

    public static boolean running = false;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout panel;
    private TextView bubble;
    private TextView who, stake, cutBtn, sessTxt, planTxt, planLbl;
    private PlanBar planBar;
    private LinearLayout planBox;
    private int planState = 0;   // 0 en juego, 1 meta cumplida, -1 riesgo tocado
    private final TextView[] lotTxt = new TextView[Engine.MAX];
    private final View[] lotBar = new View[Engine.MAX];
    private final View[] lotCell = new View[Engine.MAX];
    private LinearLayout lotsRow2;
    private long lastPress = 0;
    private boolean cutArmed = false;
    private TextView winBtn, lossBtn;
    private boolean minimized = false;
    private Engine engine;
    private final Runnable refresh = this::render;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        engine = Engine.get(this);
        startInForeground();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = getSharedPreferences("rotador", MODE_PRIVATE).getInt("x", dp(8));
        lp.y = getSharedPreferences("rotador", MODE_PRIVATE).getInt("y", dp(120));

        buildPanel();
        buildBubble();
        wm.addView(panel, lp);
        engine.addListener(refresh);
        render();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        running = false;
        engine.removeListener(refresh);
        try { wm.removeView(minimized ? bubble : panel); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("flotante", "Ventana flotante", NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "flotante")
                .setContentTitle("Rotador activo")
                .setContentText("Toca para abrir los ajustes")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ---------- UI ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return t;
    }

    private TextView button(String label, int bg, int fg, float sp, int padV) {
        TextView b = text(label, sp, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(bg, 8, 0));
        b.setPadding(dp(4), dp(padV), dp(4), dp(padV));
        b.setAllCaps(true);
        return b;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(BG, 12, GOLD_DIM));
        panel.setPadding(dp(6), dp(4), dp(6), dp(6));
        int w = dp(112);

        // Barra superior: arrastrar + minimizar + cerrar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView grip = text("⠿", 14, ACCENT, false);
        TextView expand = text("⤢", 17, MUTED, true);
        TextView min = text("–", 18, MUTED, true);
        TextView close = text("×", 18, MUTED, true);
        expand.setPadding(dp(4), 0, dp(6), 0);
        min.setPadding(dp(6), 0, dp(6), 0);
        close.setPadding(dp(6), 0, dp(2), 0);
        LinearLayout.LayoutParams growLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        top.addView(grip, growLp);
        top.addView(expand);
        top.addView(min);
        top.addView(close);
        panel.addView(top, new LinearLayout.LayoutParams(w, dp(24)));

        who = text("", 11, MUTED, false);
        who.setLetterSpacing(0.05f);
        panel.addView(who);

        // Fila de lotes: el lote en juego va en dorado con una línea debajo
        LinearLayout lotsRow = new LinearLayout(this);
        lotsRow.setOrientation(LinearLayout.HORIZONTAL);
        lotsRow2 = new LinearLayout(this);
        lotsRow2.setOrientation(LinearLayout.HORIZONTAL);
        for (int k = 0; k < Engine.MAX; k++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            lotTxt[k] = text("", 11, MUTED, false);
            lotTxt[k].setGravity(Gravity.CENTER);
            lotBar[k] = new View(this);
            cell.addView(lotTxt[k]);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
            barLp.topMargin = dp(1);
            barLp.leftMargin = dp(3); barLp.rightMargin = dp(3);
            cell.addView(lotBar[k], barLp);
            lotCell[k] = cell;
            (k < 3 ? lotsRow : lotsRow2).addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(3);
        panel.addView(lotsRow, rowLp);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(2);
        panel.addView(lotsRow2, row2Lp);

        stake = text("", 34, FG, true);
        stake.setIncludeFontPadding(false);
        panel.addView(stake);

        // Operaciones y resultado de la sesión, para saber cuándo cerrarla
        sessTxt = text("", 11, MUTED, false);
        sessTxt.setTypeface(Typeface.MONOSPACE);
        panel.addView(sessTxt);

        // Riesgo y meta del día: barra con el riesgo a la izquierda y la meta a la derecha
        planBox = new LinearLayout(this);
        planBox.setOrientation(LinearLayout.VERTICAL);
        planBox.setBackground(rounded(PANEL, 8, LINE));
        planBox.setPadding(dp(5), dp(4), dp(5), dp(5));
        planLbl = text("", 10, MUTED, false);
        planLbl.setTypeface(Typeface.MONOSPACE);
        planBar = new PlanBar(this);
        planTxt = text("", 10.5f, FG, true);
        planBox.addView(planLbl);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        pbLp.topMargin = dp(3); pbLp.bottomMargin = dp(3);
        planBox.addView(planBar, pbLp);
        planBox.addView(planTxt);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = dp(5);
        // (el riesgo y la meta ahora flotan aparte con el Guardián)

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        winBtn = button("Ganó", WIN, 0xFF0E1116, 17, 11);
        lossBtn = button("Perdió", LOSS, 0xFF0E1116, 17, 11);
        panel.addView(winBtn, blp);
        panel.addView(lossBtn, new LinearLayout.LayoutParams(blp));
        cutBtn = button("Cortar ciclo", PANEL, ACCENT, 10, 6);
        cutBtn.setBackground(rounded(PANEL, 8, GOLD_DIM));
        panel.addView(cutBtn, new LinearLayout.LayoutParams(blp));

        winBtn.setOnClickListener(v -> {
            if (!acceptPress(v)) return;
            if (engine.turn < 0) engine.reviveAll(); else { engine.result(true); flash(WIN); }
        });
        lossBtn.setOnClickListener(v -> {
            if (engine.turn < 0 || !acceptPress(v)) return;
            engine.result(false); flash(LOSS);
        });
        // Cortar pide dos toques para evitar cortes por error
        cutBtn.setOnClickListener(v -> {
            if (!cutArmed) {
                cutArmed = true;
                cutBtn.setText("¿Cortar? Toca otra vez");
                cutBtn.setBackground(rounded(ACCENT, 8, 0));
                cutBtn.setTextColor(0xFF0E1116);
                cutBtn.postDelayed(() -> { if (cutArmed) { cutArmed = false; render(); } }, 2500);
                return;
            }
            cutArmed = false;
            engine.cut();
            flash(ACCENT);
        });
        expand.setOnClickListener(v -> {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(open);
        });
        min.setOnClickListener(v -> setMinimized(true));
        close.setOnClickListener(v -> stopSelf());

        View.OnTouchListener drag = new DragListener(null);
        grip.setOnTouchListener(drag);
        who.setOnTouchListener(drag);
        stake.setOnTouchListener(drag);
    }

    /** Ignora un segundo toque muy seguido (evita saltarse un lote por doble toque) y vibra al confirmar. */
    private boolean acceptPress(View v) {
        long now = System.currentTimeMillis();
        if (now - lastPress < 700) return false;
        lastPress = now;
        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        return true;
    }

    /** Destello del borde para confirmar que el toque se registró. */
    private void flash(int color) {
        panel.setBackground(rounded(BG, 12, 0));
        ((GradientDrawable) panel.getBackground()).setStroke(dp(2), color);
        panel.postDelayed(() -> panel.setBackground(rounded(BG, 12, GOLD_DIM)), 450);
    }

    private void buildBubble() {
        bubble = text("", 13, 0xFF0E1116, true);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ACCENT);
        g.setStroke(dp(2), 0xFFA8842F);
        bubble.setBackground(g);
        bubble.setWidth(dp(52));
        bubble.setHeight(dp(52));
        bubble.setOnTouchListener(new DragListener(() -> setMinimized(false)));
    }

    private void setMinimized(boolean m) {
        if (m == minimized) return;
        wm.removeView(minimized ? bubble : panel);
        minimized = m;
        wm.addView(minimized ? bubble : panel, lp);
        render();
    }

    private void render() {
        double[] sq = engine.sequence();
        for (int k = 0; k < Engine.MAX; k++) lotCell[k].setVisibility(k < engine.nLots ? View.VISIBLE : (k < 3 ? View.INVISIBLE : View.GONE));
        lotsRow2.setVisibility(engine.nLots > 3 ? View.VISIBLE : View.GONE);
        double net = engine.net();
        sessTxt.setText(String.format(Locale.US, "%d ops · %s", engine.ops, Engine.money(net)));
        sessTxt.setTextColor(net > 0 ? WIN : net < 0 ? LOSS : MUTED);
        // renderPlan(net);
        int i = engine.turn;
        if (i < 0) {
            who.setText("QUEMADOS");
            who.setTextColor(LOSS);
            for (int k = 0; k < engine.nLots; k++) { lotTxt[k].setText("L" + (k + 1) + " ✕"); lotTxt[k].setTextColor(LOSS); lotBar[k].setBackgroundColor(0); }
            cutBtn.setVisibility(View.GONE);
            stake.setText(Engine.curSym + "0");
            winBtn.setText("Reactivar");
            lossBtn.setBackground(rounded(DEAD, 8, 0));
            bubble.setText("✕");
        } else {
            int s = engine.step[i];
            who.setTextColor(MUTED);
            who.setText(String.format(Locale.US, "LOTE %d · P%d/%d", i + 1, s + 1, sq.length));
            for (int k = 0; k < engine.nLots; k++) {
                boolean on = k == i;
                lotTxt[k].setText("L" + (k + 1) + ":" + (engine.dead[k] ? "✕" : String.valueOf(engine.step[k] + 1)));
                lotTxt[k].setTextColor(on ? ACCENT : engine.dead[k] ? LOSS : MUTED);
                lotTxt[k].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                lotBar[k].setBackground(on ? rounded(ACCENT, 2, 0) : null);
            }
            cutBtn.setVisibility(s > 0 ? View.VISIBLE : View.GONE);
            if (!cutArmed) {
                cutBtn.setText("Cortar ciclo");
                cutBtn.setBackground(rounded(PANEL, 8, GOLD_DIM));
                cutBtn.setTextColor(ACCENT);
            }
            stake.setText(fmtStake(sq[s]));
            stake.setTextColor(s == sq.length - 1 ? LOSS : FG);
            winBtn.setText("Ganó");
            lossBtn.setBackground(rounded(LOSS, 8, 0));
            bubble.setText(fmtStake(sq[s]));
        }
    }

    private void renderPlan(double net) {
        double[] p = engine.plan();
        if (p == null) { planBox.setVisibility(View.GONE); planState = 0; return; }
        planBox.setVisibility(View.VISIBLE);
        double risk = p[1], goal = p[2];
        planLbl.setText("−" + fmtStake(risk) + "  ·  +" + fmtStake(goal));
        planBar.set(net, risk, goal);
        int st = goal > 0 && net >= goal - 1e-9 ? 1 : risk > 0 && net <= -risk + 1e-9 ? -1 : 0;
        if (st == 1) { planTxt.setText("✓ META · CIERRA"); planTxt.setTextColor(WIN); }
        else if (st == -1) { planTxt.setText("✕ RIESGO · PARA"); planTxt.setTextColor(LOSS); }
        else if (net >= 0) { planTxt.setText("Faltan " + fmtStake(Engine.r2(goal - net))); planTxt.setTextColor(FG); }
        else { planTxt.setText("Margen " + fmtStake(Engine.r2(risk + net))); planTxt.setTextColor(ACCENT); }
        planBox.setBackground(rounded(st == 1 ? 0x332FC47A : st == -1 ? 0x33EF5A5A : PANEL, 8, st == 1 ? WIN : st == -1 ? LOSS : LINE));
        if (st != 0 && st != planState) {
            // aviso una sola vez al llegar: vibración y destello
            flash(st == 1 ? WIN : LOSS);
            try {
                android.os.Vibrator vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vib != null) vib.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 180, 120, 180, 120, 380}, -1));
            } catch (Exception ignored) {}
        }
        planState = st;
    }

    /** Barra de dos lados: rojo hacia la izquierda (riesgo usado), verde hacia la derecha (meta). */
    private class PlanBar extends View {
        private double net, risk, goal;
        private final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        PlanBar(android.content.Context c) { super(c); }
        void set(double n, double r, double g) { net = n; risk = r; goal = g; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float W = getWidth(), H = getHeight(), rad = H / 2f;
            double tot = Math.max(0.01, risk + goal);
            float zero = (float) (W * risk / tot);
            pt.setColor(LINE); c.drawRoundRect(0, 0, W, H, rad, rad, pt);
            if (net < 0 && risk > 0) {
                float x = (float) Math.max(0, zero - zero * Math.min(1, -net / risk));
                pt.setColor(LOSS); c.drawRoundRect(x, 0, zero, H, rad, rad, pt);
            } else if (net > 0 && goal > 0) {
                float x = (float) Math.min(W, zero + (W - zero) * Math.min(1, net / goal));
                pt.setColor(WIN); c.drawRoundRect(zero, 0, x, H, rad, rad, pt);
            }
            pt.setColor(FG); c.drawRect(zero - dp(1), -1, zero + dp(1), H + 1, pt);
        }
    }

    private static String fmtStake(double v) { return Engine.stake(v); }

    /** Arrastra la ventana; si casi no se movió, cuenta como toque. */
    private class DragListener implements View.OnTouchListener {
        private final Runnable onTap;
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        DragListener(Runnable onTap) { this.onTap = onTap; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x; startY = lp.y;
                    downX = e.getRawX(); downY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        wm.updateViewLayout(minimized ? bubble : panel, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && onTap != null) onTap.run();
                    if (moved) getSharedPreferences("rotador", MODE_PRIVATE).edit()
                            .putInt("x", lp.x).putInt("y", lp.y).apply();
                    return true;
            }
            return false;
        }
    }
}
