const STORAGE_KEY = 'ledger-data';
function emptyData(){
  return {
    debts: [], expenses: [], incomes: [], hormiga: [], loans: [],
    settings: { hormigaLimit: 50, currentBalance: 0 },
    savings: { emergency: { movements: [] }, investments: { movements: [], rate: 0 } },
    history: { paidDebts: [] },
    bank: { transactions: [], rules: [], imports: [], sourceNames: {}, names: {}, linkMemory: {}, trust: {} },
    allocations: [], shifts: {}, merges: {}, paidMarks: {}, payArrived: {}
  };
}
function normalizeData(parsed){
  parsed = parsed || {};
  return {
    debts: parsed.debts || [],
    expenses: parsed.expenses || [],
    incomes: parsed.incomes || [],
    hormiga: parsed.hormiga || [],
    loans: parsed.loans || [],
    settings: {
      hormigaLimit: (parsed.settings && parsed.settings.hormigaLimit) || 50,
      currentBalance: (parsed.settings && parsed.settings.currentBalance) || 0
    },
    savings: {
      emergency: { movements: (parsed.savings && parsed.savings.emergency && parsed.savings.emergency.movements) || [] },
      investments: {
        movements: (parsed.savings && parsed.savings.investments && parsed.savings.investments.movements) || [],
        rate: (parsed.savings && parsed.savings.investments && parsed.savings.investments.rate) || 0
      }
    },
    history: { paidDebts: (parsed.history && parsed.history.paidDebts) || [] },
    bank: {
      transactions: (parsed.bank && parsed.bank.transactions) || [],
      rules: (parsed.bank && parsed.bank.rules) || [],
      imports: (parsed.bank && parsed.bank.imports) || [],
      sourceNames: (parsed.bank && parsed.bank.sourceNames) || {},
      // nombres que tú le pones a cada comercio/fuente (antes solo existían para ingresos)
      names: Object.assign({}, (parsed.bank && parsed.bank.sourceNames) || {}, (parsed.bank && parsed.bank.names) || {}),
      linkMemory: (parsed.bank && parsed.bank.linkMemory) || {},
      trust: (parsed.bank && parsed.bank.trust) || {}
    },
    allocations: parsed.allocations || [],
    dupIgnored: parsed.dupIgnored || [],
    conflictOk: parsed.conflictOk || [],
    shifts: parsed.shifts || {},
    paidMarks: parsed.paidMarks || {},
    payArrived: parsed.payArrived || {},
    merges: parsed.merges || {}
  };
}

let data = emptyData();
let confirmingDeleteId = null;
let sectionsOpen = { personalize:false, income:true, debts:false, expenses:false, hormiga:false, loans:false, savings:false, stats:false, range:false, history:false };
let savingsTab = 'emergency';
let editingItem = null; // {kind, id}
let loaded = false;
let monthsOpen = {}; // { sectionKey: { monthKey: bool } }
let formOpen = {}; // { kind: bool } — formularios de "añadir" colapsados detrás de un botón +

function isMonthOpen(section, key, defaultOpen){
  if(!monthsOpen[section]) monthsOpen[section] = {};
  if(!(key in monthsOpen[section])) monthsOpen[section][key] = defaultOpen;
  return monthsOpen[section][key];
}
function toggleMonth(section, key){
  if(!monthsOpen[section]) monthsOpen[section] = {};
  monthsOpen[section][key] = !monthsOpen[section][key];
  render();
}
function toggleFormOpen(kind){
  formOpen[kind] = !formOpen[kind];
  render();
  if(formOpen[kind]){
    setTimeout(()=>{
      const el = document.getElementById('edit-anchor-'+kind);
      if(el) el.scrollIntoView({behavior:'smooth', block:'start'});
    }, 50);
  }
}

function getGreeting(){
  const h = new Date().getHours();
  if(h < 12) return 'Buenos días';
  if(h < 19) return 'Buenas tardes';
  return 'Buenas noches';
}

const fmtMoney = n => {
  const sign = n < 0 ? '-' : '';
  const cur = (typeof currencyInfo === 'function') ? currencyInfo() : null;
  if(cur && cur.code !== 'USD'){
    const s = Math.abs(n).toLocaleString(cur.locale, { minimumFractionDigits: cur.dec, maximumFractionDigits: cur.dec });
    return sign + (cur.after ? s + ' ' + cur.sym : cur.sym + s);
  }
  return sign + '$' + Math.abs(n).toLocaleString('en-US', {minimumFractionDigits:2, maximumFractionDigits:2});
};

function escapeAttr(s){
  return String(s == null ? '' : s)
    .replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// Convierte el texto de un campo de monto a número, tratando comas como separador
// de miles (nunca como decimal) para que un teclado en otro idioma/región no
// convierta "12,788" en 12.788. Acepta tanto "12,788.50" como "12788.50".
function parseMoney(v){
  if(v === null || v === undefined) return 0;
  let raw = String(v).trim();
  // Pesos colombianos / euros: el punto separa miles y la coma es decimal ("1.500.000" o "12,50").
  raw = raw.replace(/[\s\u00a0\u202f]/g, '');
  if(typeof currencyInfo === 'function' && currencyInfo().dotThousands) raw = raw.replace(/\./g, '').replace(',', '.');
  const cleaned = raw.replace(/,/g, '');
  const n = parseFloat(cleaned);
  return isNaN(n) ? 0 : n;
}

function startOfWeek(d){
  const date = new Date(d);
  const day = date.getDay(); // 0 Sun .. 6 Sat
  const diff = (day === 0 ? -6 : 1) - day; // lunes (fijo: los pagos guardados usan esta semana como referencia)
  date.setDate(date.getDate() + diff);
  date.setHours(0,0,0,0);
  return date;
}

function nextExpenseDueDate(dueDay, from){
  const d = new Date(from);
  d.setHours(0,0,0,0);
  let candidate = new Date(d.getFullYear(), d.getMonth(), dueDay);
  if(candidate < d) candidate = new Date(d.getFullYear(), d.getMonth()+1, dueDay);
  return candidate;
}

function monthKey(dateStr){
  const d = new Date(dateStr+'T00:00:00');
  return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0');
}
function monthLabel(dateStr){
  const d = new Date(dateStr+'T00:00:00');
  return capFirst(d.toLocaleDateString('es-ES', {month:'long', year:'numeric'}));
}
function capFirst(s){ s = String(s||''); return s.charAt(0).toUpperCase() + s.slice(1); }
function groupByMonth(items, amountFn){
  const map = {};
  const order = [];
  items.forEach(it=>{
    const key = monthKey(it.date);
    if(!map[key]){ map[key] = { key, label: monthLabel(it.date), items: [], total: 0 }; order.push(key); }
    map[key].items.push(it);
    map[key].total += amountFn ? amountFn(it) : (it.amount||0);
  });
  return order.sort((a,b)=>b.localeCompare(a)).map(k=>map[k]);
}

let saveStatus = null; // null | 'saving' | 'ok' | 'error'
let saveStatusTimer = null;

function setSaveStatus(status){
  saveStatus = status;
  renderStatusOnly();
  if(saveStatusTimer) clearTimeout(saveStatusTimer);
  if(status === 'ok'){
    saveStatusTimer = setTimeout(()=>{ saveStatus = null; renderStatusOnly(); }, 1800);
  }
}

function renderStatusOnly(){
  const el = document.getElementById('save-status');
  if(!el) return;
  if(saveStatus === 'saving') el.textContent = 'Guardando…';
  else if(saveStatus === 'ok') el.textContent = 'Guardado ✓';
  else if(saveStatus === 'error') el.textContent = 'No se pudo guardar — reintentando en segundo plano';
  else el.textContent = '';
  el.className = 'save-status ' + (saveStatus || '');
}

let loadState = 'ok'; // localStorage es síncrono: no hay estado de "cargando" ni reintentos de red
let saveBlocked = false;

function loadData(){
  try{
    const raw = localStorage.getItem(STORAGE_KEY);
    if(raw){
      data = normalizeData(JSON.parse(raw));
    }
    loadState = 'ok';
  }catch(e){
    // localStorage no disponible (modo incógnito muy restrictivo, o navegador muy viejo).
    loadState = 'unavailable';
  }
  loaded = true;
  const ghosts = cleanGhostTransactions();
  const twins = mergeExactTwins();
  applyPrefs();
  render();
  if(loadState === 'ok') scheduleNativeReminders();
  if(twins) setTimeout(() => showToast(`Encontré ${twins} gasto${twins===1?'':'s'} fijo${twins===1?'':'s'} guardado${twins===1?'':'s'} dos veces (mismo nombre, monto y día) y los uní. Ya no se cuentan doble.`), 900);
  if(ghosts) setTimeout(() => showToast(`Quité ${ghosts} movimiento${ghosts===1?'':'s'} falso${ghosts===1?'':'s'} que salían de la tabla de saldos del PDF (no eran pagos de verdad).`), 400);
}

// Limpieza: movimientos leídos por error de la tabla de saldos diarios del PDF (sin letras en la descripción).
function cleanGhostTransactions(){
  const list = (data.bank && data.bank.transactions) || [];
  const bad = list.filter(t => !/[A-Za-z]{2,}/.test(t.desc || ''));
  if(!bad.length) return 0;
  bad.forEach(t => {
    if(typeof unlinkTx === 'function') unlinkTx(t);
    if(t.category === 'loan' && typeof removeLoanFromTx === 'function') removeLoanFromTx(t);
    if(t.category === 'loanback' && typeof removeRepayment === 'function') removeRepayment(t);
  });
  data.bank.transactions = list.filter(t => /[A-Za-z]{2,}/.test(t.desc || ''));
  try{ localStorage.setItem(STORAGE_KEY, JSON.stringify(data)); }catch(e){}
  return bad.length;
}
// Respaldo completo: finanzas + Inglés + Interés compuesto + Notas + preferencias.
function downloadBackup(){
  saveTextFile(`control-financiero-respaldo-${todayISO()}.json`, 'application/json', JSON.stringify(fullBackupObject(), null, 1));
}

let showBackupBox = false;
let copyStatus = '';

function toggleBackupBox(){
  showBackupBox = !showBackupBox;
  copyStatus = '';
  render();
  if(showBackupBox){
    setTimeout(()=>{
      const el = document.getElementById('backup-textarea');
      if(el){ el.focus(); el.select(); }
    }, 50);
  }
}

async function copyBackupText(){
  const text = document.getElementById('backup-textarea').value;
  try{
    await navigator.clipboard.writeText(text);
    copyStatus = 'Copiado ✓ — ahora pégalo en Notas o donde quieras guardarlo.';
  }catch(e){
    // El portapapeles automático puede estar bloqueado: recurrimos a selección manual.
    const el = document.getElementById('backup-textarea');
    el.focus();
    el.select();
    try{
      document.execCommand('copy');
      copyStatus = 'Copiado ✓ — ahora pégalo en Notas o donde quieras guardarlo.';
    }catch(e2){
      copyStatus = 'No pudimos copiarlo automáticamente. El texto ya está seleccionado: mantén presionado y elige "Copiar".';
    }
  }
  render();
  setTimeout(()=>{
    const el = document.getElementById('backup-textarea');
    if(el){ el.focus(); el.select(); }
  }, 50);
}

let pendingRestoreData = null;
let restoreError = null;
let showRestoreBox = false;

function toggleRestoreBox(){
  showRestoreBox = !showRestoreBox;
  restoreError = null;
  render();
}

function restoreFromTextarea(){
  const el = document.getElementById('restore-textarea');
  const val = el ? el.value : '';
  if(!val || !val.trim()){
    restoreError = 'Pega el contenido del archivo antes de restaurar.';
    render();
    return;
  }
  try{
    const parsed = JSON.parse(val);
    if(typeof parsed !== 'object' || parsed === null) throw new Error('formato inválido');
    pendingRestoreData = parsed;
    restoreError = null;
    showRestoreBox = false;
  }catch(err){
    pendingRestoreData = null;
    restoreError = 'Ese texto no es un respaldo válido — revisa que copiaste todo el contenido del archivo, desde la primera { hasta la última }.';
  }
  render();
}

function confirmRestore(){
  restoreAnyBackup(pendingRestoreData);
  pendingRestoreData = null;
  render();
}

function cancelRestore(){
  pendingRestoreData = null;
  render();
}

function saveData(){
  if(typeof previewMode !== 'undefined' && previewMode){ return; }
  if(loadState === 'unavailable'){
    setSaveStatus('error');
    return;
  }
  try{
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    setSaveStatus('ok');
    scheduleNativeReminders();
  }catch(e){
    // Típicamente por cuota llena (muy improbable con este volumen de datos) o modo incógnito estricto.
    setSaveStatus('error');
  }
}

// ---- Edición en curso ----
function startEdit(kind, id){
  editingItem = { kind, id };
  confirmingDeleteId = null;
  if(kind === 'savings-emergency') savingsTab = 'emergency';
  if(kind === 'savings-investments') savingsTab = 'investments';
  const sectionKey = kind === 'incomes' ? 'income'
    : (kind === 'savings-emergency' || kind === 'savings-investments') ? 'savings'
    : kind;
  if(sectionKey in sectionsOpen) sectionsOpen[sectionKey] = true;
  const dest = TAB_FOR_KIND[kind];
  if(dest){
    activeTab = dest[0];
    if(dest[0]==='mov') movSeg = dest[1];
    if(dest[0]==='debts') debtSeg = dest[1];
  }
  render();
  setTimeout(()=>{
    const el = document.getElementById('edit-anchor-'+kind);
    if(el) el.scrollIntoView({behavior:'smooth', block:'start'});
  }, 50);
}
function cancelEdit(){
  if(editingItem) formOpen[editingItem.kind] = false;
  editingItem = null;
  render();
}

// ---- Deudas ----
// extra: { payFreq:'month'|'week', weeklyPayment, weekday, flexible }
function debtExtra(extra, monthlyPayment){
  extra = extra || {};
  const week = extra.payFreq === 'week';
  const wp = week ? parseMoney(extra.weeklyPayment) : 0;
  return { payFreq: week ? 'week' : 'month', weeklyPayment: wp, weekday: extra.weekday != null ? +extra.weekday : 5, flexible: !!extra.flexible,
           monthlyPayment: week ? Math.round(wp * 4.345 * 100) / 100 : parseMoney(monthlyPayment) };
}
function addDebt(name, balance, rate, dueDate, monthlyPayment, extra){
  data.debts.push({id:'d'+Date.now(), name, balance:parseMoney(balance), rate:parseMoney(rate)||0, dueDate, ...debtExtra(extra, monthlyPayment), createdAt: todayISO()});
  saveData(); render();
}
function updateDebt(id, name, balance, rate, dueDate, monthlyPayment, extra){
  const idx = data.debts.findIndex(d=>d.id===id);
  if(idx>-1) data.debts[idx] = {...data.debts[idx], name, balance:parseMoney(balance), rate:parseMoney(rate)||0, dueDate, ...debtExtra(extra || data.debts[idx], monthlyPayment)};
  saveData(); render();
}
// ---- Gastos fijos ----
// Un gasto fijo o deuda ya guardado con el mismo nombre (sin importar mayúsculas/espacios).
function sameNameItem(name){
  const n = String(name || '').trim().toLowerCase().replace(/\s+/g,' ');
  if(!n) return null;
  const e = data.expenses.find(x => String(x.name||'').trim().toLowerCase().replace(/\s+/g,' ') === n);
  if(e) return { type:'expense', item:e };
  const d = data.debts.find(x => String(x.name||'').trim().toLowerCase().replace(/\s+/g,' ') === n);
  return d ? { type:'debt', item:d } : null;
}
// Gastos fijos idénticos (mismo nombre, monto, día y frecuencia): se unen solos al abrir la app.
function mergeExactTwins(){
  let n = 0;
  const sig = e => [String(e.name||'').trim().toLowerCase(), Math.round((e.amount||0)*100), expenseFreq(e), expenseFreq(e)==='week' ? e.weekday : e.dueDay].join('|');
  const seen = {};
  data.expenses.slice().forEach(e => {
    const k = sig(e);
    if(!seen[k]){ seen[k] = e; return; }
    const keep = seen[k];
    bankList().forEach(t => { if(t.link && t.link.type === 'expense' && t.link.id === e.id) t.link.id = keep.id; });
    (data.allocations || []).forEach(a => { if(a.expenseId === e.id) a.expenseId = keep.id; });
    Object.values((data.bank && data.bank.linkMemory) || {}).forEach(m => { if(m.type === 'expense' && m.id === e.id) m.id = keep.id; });
    data.expenses = data.expenses.filter(x => x.id !== e.id);
    n++;
  });
  if(n) saveData();
  return n;
}
function addExpense(name, amount, dueDay, freq, weekday){
  const twin = sameNameItem(name);
  if(twin){ showToast(`Ya tienes "${twin.item.name}" en ${twin.type==='debt'?'Deudas':'Gastos fijos'}. Edítalo ahí en vez de crearlo otra vez.`); startEdit(twin.type==='debt'?'debts':'expenses', twin.item.id); return; }
  data.expenses.push({id:'e'+Date.now(), name, amount:parseMoney(amount), dueDay:Number(dueDay)||1, freq: freq==='week'?'week':'month', weekday: Number(weekday)});
  saveData(); render();
}

function updateExpense(id, name, amount, dueDay, freq, weekday){
  const idx = data.expenses.findIndex(e=>e.id===id);
  if(idx>-1) data.expenses[idx] = {...data.expenses[idx], name, amount:parseMoney(amount), dueDay:Number(dueDay)||1, freq: freq==='week'?'week':'month', weekday: Number(weekday)};
  saveData(); render();
}

function addIncome(amount, date, source){
  data.incomes.push({id:'i'+Date.now(), amount:parseMoney(amount), date, source:source||''});
  saveData(); render();
}
function updateIncome(id, amount, date, source){
  const idx = data.incomes.findIndex(i=>i.id===id);
  if(idx>-1) data.incomes[idx] = {...data.incomes[idx], amount:parseMoney(amount), date, source:source||''};
  saveData(); render();
}
// ---- Gastos hormiga ----
function addHormiga(amount, date, desc){
  data.hormiga.push({id:'h'+Date.now(), amount:parseMoney(amount), date, desc:desc||''});
  saveData(); render();
}
function updateHormiga(id, amount, date, desc){
  const idx = data.hormiga.findIndex(h=>h.id===id);
  if(idx>-1) data.hormiga[idx] = {...data.hormiga[idx], amount:parseMoney(amount), date, desc:desc||''};
  saveData(); render();
}
// ---- Préstamos ----
// Un préstamo "activo" (no devuelto) descuenta su monto del capital disponible;
// al marcarlo devuelto, borrarlo, o editar su monto, se ajusta el capital para que
// siempre refleje lo que de verdad tienes en la mano.
function applyLoanBalanceEffect(amount, wasActive, isActive){
  const oldContribution = wasActive ? -amount : 0;
  const newContribution = isActive ? -amount : 0;
  data.settings.currentBalance = (data.settings.currentBalance || 0) + (newContribution - oldContribution);
}
function addLoan(person, amount, date, note, due){
  const amt = parseMoney(amount);
  data.loans.push({id:'l'+Date.now(), person, amount:amt, date, note:note||'', returned:false, due: due||''});
  applyLoanBalanceEffect(amt, false, true);
  saveData(); render();
}
function updateLoan(id, person, amount, date, note, returned){
  const idx = data.loans.findIndex(l=>l.id===id);
  if(idx>-1){
    const existing = data.loans[idx];
    const wasActive = !existing.returned;
    const newAmount = parseMoney(amount);
    const isActive = !returned;
    // Revierte el efecto del monto viejo y aplica el del monto nuevo
    if(!existing.fromBank){
      if(wasActive) applyLoanBalanceEffect(existing.amount, true, false);
      if(isActive) applyLoanBalanceEffect(newAmount, false, true);
    }
    data.loans[idx] = {...existing, person, amount:newAmount, date, note:note||'', returned: !!returned, due: arguments[6] !== undefined ? arguments[6] : (existing.due||'')};
  }
  saveData(); render();
}
function toggleLoanReturned(id){
  const l = data.loans.find(x=>x.id===id);
  if(l){
    const wasActive = !l.returned;
    l.returned = !l.returned;
    const isActive = !l.returned;
    if(!l.fromBank) applyLoanBalanceEffect(l.amount, wasActive, isActive);
    saveData(); render();
  }
}
function removeLoan(id){
  const l = data.loans.find(x=>x.id===id);
  if(l){
    const wasActive = !l.returned;
    if(!l.fromBank) applyLoanBalanceEffect(l.amount, wasActive, false);
    if(l.txId){ const t = bankList().find(x => x.id === l.txId); if(t){ delete t.loanId; t.category = 'gasto'; } }
  }
  data.loans = data.loans.filter(x => x.id !== id);
  saveData(); render();
}

function removeItem(kind, id){
  // Si borras una deuda o un gasto fijo, los pagos del banco conectados quedan sin conectar.
  if(kind === 'debts' || kind === 'expenses'){
    const type = kind === 'debts' ? 'debt' : 'expense';
    bankList().forEach(t => { if(t.link && t.link.type === type && t.link.id === id) delete t.link; });
    data.allocations = (data.allocations||[]).filter(a => kind === 'debts' ? a.debtId !== id : a.expenseId !== id);
  }
  data[kind] = data[kind].filter(x => x.id !== id);
  saveData(); render();
}

function requestDelete(id){
  confirmingDeleteId = id;
  render();
}
function cancelDelete(){
  confirmingDeleteId = null;
  render();
}
function confirmDelete(kind, id){
  confirmingDeleteId = null;
  if(editingItem && editingItem.kind===kind && editingItem.id===id) editingItem = null;
  if(kind === 'loans'){ removeLoan(id); return; }
  removeItem(kind, id);
}
function deleteControl(kind, id){
  if(confirmingDeleteId === id){
    if(kind === 'debts'){
      return `<span class="del-confirm">
        <button class="item-del-yes" onclick="confirmPaidDebt('${id}')">Saldada ✓</button>
        <button class="item-del-yes" style="border-color:var(--ink-faint);color:var(--ink-faint);" onclick="confirmDelete('${kind}','${id}')">Borrar sin más</button>
        <button class="item-del-no" onclick="cancelDelete()">Cancelar</button>
      </span>`;
    }
    return `<span class="del-confirm">
      <button class="item-del-yes" onclick="confirmDelete('${kind}','${id}')">Sí, borrar</button>
      <button class="item-del-no" onclick="cancelDelete()">Cancelar</button>
    </span>`;
  }
  return `<button class="item-del" onclick="event.stopPropagation(); requestDelete('${id}')">×</button>`;
}
function confirmPaidDebt(id){
  const debt = data.debts.find(d => d.id === id);
  if(debt){
    const paidInApp = debtPayments(debt.id).reduce((s,p)=>s+p.amount,0);
    data.history.paidDebts.push({
      id:'p'+Date.now(),
      name: debt.name,
      balance: debt.balance > 0 ? debt.balance : paidInApp,
      monthlyPayment: debt.monthlyPayment,
      date: new Date().toISOString().slice(0,10)
    });
  }
  confirmingDeleteId = null;
  if(editingItem && editingItem.kind==='debts' && editingItem.id===id) editingItem = null;
  removeItem('debts', id);
}

function setHormigaLimit(val){
  data.settings.hormigaLimit = parseMoney(val);
  saveData(); render();
}
function setCurrentBalance(val){
  data.settings.currentBalance = parseMoney(val);
  saveData(); render();
}

// ---- Ahorro (fondo de emergencia / inversiones) ----
function savingsTotal(which){
  return data.savings[which].movements.reduce((s,m)=> s + (m.type==='withdraw' ? -(m.amount||0) : (m.amount||0)), 0);
}
function addSavingsMovement(which, amount, date, note, type){
  data.savings[which].movements.push({id:'s'+Date.now(), amount:parseMoney(amount), date, note:note||'', type});
  saveData(); render();
}
function updateSavingsMovement(which, id, amount, date, note, type){
  const list = data.savings[which].movements;
  const idx = list.findIndex(m=>m.id===id);
  if(idx>-1) list[idx] = {...list[idx], amount:parseMoney(amount), date, note:note||'', type};
  saveData(); render();
}
function removeSavingsMovement(which, id){
  data.savings[which].movements = data.savings[which].movements.filter(m=>m.id!==id);
  saveData(); render();
}
function deleteControlSavings(which, id){
  if(confirmingDeleteId === id){
    return `<span class="del-confirm">
      <button class="item-del-yes" onclick="confirmDeleteSavings('${which}','${id}')">Sí, borrar</button>
      <button class="item-del-no" onclick="cancelDelete()">Cancelar</button>
    </span>`;
  }
  return `<button class="item-del" onclick="event.stopPropagation(); requestDelete('${id}')">×</button>`;
}
function confirmDeleteSavings(which, id){
  confirmingDeleteId = null;
  const kind = 'savings-'+which;
  if(editingItem && editingItem.kind===kind && editingItem.id===id) editingItem = null;
  removeSavingsMovement(which, id);
}
function setInvestmentRate(val){
  data.savings.investments.rate = parseMoney(val);
  saveData(); render();
}
function setSavingsTab(tab){
  savingsTab = tab;
  render();
}
function submitSavingsMovement(which){
  const amount = document.getElementById('sav-'+which+'-amount').value;
  const date = document.getElementById('sav-'+which+'-date').value;
  const note = document.getElementById('sav-'+which+'-note').value;
  const type = document.getElementById('sav-'+which+'-type').value;
  if(!amount || !date) return;
  const kind = 'savings-'+which;
  if(editingItem && editingItem.kind===kind){
    updateSavingsMovement(which, editingItem.id, amount, date, note, type);
    editingItem = null;
  } else {
    addSavingsMovement(which, amount, date, note, type);
  }
  formOpen[kind] = false;
}

// ---- Resumen por rango de fechas ----
let rangeStart = null, rangeEnd = null;
function setRangeStart(v){ rangeStart = v; render(); }
function setRangeEnd(v){ rangeEnd = v; render(); }
function computeRangeSummary(){
  if(!rangeStart || !rangeEnd) return null;
  const rs = new Date(rangeStart+'T00:00:00');
  const re = new Date(rangeEnd+'T23:59:59');
  if(re < rs) return null;
  const incomes = allIncomes().filter(i=>{ const d=new Date(i.date+'T00:00:00'); return d>=rs && d<=re; });
  const hormiga = allHormiga().filter(h=>{ const d=new Date(h.date+'T00:00:00'); return d>=rs && d<=re; });
  const gastos = allOtherSpend().filter(g=>{ const d=new Date(g.date+'T00:00:00'); return d>=rs && d<=re; });
  const loans = data.loans.filter(l=>{ const d=new Date(l.date+'T00:00:00'); return d>=rs && d<=re; });

  const dueItems = occurrencesInRange(rs, re).map(o => ({ name:o.name, amount:o.amount, kind: o.kind==='debt' ? 'Deuda' : 'Gasto fijo', date: dayISO(o.date) }));

  const totalIncome = incomes.reduce((s,i)=>s+(i.amount||0),0);
  const totalHormiga = hormiga.reduce((s,h)=>s+(h.amount||0),0);
  const totalDue = dueItems.reduce((s,x)=>s+(x.amount||0),0);
  const totalLoaned = loans.reduce((s,l)=>s+(l.amount||0),0);
  const totalGasto = gastos.reduce((s,g)=>s+(g.amount||0),0);
  return { incomes, hormiga, gastos, loans, dueItems, totalIncome, totalHormiga, totalGasto, totalDue, totalLoaned };
}
function renderRangeSummary(r){
  if(!r) return '<div class="empty-note" style="border-top:none;">Elige fecha de inicio y fin para ver el resumen.</div>';
  const rows = [];
  r.incomes.forEach(i=>rows.push({label:(i.source||'Ingreso'), kind:'Ingreso', amount:i.amount, date:i.date, positive:true}));
  r.hormiga.forEach(h=>rows.push({label:(h.desc||'Gasto hormiga'), kind:'Hormiga', amount:h.amount, date:h.date, positive:false}));
  r.gastos.forEach(g=>rows.push({label:(g.desc||'Gasto'), kind:'Otros gastos', amount:g.amount, date:g.date, positive:false}));
  r.loans.forEach(l=>rows.push({label:l.person, kind:'Préstamo', amount:l.amount, date:l.date, positive:false}));
  r.dueItems.forEach(x=>rows.push({label:x.name, kind:x.kind, amount:x.amount, date:x.date, positive:false}));
  rows.sort((a,b)=>new Date(a.date)-new Date(b.date));
  const net = r.totalIncome - r.totalHormiga - r.totalGasto - r.totalDue - r.totalLoaned;
  return `
    <div class="item-row" style="border-top:none;"><div class="item-main"><div class="item-name">Ingresos</div></div><div class="item-amounts"><span class="item-amount" style="color:var(--good);">${fmtMoney(r.totalIncome)}</span></div></div>
    <div class="item-row"><div class="item-main"><div class="item-name">Gastos hormiga</div></div><div class="item-amounts"><span class="item-amount" style="color:var(--warn);">${fmtMoney(r.totalHormiga)}</span></div></div>
    <div class="item-row"><div class="item-main"><div class="item-name">Otros gastos (del banco)</div></div><div class="item-amounts"><span class="item-amount" style="color:var(--warn);">${fmtMoney(r.totalGasto)}</span></div></div>
    <div class="item-row"><div class="item-main"><div class="item-name">Deudas y gastos fijos que vencen</div></div><div class="item-amounts"><span class="item-amount" style="color:var(--warn);">${fmtMoney(r.totalDue)}</span></div></div>
    <div class="item-row"><div class="item-main"><div class="item-name">Prestado en el período</div></div><div class="item-amounts"><span class="item-amount" style="color:var(--warn);">${fmtMoney(r.totalLoaned)}</span></div></div>
    <div class="item-row" style="border-top:2px solid var(--line);"><div class="item-main"><div class="item-name">Neto del período</div></div><div class="item-amounts"><span class="item-amount" style="${net<0?'color:var(--warn);':'color:var(--good);'}">${fmtMoney(net)}</span></div></div>
    <p class="item-meta" style="margin:14px 0 6px 0;">Detalle</p>
    ${rows.length ? rows.map(x=>`
      <div class="item-row">
        <div class="item-main"><div class="item-name">${escapeAttr(x.label)}</div><div class="item-meta">${x.kind} · ${new Date(x.date+'T00:00:00').toLocaleDateString('es-ES',{day:'numeric',month:'short'})}</div></div>
        <div class="item-amounts"><span class="item-amount" style="${x.positive?'color:var(--good);':''}">${x.positive?'+':'-'}${fmtMoney(x.amount)}</span></div>
      </div>`).join('') : '<div class="empty-note">Sin movimientos en este rango.</div>'}
  `;
}

function computeAll(){
  const today = new Date(); today.setHours(0,0,0,0);
  // El "periodo" es la semana, quincena o mes según cada cuánto te pagan (Más → Personalizar).
  const per = periodBounds(today);
  const weekStart = per.s, weekEnd = per.e;
  const div = periodsPerMonth();

  const weeklyEquivDebt = data.debts.map(d => ({...d, weekly: (d.monthlyPayment||0)/div}));
  const weeklyEquivExp = data.expenses.map(e => ({...e, weekly: expenseMonthly(e)/div}));
  const weeklyTarget = weeklyEquivDebt.reduce((s,d)=>s+d.weekly,0) + weeklyEquivExp.reduce((s,e)=>s+e.weekly,0);
  const monthlyTotal = data.debts.reduce((s,d)=>s+(d.monthlyPayment||0),0) + data.expenses.reduce((s,e)=>s+expenseMonthly(e),0);

  // Esta semana: lo que vence menos lo que ya pagaste ANTES de esta semana (lo de esta semana sale de lo que ganas).
  const occWeek = occurrencesInRange(weekStart, weekEnd, weekStart);
  const dueThisWeek = occWeek.reduce((s,o)=>s+o.remaining,0);

  // Próximos 30 días (incluye lo de esta semana que aún no está pagado)
  const in30 = addDays(today, 30); in30.setHours(23,59,59,999);
  const upcoming = occurrencesInRange(weekStart, in30)
    .filter(o => o.remaining > 0.004 && (o.date >= today || o.date >= weekStart))
    .map(o => ({ name:o.name, amount:o.remaining, full:o.amount, paid:o.paid, days: Math.round((o.date - today)/86400000), date:o.date,
                 key:o.key, baseIso: dayISO(o.baseDate), shifted:o.shifted, kind:o.kind, refId:o.refId }));

  const incomeThisWeek = sumInRange(allIncomes(), weekStart, weekEnd);
  const hormigaThisWeek = sumInRange(allHormiga(), weekStart, weekEnd);
  const gastoThisWeek = sumInRange(allOtherSpend(), weekStart, weekEnd);

  const hormigaLimit = (data.settings && data.settings.hormigaLimit) || 0;
  const hormigaOver = hormigaThisWeek - hormigaLimit;

  const availableThisWeek = incomeThisWeek - hormigaThisWeek - gastoThisWeek;
  const need = byDates() ? dueThisWeek : Math.max(weeklyTarget, dueThisWeek);
  const shortfall = need - availableThisWeek;

  const currentBalance = (data.settings && data.settings.currentBalance) || 0;
  const totalWithBalance = currentBalance + availableThisWeek;
  const realGap = need - totalWithBalance; // positivo = aún te falta, negativo = te sobra

  const totalDebt = data.debts.reduce((s,d)=>s+Math.max(0, d.balance||0),0);

  // Esta semana y las 4 siguientes: lo que vence (menos lo ya pagado) y lo que conviene ganar.
  const weeksAhead = [];
  let cursor = weekStart;
  for(let w=0; w<5; w++){
    const pb = periodBounds(cursor);
    const ws = pb.s, we = pb.e;
    cursor = new Date(we.getFullYear(), we.getMonth(), we.getDate() + 1);
    const occ = w === 0 ? occWeek : occurrencesInRange(ws, we);
    const due = occ.reduce((s,o)=>s+o.remaining,0);
    weeksAhead.push({ weekStart: ws, weekEnd: we, due, need: byDates() ? due : Math.max(weeklyTarget, due), items: occ, isCurrent: w===0 });
  }

  return {weeklyTarget, dueThisWeek, incomeThisWeek, hormigaThisWeek, gastoThisWeek, hormigaLimit, hormigaOver, availableThisWeek, shortfall, totalDebt, monthlyTotal, upcoming, weeklyEquivDebt, weeklyEquivExp, need, weeksAhead, currentBalance, totalWithBalance, realGap};
}

function pastWeeksHistory(limit){
  const today = new Date(); today.setHours(0,0,0,0);
  const weeks = [];
  let cur = periodBounds(today).s;
  for(let w=1; w<=limit; w++){
    const prev = new Date(cur.getFullYear(), cur.getMonth(), cur.getDate() - 1);
    const pb = periodBounds(prev);
    const ws = pb.s, we = pb.e; cur = ws;
    const income = sumInRange(allIncomes(), ws, we);
    const hormiga = sumInRange(allHormiga(), ws, we);
    const gasto = sumInRange(allOtherSpend(), ws, we);
    if(income === 0 && hormiga === 0 && gasto === 0) continue;
    weeks.push({ weekStart: ws, weekEnd: we, income, hormiga, gasto, net: income - hormiga - gasto });
  }
  return weeks;
}

function toggleSection(key){
  sectionsOpen[key] = !sectionsOpen[key];
  render();
}

function renderBackupBox(){
  if(!showBackupBox) return '';
  const text = JSON.stringify(fullBackupObject(), null, 1)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  return `<div class="load-fail">
    <p class="load-fail-title">Tu respaldo (ya seleccionado, listo para copiar)</p>
    <p class="load-fail-body">Toca "Copiar", o si no funciona, mantén presionado el texto y elige "Copiar" tú mismo. Después pégalo en Notas, WhatsApp (contigo mismo), o donde lo vayas a guardar.</p>
    <textarea id="backup-textarea" readonly>${text}</textarea>
    <button class="btn btn-primary" style="margin-top:8px;" onclick="copyBackupText()">Copiar</button>
    <button class="btn" style="margin-top:8px;" onclick="toggleBackupBox()">Cerrar</button>
    ${copyStatus ? `<p class="load-fail-body" style="color:var(--good);margin-top:8px;">${copyStatus}</p>` : ''}
  </div>`;
}

function renderRestoreDialogs(){
  let html = '';
  if(showRestoreBox){
    html += `<div class="load-fail">
      <p class="load-fail-title">Pega aquí el contenido de tu respaldo</p>
      <p class="load-fail-body">Abre el archivo .json que descargaste (en tu app de Archivos/Descargas), selecciona todo el texto, cópialo, y pégalo en el cuadro de abajo.</p>
      <textarea id="restore-textarea" placeholder='{"debts": [...], ...}'></textarea>
      <button class="btn btn-primary" style="margin-top:8px;" onclick="restoreFromTextarea()">Restaurar desde este texto</button>
      <button class="btn" style="margin-top:8px;" onclick="toggleRestoreBox()">Cancelar</button>
    </div>`;
  }
  if(pendingRestoreData){
    const counts = ['debts','expenses','incomes','hormiga','loans'].map(k=>{
      const n = (pendingRestoreData[k]||[]).length;
      const labels = {debts:'deudas', expenses:'gastos fijos', incomes:'ingresos', hormiga:'gastos hormiga', loans:'préstamos'};
      return `${n} ${labels[k]}`;
    }).join(', ');
    html += `<div class="load-fail" style="border-color:var(--good);">
      <p class="load-fail-title">¿Restaurar este respaldo?</p>
      <p class="load-fail-body">Contiene: ${counts}. Esto va a <strong>reemplazar todos los datos actuales</strong> de la app por los de este archivo.</p>
      <button class="btn btn-primary" onclick="confirmRestore()">Sí, restaurar</button>
      <button class="btn" style="margin-top:8px;" onclick="cancelRestore()">Cancelar</button>
    </div>`;
  }
  if(restoreError){
    html += `<div class="load-fail"><p class="load-fail-body" style="color:var(--warn);">${restoreError}</p></div>`;
  }
  return html;
}
