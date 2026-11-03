package com.ktrsis.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Anota solas las operaciones mirando cómo cambia el saldo:
 *  - el saldo BAJA $X  → se abrió una operación de $X (se asigna a un lote);
 *  - el saldo SUBE ≈ $X × (1 + payout) → esa operación se ganó;
 *  - el saldo SUBE ≈ $X → empate / devuelta (no cuenta);
 *  - pasó la expiración y no subió → se perdió.
 * Dos operaciones del mismo monto a la vez se reparten: una al lote de turno y otra al siguiente que
 * espera ese monto. Un monto que no corresponde a ningún lote se anota "fuera del plan" (no mueve los lotes).
 */
public class AutoTrades {
    /** Lo que AutoTrades necesita saber de los lotes (lo da el Rotador). */
    public interface Book {
        int turn();                       // lote de turno (-1 si todos quemados)
        int lots();                       // lotes activos
        boolean alive(int lot);
        double stakeOf(int lot);          // monto que le toca a ese lote ahora
        void apply(int lot, boolean win); // anota el resultado en ese lote (y rota)
        void extra(double stake, boolean win, double value); // operación fuera del plan
        void setTurn(int lot);            // a qué lote le toca después
    }

    public static final class Open {
        public final double amt; public final int lot; public final long at;
        Open(double amt, int lot, long at) { this.amt = amt; this.lot = lot; this.at = at; }
        public boolean extra() { return lot < 0; }
    }

    private final Book book;
    private final List<Open> open = new ArrayList<>();
    public long expiryMs = 3000;
    /** Margen después de la expiración antes de dar una operación por perdida. */
    public long graceMs = 3500;
    public String last = "";          // lo último que anotó, para mostrarlo en la ventanita
    public int count = 0;             // operaciones anotadas solas
    private int resumeAt = -1;        // tras repartir iguales: el lote que naturalmente seguía

    public AutoTrades(Book book) { this.book = book; }

    public List<Open> pending() { return open; }

    private static boolean near(double a, double b) { return Math.abs(a - b) <= Math.max(0.02, b * 0.02); }
    private static String m(double v) { return String.format(Locale.US, "$%.2f", v).replace(".00", ""); }

    private boolean busy(int lot) { for (Open o : open) if (o.lot == lot) return true; return false; }

    /** Siguiente lote vivo después de "from" que no tenga una operación abierta. */
    private int nextFree(int from) {
        int n = book.lots();
        for (int k = 1; k <= n; k++) {
            int i = ((from + k) % n + n) % n;
            if (book.alive(i) && !busy(i)) return i;
        }
        return -1;
    }

    /** Lote que va de turno teniendo en cuenta las operaciones que siguen abiertas. */
    private int virtualTurn() {
        int t = book.turn();
        if (t < 0) return -1;
        if (!busy(t)) return t;
        int lastLot = t;
        for (Open o : open) if (!o.extra()) lastLot = o.lot;
        return nextFree(lastLot);
    }

    /** Llamar cada vez que el saldo confirmado cambia. */
    public void onBalance(double prev, double now, long t) {
        double d = Math.round((now - prev) * 100) / 100.0;
        if (Math.abs(d) < 0.005) return;
        if (d < 0) opened(-d, t); else returned(d, t);
    }

    private void opened(double amt, long t) {
        int v = virtualTurn();
        // 1) justo el monto del lote de turno
        if (v >= 0 && near(amt, book.stakeOf(v))) { open.add(new Open(amt, v, t)); last = "Abrió L" + (v + 1) + " " + m(amt); return; }
        // 2) varias operaciones iguales a la vez: una al lote de turno y las demás a los siguientes que esperan ese mismo monto
        if (v >= 0) {
            double s = book.stakeOf(v);
            int k = (int) Math.round(amt / s);
            if (k >= 2 && near(amt, s * k)) {
                List<Integer> lots = new ArrayList<>();
                lots.add(v);
                int from = v;
                for (int n = 0; n < book.lots() && lots.size() < k; n++) {
                    int c = nextFree(from);
                    if (c < 0 || lots.contains(c)) break;
                    from = c;
                    if (near(book.stakeOf(c), s)) lots.add(c);
                }
                if (lots.size() == k) {
                    // El turno vuelve al lote que seguía después del de turno (los que se saltaron no pierden su turno).
                    int nat = -1;
                    for (int q = 1; q <= book.lots(); q++) { int c = (v + q) % book.lots(); if (book.alive(c) && !lots.contains(c)) { nat = c; break; } }
                    resumeAt = nat;
                    StringBuilder sb = new StringBuilder("Abrió " + k + " iguales: ");
                    for (int c : lots) { open.add(new Open(s, c, t)); sb.append("L").append(c + 1).append(' '); }
                    last = sb.toString().trim() + " · " + m(s) + " c/u";
                    return;
                }
            }
        }
        // 3) el monto que espera otro lote
        for (int i = 0; i < book.lots(); i++) {
            if (book.alive(i) && !busy(i) && near(amt, book.stakeOf(i))) { open.add(new Open(amt, i, t)); last = "Abrió L" + (i + 1) + " " + m(amt); return; }
        }
        // 4) fuera del plan
        open.add(new Open(amt, -1, t));
        last = "Fuera del plan: " + m(amt);
    }

    private void returned(double d, long t) {
        // Antes de mirar la ganancia, las que ya vencieron sin pago se dan por perdidas.
        expire(t, true);
        if (open.isEmpty()) { last = "El saldo subió " + m(d) + " (sin operación abierta)"; return; }
        // Una operación: ganada si devuelve entre 1.4 y 2.05 veces lo apostado; empate si devuelve lo mismo.
        Open best = null; long bestGap = Long.MAX_VALUE;
        for (Open o : open) {
            if (d >= o.amt * 1.4 - 0.01 && d <= o.amt * 2.05 + 0.01) {
                long gap = Math.abs(t - (o.at + expiryMs));
                if (gap < bestGap) { bestGap = gap; best = o; }
            }
        }
        if (best != null) { close(best, true, Math.round((d - best.amt) * 100) / 100.0); return; }
        for (Open o : open) if (near(d, o.amt)) { open.remove(o); last = "Empate / devuelta " + m(o.amt); return; }
        // Dos operaciones que pagaron juntas
        for (int i = 0; i < open.size(); i++) for (int j = i + 1; j < open.size(); j++) {
            Open a = open.get(i), b = open.get(j);
            double s = a.amt + b.amt;
            if (d >= s * 1.4 - 0.01 && d <= s * 2.05 + 0.01) {
                double r = (d - s) / s;
                close(a, true, Math.round(a.amt * r * 100) / 100.0);
                close(b, true, Math.round(b.amt * r * 100) / 100.0);
                return;
            }
        }
        last = "El saldo subió " + m(d) + " y no sé de qué operación";
    }

    private void close(Open o, boolean win, double value) {
        open.remove(o);
        count++;
        if (o.extra()) { book.extra(o.amt, win, win ? value : -o.amt); last = (win ? "✓ Ganó " : "✕ Perdió ") + m(o.amt) + " (fuera del plan)"; }
        else { book.apply(o.lot, win); last = (win ? "✓ Ganó L" : "✕ Perdió L") + (o.lot + 1) + " " + m(o.amt); }
        boolean planOpen = false;
        for (Open x : open) if (!x.extra()) planOpen = true;
        if (!planOpen && resumeAt >= 0) { if (book.alive(resumeAt)) book.setTurn(resumeAt); resumeAt = -1; }
    }

    /** Llamar seguido (cada lectura): da por perdidas las que pasaron la expiración sin pago. */
    public void tick(long t) { expire(t, false); }

    private void expire(long t, boolean strict) {
        long wait = strict ? expiryMs + 600 : expiryMs + graceMs;
        while (!open.isEmpty()) {
            Open o = open.get(0);   // en orden: la más vieja primero
            if (t - o.at < wait) break;
            close(o, false, 0);
        }
    }

    /** Olvida las operaciones abiertas (por ejemplo al reiniciar). */
    public void clear() { open.clear(); resumeAt = -1; }
}
