
// =====================================================================
//  CALENDARIO DE PAGOS (gastos fijos mensuales/semanales, deudas,
//  pagos programados) + PAGOS CONECTADOS A DEUDAS Y GASTOS FIJOS
// =====================================================================
const WEEKDAYS = ['domingo','lunes','martes','miércoles','jueves','viernes','sábado'];

function dayISO(d){
  return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
}
function isoToDate(iso){ return new Date(iso+'T00:00:00'); }
function addDays(d, n){ const x = new Date(d); x.setDate(x.getDate()+n); return x; }
function expenseFreq(e){ return e.freq === 'week' ? 'week' : 'month'; }
function expenseMonthly(e){ return expenseFreq(e) === 'week' ? (e.amount||0) * 4.345 : (e.amount||0); }
function expenseWhen(e){
  return expenseFreq(e) === 'week' ? `Cada ${WEEKDAYS[e.weekday ?? 5]}` : `Día ${e.dueDay} de cada mes`;
}
function monthPeriod(d){
  return { s: new Date(d.getFullYear(), d.getMonth(), 1), e: new Date(d.getFullYear(), d.getMonth()+1, 0, 23,59,59,999),
           key: d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0') };
}
function weekPeriod(d){
  const s = startOfWeek(d);
  const e = addDays(s, 6); e.setHours(23,59,59,999);
  return { s, e, key: dayISO(s) };
}

// ---- Pagos conectados ----
function linkedTxs(type, id){
  return bankList().filter(t => t.link && t.link.type === type && t.link.id === id && t.dir === 'out');
}
function allocations(){ return data.allocations || (data.allocations = []); }
// Lo pagado a un gasto fijo dentro de un periodo (sin contar lo que se pasó a una deuda).
function expensePaid(eid, s, e, before){
  let paid = 0;
  linkedTxs('expense', eid).forEach(t => {
    const d = isoToDate(t.date);
    if(d >= s && d <= e && (!before || d < before)) paid += t.amount;
  });
  allocations().forEach(a => {
    if(a.expenseId !== eid) return;
    const d = isoToDate(a.periodDate);
    if(d >= s && d <= e) paid -= a.amount;
  });
  return Math.max(0, paid);
}
function debtPaidIn(did, s, e, before){
  let paid = 0;
  linkedTxs('debt', did).forEach(t => {
    const d = isoToDate(t.date);
    if(d >= s && d <= e && (!before || d < before)) paid += t.amount;
  });
  allocations().forEach(a => {
    if(a.debtId !== did) return;
    const d = isoToDate(a.date);
    if(d >= s && d <= e && (!before || d < before)) paid += a.amount;
  });
  return paid;
}
function debtPayments(did){
  const list = linkedTxs('debt', did).map(t => ({ date:t.date, amount:t.amount, label: displayName(t), kind:'tx', id:t.id }));
  allocations().filter(a => a.debtId === did).forEach(a => {
    const e = data.expenses.find(x => x.id === a.expenseId);
    list.push({ date:a.date, amount:a.amount, label:'Sobrante de ' + (e ? e.name : 'gasto fijo'), kind:'alloc', id:a.id });
  });
  return list.sort((a,b) => b.date.localeCompare(a.date));
}

// Conecta un movimiento del banco a una deuda o gasto fijo. A una deuda le resta el monto del saldo.
// Fecha en que registraste una deuda (sale del id). Los pagos anteriores ya estaban descontados del saldo que escribiste.
function debtCreated(d){
  if(d.createdAt) return d.createdAt;
  const m = /^d(\d{12,})$/.exec(d.id || '');
  return m ? dayISO(new Date(+m[1])) : null;
}
function linkTx(t, type, id, force){
  unlinkTx(t);
  let applied = false;
  if(type === 'debt'){
    const d = data.debts.find(x => x.id === id);
    if(!d) return;
    const created = debtCreated(d);
    applied = !!force || !created || t.date >= created;
    if(applied) d.balance = Math.round(((d.balance||0) - t.amount) * 100) / 100;
    t.category = 'debt';
  } else {
    if(!data.expenses.find(x => x.id === id)) return;
    t.category = 'fixed';
  }
  t.link = { type, id, applied };
  t.reviewed = true;
  t.auto = t.auto === 'manual' ? 'manual' : 'manual-set';
}
function unlinkTx(t){
  if(!t.link) return;
  if(t.link.type === 'debt' && t.link.applied !== false){
    const d = data.debts.find(x => x.id === t.link.id);
    if(d) d.balance = Math.round(((d.balance||0) + t.amount) * 100) / 100;
  }
  delete t.link;
}
function removeAllocation(id){
  const a = allocations().find(x => x.id === id);
  if(!a) return;
  const d = data.debts.find(x => x.id === a.debtId);
  if(d) d.balance = Math.round(((d.balance||0) + a.amount) * 100) / 100;
  data.allocations = allocations().filter(x => x.id !== id);
  saveData(); render();
  showToast('Se quitó ese abono de la deuda');
}
function allocateExcess(expenseId, periodDate, debtId, amount){
  const d = data.debts.find(x => x.id === debtId);
  if(!d || !(amount > 0)) return;
  allocations().push({ id:'a'+Date.now(), date: todayISO(), periodDate, expenseId, debtId, amount: Math.round(amount*100)/100 });
  d.balance = Math.round(((d.balance||0) - amount) * 100) / 100;
  sheet = null;
  saveData(); render();
  showToast(`Se abonaron ${fmtMoney(amount)} a "${d.name}". Ahora debes ${fmtMoney(Math.max(0, d.balance))}`);
}

// ---- Fechas de pago en un rango ----
// Devuelve cada pago (gasto fijo, cuota de deuda o pago programado) con lo pagado y lo que falta.
// "paidBefore": si se da, solo cuenta lo pagado antes de esa fecha.
function occurrencesInRange(s, e, paidBefore){
  const out = [];
  const shifts = data.shifts || {};
  const push = (o) => {
    o.date = shifts[o.key] ? isoToDate(shifts[o.key]) : o.baseDate;
    o.shifted = !!shifts[o.key];
    if(o.date >= s && o.date <= e) out.push(o);
  };
  // Meses y semanas a revisar (con margen, por si un pago se movió de fecha)
  const from = addDays(s, -40), to = addDays(e, 40);
  const months = [];
  for(let d = new Date(from.getFullYear(), from.getMonth(), 1); d <= to; d = new Date(d.getFullYear(), d.getMonth()+1, 1)) months.push(new Date(d));
  const weeks = [];
  for(let d = startOfWeek(from); d <= to; d = addDays(d, 7)) weeks.push(new Date(d));

  data.expenses.forEach(ex => {
    if(expenseFreq(ex) === 'week'){
      const wd = ex.weekday ?? 5;
      weeks.forEach(ws => {
        const base = addDays(ws, (wd + 6) % 7); // la semana empieza el lunes
        const p = weekPeriod(base);
        push({ key:`e:${ex.id}:${p.key}`, kind:'expense', refId:ex.id, name:ex.name, amount:ex.amount||0, baseDate:base, period:p });
      });
    } else {
      months.forEach(m => {
        const last = new Date(m.getFullYear(), m.getMonth()+1, 0).getDate();
        const base = new Date(m.getFullYear(), m.getMonth(), Math.min(ex.dueDay||1, last));
        const p = monthPeriod(base);
        push({ key:`e:${ex.id}:${p.key}`, kind:'expense', refId:ex.id, name:ex.name, amount:ex.amount||0, baseDate:base, period:p });
      });
    }
  });
  data.debts.forEach(dt => {
    if(dt.payFreq === 'week' && (dt.weeklyPayment||0) > 0){
      // Abono semanal: cae cada semana el día elegido; lo pagado se cuenta por tu semana de pago.
      const wd = dt.weekday ?? 5;
      for(let d = addDays(from, (wd - from.getDay() + 7) % 7); d <= to; d = addDays(d, 7)){
        const base = new Date(d); base.setHours(0,0,0,0);
        const ws = startOfUserWeek(base), we = addDays(ws, 6); we.setHours(23,59,59,999);
        push({ key:`d:${dt.id}:w${dayISO(ws)}`, kind:'debt', refId:dt.id, name:dt.name, amount:dt.weeklyPayment, baseDate:base, period:{ s:ws, e:we, key:'w'+dayISO(ws) }, flexible:!!dt.flexible });
      }
    } else if(dt.dueDate && (dt.monthlyPayment||0) > 0){
      const day = isoToDate(dt.dueDate).getDate();
      months.forEach(m => {
        const last = new Date(m.getFullYear(), m.getMonth()+1, 0).getDate();
        const base = new Date(m.getFullYear(), m.getMonth(), Math.min(day, last));
        const p = monthPeriod(base);
        push({ key:`d:${dt.id}:${p.key}`, kind:'debt', refId:dt.id, name:dt.name, amount:dt.monthlyPayment, baseDate:base, period:p, flexible:!!dt.flexible });
      });
    }
    (dt.schedule || []).forEach(sc => {
      const base = isoToDate(sc.date);
      push({ key:`s:${dt.id}:${sc.id}`, kind:'debt', refId:dt.id, name:dt.name + ' (pago programado)', amount:sc.amount||0, baseDate:base, period:monthPeriod(base), scheduled:true });
    });
  });

  // Lo pagado: a los gastos fijos por periodo; a las deudas, se reparte en orden de fecha dentro del mes.
  const debtPool = {};
  out.sort((a,b) => a.date - b.date).forEach(o => {
    let paid;
    if(o.kind === 'expense') paid = expensePaid(o.refId, o.period.s, o.period.e, paidBefore);
    else {
      const k = o.refId + '|' + o.period.key;
      if(!(k in debtPool)) debtPool[k] = debtPaidIn(o.refId, o.period.s, o.period.e, paidBefore);
      paid = Math.min(o.amount, debtPool[k]);
      debtPool[k] -= paid;
    }
    o.paid = Math.min(paid, o.amount);
    o.remaining = Math.max(0, Math.round((o.amount - o.paid) * 100) / 100);
    o.overpaid = o.kind === 'expense' ? Math.max(0, Math.round((paid - o.amount) * 100) / 100) : 0;
    // Pagado casi completo (el banco cobró unos centavos/dólares distinto): cuenta como pagado.
    if(o.paid > 0 && o.remaining > 0 && o.remaining <= Math.min(5, Math.max(1, o.amount * 0.01))) o.remaining = 0;
    // Marcado como pagado a mano en "Ya lo pagué" (efectivo, otra cuenta, o lo que faltaba no se va a pagar).
    if((data.paidMarks || {})[o.key]) o.remaining = 0;
  });
  return out;
}

// ---- Mover un pago de fecha (solo esa vez) ----
function shiftPayment(key, iso){
  if(!data.shifts) data.shifts = {};
  if(iso) data.shifts[key] = iso; else delete data.shifts[key];
  sheet = null;
  saveData(); render();
  showToast(iso ? `Pago movido al ${fmtShortDate(iso)}. Todo se recalculó.` : 'El pago volvió a su fecha original');
}
function shiftBy(key, baseIso, days){
  shiftPayment(key, dayISO(addDays(isoToDate(baseIso), days)));
}

// ---- Pagos programados de una deuda (ej. los $500 de la orden) ----
function addScheduledPayment(debtId){
  const d = data.debts.find(x => x.id === debtId);
  const di = document.getElementById('sch-date-'+debtId), ai = document.getElementById('sch-amt-'+debtId);
  if(!d || !di || !ai || !di.value || !parseMoney(ai.value)) return;
  if(!d.schedule) d.schedule = [];
  d.schedule.push({ id:'sc'+Date.now(), date: di.value, amount: parseMoney(ai.value) });
  d.schedule.sort((a,b) => a.date.localeCompare(b.date));
  saveData(); render();
  showToast('Pago programado agregado, con aviso antes de la fecha');
}
function removeScheduledPayment(debtId, sid){
  const d = data.debts.find(x => x.id === debtId);
  if(!d) return;
  d.schedule = (d.schedule || []).filter(x => x.id !== sid);
  saveData(); render();
}

// =====================================================================
//  VENTANA DE ABAJO (hoja) — elegir deuda/gasto, mover fecha, ver semana…
// =====================================================================
let sheet = null; // {type:'link', txId, kind} | {type:'shift', key, baseIso, date, name} | {type:'week', idx} | {type:'source', name} | {type:'alloc', expenseId, periodDate, amount} | {type:'rename', txId}
function closeSheet(){ sheet = null; render(); }
function openLinkSheet(txId, kind){ sheet = { type:'link', txId, kind }; render(); }
// Conecta este pago y todos los del mismo comercio/persona que aún no estén conectados.
function chooseLink(txId, type, id, force){
  const t = bankList().find(x => x.id === txId);
  if(!t) return;
  linkTx(t, type, id, force);
  const gk = groupKeyOf(t);
  if(!data.bank.linkMemory) data.bank.linkMemory = {};
  const prev = data.bank.linkMemory[gk];
  data.bank.linkMemory[gk] = { type, id, count: prev && prev.type === type && prev.id === id ? (prev.count || 1) + 1 : 1 };
  let others = 0;
  bankList().forEach(o => {
    if(o === t || o.link || o.dir !== t.dir || groupKeyOf(o) !== gk || o.qDismissed) return;
    if(o.category === 'loan' || o.category === 'loanback' || o.category === 'own' || o.category === 'trading' || o.category === 'income') return;
    linkTx(o, type, id); others++;
  });
  upsertRule(merchantKey(t.desc), t.dir, type === 'debt' ? 'debt' : 'fixed');
  sheet = null;
  saveData(); render();
  const extra = others ? ` También conecté ${others} pago${others===1?'':'s'} más de "${displayName(t)}".` : '';
  if(type === 'debt'){
    const d = data.debts.find(x => x.id === id);
    const msg = t.link.applied ? `Se restaron ${fmtMoney(t.amount)} de "${d.name}". ` + (d.balance <= 0 ? '¡Esa deuda quedó en cero!' : `Ahora debes ${fmtMoney(d.balance)}.`)
      : `Conectado a "${d.name}". No se restó del saldo porque ese pago es de antes de que registraras la deuda.`;
    showToast(msg + extra);
  } else {
    const e = data.expenses.find(x => x.id === id);
    showToast(`Pago de ${fmtMoney(t.amount)} conectado a "${e.name}".` + extra + ' Los próximos se conectan solos.');
  }
}
// Al subir un extracto: los comercios que ya conectaste antes se conectan solos.
function autoLinkNew(txs){
  let n = 0;
  txs.forEach(t => {
    if(t.link || t.dir !== 'out' || t.auto === 'manual') return;
    const mem = (data.bank.linkMemory || {})[groupKeyOf(t)];
    if(!mem || (mem.count || 1) < 2) return; // todavía no está seguro: te pregunta
    const ok = mem.type === 'debt' ? data.debts.find(d => d.id === mem.id) : data.expenses.find(e => e.id === mem.id);
    if(!ok) return;
    linkTx(t, mem.type, mem.id); n++;
  });
  return n;
}
function createAndLink(txId, kind){
  const t = bankList().find(x => x.id === txId);
  const name = (document.getElementById('nl-name') || {}).value || '';
  const amt = parseMoney((document.getElementById('nl-amount') || {}).value);
  if(!t || !name.trim()) { showToast('Escribe un nombre'); return; }
  const twin = sameNameItem(name);
  if(twin){ chooseLink(txId, twin.type, twin.item.id); showToast(`Ya tenías "${twin.item.name}": lo conecté ahí en vez de crear otro.`); return; }
  if(kind === 'debt'){
    const pay = parseMoney((document.getElementById('nl-pay') || {}).value);
    const id = 'd'+Date.now();
    data.debts.push({ id, name:name.trim(), balance: amt, rate:0, dueDate:'', monthlyPayment: pay, createdAt: todayISO() });
    chooseLink(txId, 'debt', id, true);
  } else {
    const freq = (document.getElementById('nl-freq') || {}).value === 'week' ? 'week' : 'month';
    const id = 'e'+Date.now();
    const d = isoToDate(t.date);
    data.expenses.push({ id, name:name.trim(), amount: amt || t.amount, freq, dueDay: d.getDate(), weekday: d.getDay() });
    chooseLink(txId, 'expense', id);
  }
}
function unlinkFromSheet(txId){
  const t = bankList().find(x => x.id === txId);
  if(t){ unlinkTx(t); t.linkAsked = true; }
  sheet = null; saveData(); render();
}

// =====================================================================
//  "YA LO PAGUÉ": elegir el movimiento del banco que pagó un pago pendiente
// =====================================================================
function markPaidCandidates(dateIso, amount, wide){
  const d0 = isoToDate(dateIso);
  const span = wide ? 25 : 12;
  return bankList()
    .filter(t => t.dir === 'out' && !t.link && t.category !== 'own' && t.category !== 'trading' && t.category !== 'loan'
      && Math.abs((isoToDate(t.date) - d0) / 86400000) <= span)
    .map(t => ({ t, score: Math.abs(t.amount - amount) / Math.max(1, amount) * 3 + Math.abs((isoToDate(t.date) - d0) / 86400000) / span }))
    .sort((a,b) => a.score - b.score)
    .slice(0, wide ? 40 : 8)
    .map(x => x.t);
}
function markPaidLinked(u){
  const d0 = isoToDate(u.date);
  return bankList().filter(t => t.link && t.link.id === u.refId && t.link.type === (u.kind === 'debt' ? 'debt' : 'expense')
    && Math.abs((isoToDate(t.date) - d0) / 86400000) <= 25).sort((a,b) => b.date.localeCompare(a.date));
}
function markPaidManual(){
  if(!sheet || sheet.type !== 'markpaid') return;
  const u = sheet.u;
  if(!data.paidMarks) data.paidMarks = {};
  data.paidMarks[u.key] = todayISO();
  sheet = null; saveData(); render();
  showToast(`"${u.name}" quedó como pagado.`);
}
function unmarkPaid(key){
  if(data.paidMarks) delete data.paidMarks[key];
  saveData(); render();
}
function openMarkPaid(u){ sheet = { type:'markpaid', u, wide:false }; render(); }
function chooseMarkPaid(txId){
  if(!sheet || sheet.type !== 'markpaid') return;
  const u = sheet.u;
  chooseLink(txId, u.kind === 'debt' ? 'debt' : 'expense', u.refId);
}

// =====================================================================
//  PRÉSTAMOS DESDE EL BANCO (le prestaste a alguien)
// =====================================================================
function loanPersonName(t){
  return displayName(t).replace(/^Zelle\s+/i, '') || 'Préstamo';
}
function createLoanFromTx(t){
  if(t.loanId && data.loans.find(l => l.id === t.loanId)) return;
  const id = 'l' + Date.now() + Math.floor(Math.random()*1000);
  data.loans.push({ id, person: loanPersonName(t), amount: t.amount, date: t.date, note:'Desde el banco', returned:false,
                    fromBank:true, txId: t.id, personKey: merchantKey(t.desc), due:'', repayments:[] });
  t.loanId = id;
}
function removeLoanFromTx(t){
  if(!t.loanId) return;
  data.loans = data.loans.filter(l => !(l.id === t.loanId && l.fromBank));
  delete t.loanId;
}
function loanRepaid(l){ return (l.repayments || []).reduce((s,r) => s + r.amount, 0); }
function applyRepayment(t, loanId){
  const l = data.loans.find(x => x.id === loanId);
  if(!l) return;
  removeRepayment(t);
  l.repayments = l.repayments || [];
  const left = Math.max(0, l.amount - loanRepaid(l));
  l.repayments.push({ txId: t.id, amount: Math.min(t.amount, left || t.amount), date: t.date });
  t.loanId = loanId;
  if(loanRepaid(l) >= l.amount - 0.009) l.returned = true;
  sheet = null;
  showToast(l.returned ? `${l.person} terminó de pagarte el préstamo ✓` : `${l.person} te abonó ${fmtMoney(t.amount)}. Le faltan ${fmtMoney(l.amount - loanRepaid(l))}.`);
}
function removeRepayment(t){
  if(!t.loanId) return;
  const l = data.loans.find(x => x.id === t.loanId);
  if(l && l.repayments){
    l.repayments = l.repayments.filter(r => r.txId !== t.id);
    if(loanRepaid(l) < l.amount - 0.009) l.returned = false;
  }
  delete t.loanId;
}
function setLoanDue(loanId, iso){
  const l = data.loans.find(x => x.id === loanId);
  if(!l) return;
  l.due = iso || '';
  sheet = null;
  saveData(); render();
  showToast(iso ? `Listo: te aviso 2 días antes y el ${fmtShortDate(iso)}, el día que ${l.person} te tiene que pagar` : 'Préstamo guardado sin fecha');
}
function pickLoanForRepayment(txId, loanId){
  const t = bankList().find(x => x.id === txId);
  if(!t) return;
  t.category = 'loanback'; t.reviewed = true;
  applyRepayment(t, loanId);
  saveData(); render();
}
