
// =====================================================================
//  NOMBRES QUE LA APP APRENDE + FUENTES DE INGRESO (con fusiones)
// =====================================================================
let editingSourceTx = null; // id del movimiento que se está renombrando

// Fusiones: "star" → "Starsnack". Se siguen en cadena por si se unieron varias.
function resolveSource(name){
  let n = String(name || '').trim(), guard = 0;
  const m = data.merges || {};
  while(m[n.toLowerCase()] && guard++ < 8) n = m[n.toLowerCase()];
  return n;
}
function rawSourceName(i){
  if(i.bank) return i.name || niceName(merchantKey(i.source));
  const s = String(i.source || '').trim();
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : 'Sin fuente';
}
function incomeSourceName(i){ return resolveSource(rawSourceName(i)); }
function incomeBySource(s, e){
  const map = {};
  allIncomes().forEach(i => {
    const d = new Date(i.date+'T00:00:00');
    if(d < s || d > e) return;
    const raw = rawSourceName(i);
    const name = resolveSource(raw);
    const k = name.toLowerCase();
    if(!map[k]) map[k] = { name, total:0, count:0, members: new Set() };
    map[k].total += i.amount || 0;
    map[k].count++;
    if(raw.toLowerCase() !== k) map[k].members.add(raw);
  });
  return Object.values(map).map(x => ({ ...x, members: Array.from(x.members) })).sort((a,b) => b.total - a.total);
}
function mergeSource(from, into){
  if(!from || !into || from.toLowerCase() === into.toLowerCase()) return;
  if(!data.merges) data.merges = {};
  data.merges[from.toLowerCase()] = into;
  sheet = null;
  saveData(); render();
  showToast(`"${from}" ahora cuenta como "${into}"`);
}
function unmergeSource(name){
  if(!data.merges) return;
  delete data.merges[String(name).toLowerCase()];
  sheet = null;
  saveData(); render();
  showToast(`"${name}" volvió a ir por separado`);
}

// Renombrar cualquier movimiento del banco (entrada o salida). El nombre queda para ese comercio/fuente.
function startSourceEdit(id){ editingSourceTx = id; render(); setTimeout(() => { const el = document.getElementById('src-input-'+id); if(el) el.focus(); }, 50); }
function cancelSourceEdit(){ editingSourceTx = null; render(); }
function saveSourceName(id){
  const t = bankList().find(x => x.id === id);
  const el = document.getElementById('src-input-'+id);
  if(!t || !el) return;
  const name = el.value.trim();
  const key = merchantKey(t.desc);
  if(name){
    data.bank.names[key] = name;
    data.bank.trust[key] = Math.max(2, data.bank.trust[key] || 0); // lo nombraste tú: los iguales ya no preguntan
  } else delete data.bank.names[key];
  t.nameOk = true;
  editingSourceTx = null;
  saveData(); render();
  if(name) showToast(`Listo: "${niceName(key)}" ahora se llama "${name}". Si llega algo parecido, te pregunto.`);
}

// =====================================================================
//  REVISAR MOVIMIENTOS (tarjeta en Inicio)
//  Agrupa por comercio/persona: lo que contestas se aplica a todos los de ese comercio.
// =====================================================================
const NOISE_ONE = new RegExp(DESC_NOISE.source);
// Palabras del banco que no dicen nada del comercio: no sirven para comparar.
const GENERIC_WORDS = new Set(['JERSEY','CITY','BAYONNE','NEWARK','YORK','STORE','MARKET','PAYMENT','PAYMENTS','DEPOSIT','INC','LLC','COMPANY',
  'SERVICE','SERVICES','SVC','ONLINE','BILL','TRANSFER','ZELLE','SENT','RECEIVED','ELECTRONIC','PURCHASE','DEBIT','CREDIT','CARD','AUTOPAY',
  'RECURRING','WITHDRAWAL','CHECK','FUNDS','DIRECT','PAYROLL','INTERNET','MOBILE','BANK','CORP','STORES','SUPERCENTER','CENTER','WWW']);
const REVIEW_DAYS = 45;     // solo se pregunta por movimientos recientes
const SNOOZE_DAYS = 3;      // "Después" lo esconde estos días; a la segunda vez se descarta

function sigWords(s){
  return normDesc(s).replace(/[^A-Z ]/g,' ').split(' ').filter(w => w.length >= 4 && !GENERIC_WORDS.has(w) && !NOISE_ONE.test(' '+w+' '));
}
// Dirección (entrada/salida) de un comercio ya nombrado, según sus movimientos.
function keyDir(key){
  const t = bankList().find(x => merchantKey(x.desc) === key);
  return t ? t.dir : null;
}
// Sugerencia de nombre: mismo comercio ya nombrado, o uno que comparte una palabra propia (nunca mezcla entradas con salidas).
function suggestName(t){
  const names = data.bank.names || {};
  const key = merchantKey(t.desc);
  if(names[key]) return { name: names[key], exact: true, key };
  const words = sigWords(key);
  if(!words.length) return null;
  for(const k of Object.keys(names)){
    if(keyDir(k) !== t.dir) continue;
    const kw = sigWords(k);
    if(kw.some(w => words.includes(w)) || sameCompact(words, kw)) return { name: names[k], exact: false, key: k };
  }
  return null;
}

// "STAR SNACKS" y "STARSNACKS" son lo mismo: se comparan pegados.
function sameCompact(a, b){
  const ca = a.join(''), cb = b.join('');
  if(ca.length < 5 || cb.length < 5) return false;
  return ca.includes(cb) || cb.includes(ca) || b.some(w => w.length >= 5 && ca.includes(w)) || a.some(w => w.length >= 5 && cb.includes(w));
}
let questionRename = null;   // grupo al que le dijiste "No" y estás escribiendo qué es
let questionCats = null;     // grupo en el que abriste "Otra categoría"
function groupKeyOf(t){ return t.dir + '|' + merchantKey(t.desc); }
function groupTxs(gk){
  const since = addDays(new Date(), -REVIEW_DAYS);
  return bankList().filter(t => groupKeyOf(t) === gk && isoToDate(t.date) >= since && !t.qDismissed);
}
function pendingLoanFor(t){
  const k = merchantKey(t.desc);
  return data.loans.find(l => l.fromBank && !l.returned && l.personKey === k);
}

function pendingQuestions(){
  const today = todayISO();
  const since = addDays(new Date(), -REVIEW_DAYS);
  const groups = {};
  bankList().forEach(t => {
    if(t.qDismissed || isoToDate(t.date) < since) return;
    if(t.qSnooze && t.qSnooze > today) return;
    (groups[groupKeyOf(t)] = groups[groupKeyOf(t)] || []).push(t);
  });
  const qs = [];
  // Pagos repetidos: una deuda y un gasto fijo con casi el mismo monto
  detectDupes().forEach(d => qs.push({ kind:'dupe', gk: d.gk, dupe:d, date:'9999' }));
  Object.keys(groups).forEach(gk => {
    const txs = groups[gk].sort((a,b) => b.date.localeCompare(a.date));
    const t = txs[0];
    const key = merchantKey(t.desc);
    const base = { gk, txs, t, date: t.date };
    // 0) El mismo comercio quedó en dos cosas distintas: preguntar para que no se duplique
    const conf = groupConflict(gk);
    if(conf){ qs.push({ ...base, kind:'conflict', options: conf }); return; }
    // 1) Alguien te devuelve un préstamo
    const loan = t.dir === 'in' ? pendingLoanFor(t) : null;
    const loanTx = txs.find(x => x.dir === 'in' && x.category !== 'loanback' && !x.reviewed && pendingLoanFor(x));
    if(loan && loanTx){ qs.push({ ...base, t: loanTx, kind:'loanback', loan }); return; }
    // 2) Pagos de deuda / gasto fijo sin conectar
    const unlinked = txs.filter(x => (x.category === 'debt' || x.category === 'fixed') && !x.link && !x.linkAsked && x.dir === 'out');
    if(unlinked.length){
      const mem = (data.bank.linkMemory || {})[gk];
      const target = mem && (mem.type === 'debt' ? data.debts.find(d => d.id === mem.id) : data.expenses.find(e => e.id === mem.id));
      qs.push({ ...base, t: unlinked[0], txs: unlinked, kind:'link', target: target ? { type: mem.type, id: mem.id, name: target.name } : null });
      return;
    }
    // 3) Te devolvieron un préstamo pero falta decir cuál
    const lb = txs.find(x => x.category === 'loanback' && !x.loanId);
    if(lb){ qs.push({ ...base, t: lb, kind:'loanpick' }); return; }
    // 4) Nombre parecido a uno que ya pusiste
    if(!(data.bank.trust[key] >= 2) && txs.some(x => !x.nameOk) && t.category !== 'own' && t.category !== 'ignore'){
      const sug = suggestName(t);
      if(sug){ qs.push({ ...base, kind:'name', sug }); return; }
    }
    // 5) Comercio nuevo (o confirmado solo 1 vez): preguntar en qué categoría va
    const rule = findRule(key, t.dir);
    const unrev = txs.filter(x => !x.reviewed && x.auto !== 'manual-set' && x.auto !== 'manual' && !x.link);
    if(unrev.length && !ruleTrusted(rule)){
      qs.push({ ...base, t: unrev[0], txs: unrev, kind:'cat', last: rule ? rule.category : null });
    }
  });
  return qs.sort((a,b) => (b.date||'').localeCompare(a.date||''));
}

function setGroupCategory(gk, cat){
  // Todos los movimientos de ese comercio/persona, no solo los recientes (los viejos también cuentan en los promedios).
  // No toca los que son un duplicado de algo anotado a mano.
  const recent = groupTxs(gk);
  const txs = bankList().filter(t => groupKeyOf(t) === gk && t.auto !== 'manual').sort((a,b) => (recent.includes(b) - recent.includes(a)) || b.date.localeCompare(a.date));
  if(!txs.length || !BANK_CATS[cat]) return;
  const t = txs[0];
  const key = merchantKey(t.desc);
  txs.forEach(x => {
    if(x.category !== cat){
      if(x.link) unlinkTx(x);
      if(x.category === 'loan') removeLoanFromTx(x);
      if(x.category === 'loanback') removeRepayment(x);
    }
    x.category = cat; x.auto = 'manual-set'; x.reviewed = true;
  });
  upsertRule(key, t.dir, cat);
  questionCats = null;
  saveData();
  if(cat === 'debt' || cat === 'fixed'){ openLinkSheet(t.id, cat === 'debt' ? 'debt' : 'expense'); return; }
  if(cat === 'loan'){ txs.forEach(x => createLoanFromTx(x)); saveData(); sheet = { type:'loanDue', loanId: t.loanId }; render(); return; }
  if(cat === 'loanback'){ sheet = { type:'loanPick', txId: t.id }; render(); return; }
  render();
  showToast(`"${displayName(t)}" → ${BANK_CATS[cat].label}` + (txs.length > 1 ? ` (${txs.length} movimientos)` : '') + '. Lo recordaré.');
}
function dismissGroup(gk){
  if(gk.startsWith('dupe|')){ const [, a, b] = gk.split('|'); ignoreDupe(a, b); return; }
  groupTxs(gk).forEach(x => { x.qDismissed = true; x.reviewed = true; });
  questionRename = null; questionCats = null;
  saveData(); render();
  showToast('Descartado: no vuelve a aparecer');
}
function snoozeGroup(gk){
  if(gk.startsWith('dupe|')){ skippedDupes.add(gk); render(); return; }
  let dismissed = false;
  const until = dayISO(addDays(new Date(), SNOOZE_DAYS));
  groupTxs(gk).forEach(x => {
    x.qSnoozes = (x.qSnoozes || 0) + 1;
    if(x.qSnoozes >= 2){ x.qDismissed = true; dismissed = true; } else x.qSnooze = until;
  });
  questionRename = null; questionCats = null;
  saveData(); render();
  showToast(dismissed ? 'Ya lo habías dejado para después: lo descarté para que no se acumule' : `Te lo vuelvo a preguntar en ${SNOOZE_DAYS} días`);
}
function answerName(gk, yes){
  const txs = groupTxs(gk);
  if(!txs.length) return;
  const t = txs[0];
  const sug = suggestName(t);
  if(yes && sug){
    const key = merchantKey(t.desc);
    if(!sug.exact) data.bank.names[key] = sug.name;
    data.bank.trust[key] = (data.bank.trust[key] || 0) + 1;
    txs.forEach(x => { x.nameOk = true; });
    // Si el comercio parecido ya tenía categoría, se usa la misma
    const simRule = (data.bank.rules || []).find(r => r.key === sug.key && r.dir === t.dir);
    if(simRule && !findRule(key, t.dir)){ upsertRule(key, t.dir, simRule.category); txs.forEach(x => { if(!x.link){ x.category = simRule.category; x.reviewed = true; } }); }
    saveData(); render();
    showToast(`Listo: "${sug.name}"` + (txs.length > 1 ? ` (${txs.length} movimientos)` : ''));
  } else {
    questionRename = gk;
    render();
    setTimeout(() => { const el = document.getElementById('q-rename'); if(el) el.focus(); }, 50);
  }
}
function saveQuestionRename(gk){
  const txs = groupTxs(gk);
  const el = document.getElementById('q-rename');
  if(!txs.length || !el) return;
  const name = el.value.trim();
  const key = merchantKey(txs[0].desc);
  if(name){ data.bank.names[key] = name; data.bank.trust[key] = 1; }
  txs.forEach(x => { x.nameOk = true; });
  questionRename = null;
  saveData(); render();
  if(name) showToast(`Guardado como "${name}"`);
}
function answerLink(gk, yes){
  const txs = groupTxs(gk).filter(x => (x.category === 'debt' || x.category === 'fixed') && !x.link);
  if(!txs.length) return;
  const mem = (data.bank.linkMemory || {})[gk];
  if(yes && mem){ chooseLink(txs[0].id, mem.type, mem.id); return; }
  openLinkSheet(txs[0].id, txs[0].category === 'debt' ? 'debt' : 'expense');
}
function answerLoanBack(txId, loanId, yes){
  const t = bankList().find(x => x.id === txId);
  if(!t) return;
  if(!yes){ t.reviewed = true; saveData(); render(); return; }
  if(t.link) unlinkTx(t);
  t.category = 'loanback'; t.auto = 'manual-set'; t.reviewed = true;
  applyRepayment(t, loanId);
  saveData(); render();
}
function toggleQuestionCats(gk){ questionCats = questionCats === gk ? null : gk; render(); }

// ---- Un mismo comercio puesto en dos cosas distintas ----
function assignmentOf(x){
  if(x.link){
    const tg = x.link.type === 'debt' ? data.debts.find(d => d.id === x.link.id) : data.expenses.find(e => e.id === x.link.id);
    return { id: x.link.type + ':' + x.link.id, label: tg ? tg.name + (x.link.type === 'debt' ? ' (deuda)' : ' (gasto fijo)') : '?', link: x.link };
  }
  return { id: 'cat:' + x.category, label: BANK_CATS[x.category] ? BANK_CATS[x.category].label : x.category, cat: x.category };
}
function groupConflict(gk){
  if((data.conflictOk || []).includes(gk)) return null;
  const opts = {};
  bankList().forEach(x => {
    if(groupKeyOf(x) !== gk || x.qDismissed) return;
    if(!(x.auto === 'manual-set' || x.link)) return;
    if(['loan','loanback','own','trading','ignore'].includes(x.category)) return;
    const a = assignmentOf(x);
    if(!opts[a.id]) opts[a.id] = { ...a, n: 0 };
    opts[a.id].n++;
  });
  const list = Object.values(opts);
  return list.length > 1 ? list : null;
}
function resolveConflict(gk, optId){
  const conf = groupConflict(gk);
  const opt = conf && conf.find(o => o.id === optId);
  if(!opt) return;
  const txs = bankList().filter(x => groupKeyOf(x) === gk && !x.qDismissed);
  if(opt.link){
    const t0 = txs.find(x => !(x.link && x.link.id === opt.link.id)) || txs[0];
    txs.forEach(x => { if(x.link && x.link.id !== opt.link.id) unlinkTx(x); x.auto = 'manual-set'; });
    chooseLink(t0.id, opt.link.type, opt.link.id);
  } else {
    txs.forEach(x => { if(x.link) unlinkTx(x); x.category = opt.cat; x.auto = 'manual-set'; x.reviewed = true; });
    upsertRule(merchantKey(txs[0].desc), txs[0].dir, opt.cat);
    saveData(); render();
    showToast(`Listo: todo "${displayName(txs[0])}" quedó como ${opt.label}`);
  }
}
function keepConflict(gk){
  if(!data.conflictOk) data.conflictOk = [];
  data.conflictOk.push(gk);
  saveData(); render();
}

// ---- Pagos repetidos: deuda y gasto fijo que son el mismo pago (ej. el carro) ----
let skippedDupes = new Set();
// Referencias: "d:<id>" para deudas, "e:<id>" para gastos fijos.
function refItem(ref){
  const [k, id] = [ref.slice(0,1), ref.slice(2)];
  if(k === 'd'){ const d = data.debts.find(x => x.id === id); return d ? { type:'debt', id, name:d.name, amount:d.monthlyPayment||0, item:d } : null; }
  const e = data.expenses.find(x => x.id === id);
  return e ? { type:'expense', id, name:e.name, amount:e.amount||0, item:e, freq: expenseFreq(e) } : null;
}
function itemDesc(r){
  if(r.type === 'debt') return `deuda con pago de ${fmtMoney(r.amount)} al mes`;
  return `gasto fijo de ${fmtMoney(r.amount)} ${expenseWhen(r.item).toLowerCase()}`;
}
const normName = s => String(s||'').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g,'').replace(/[^a-z0-9]/g,'');
function detectDupes(){
  const ign = data.dupIgnored || [];
  const out = [];
  const close = (a, b) => a > 0 && b > 0 && Math.abs(a - b) <= Math.max(5, Math.max(a,b) * 0.03);
  const add = (a, b) => {
    const gk = 'dupe|'+a+'|'+b;
    if(ign.includes(a+'|'+b) || ign.includes(b+'|'+a) || skippedDupes.has(gk)) return;
    out.push({ a: refItem(a), b: refItem(b), aRef:a, bRef:b, gk });
  };
  // deuda + gasto fijo con casi el mismo pago mensual (ej. Tel Toyota y Carro)
  data.debts.forEach(d => {
    if(!(d.monthlyPayment > 0)) return;
    data.expenses.forEach(e => { if(expenseFreq(e) === 'month' && close(e.amount, d.monthlyPayment)) add('e:'+e.id, 'd:'+d.id); });
  });
  // dos deudas con el mismo nombre (ej. "Child support" repetida)
  for(let i = 0; i < data.debts.length; i++) for(let k = i+1; k < data.debts.length; k++){
    const a = data.debts[i], b = data.debts[k];
    if(normName(a.name) === normName(b.name)) add('d:'+b.id, 'd:'+a.id);
  }
  // dos gastos fijos iguales (mismo nombre, o mismo monto y misma frecuencia)
  for(let i = 0; i < data.expenses.length; i++) for(let k = i+1; k < data.expenses.length; k++){
    const a = data.expenses[i], b = data.expenses[k];
    if(expenseFreq(a) !== expenseFreq(b)) continue;
    if(normName(a.name) === normName(b.name) || close(a.amount, b.amount)) add('e:'+b.id, 'e:'+a.id); // se queda el más viejo
  }
  return out;
}
function ignoreDupe(a, b){
  if(!data.dupIgnored) data.dupIgnored = [];
  data.dupIgnored.push(a+'|'+b);
  saveData(); render();
}
// Une "from" dentro de "to": los pagos conectados pasan a "to" y "from" se borra.
function mergeItems(fromRef, toRef){
  const from = refItem(fromRef), to = refItem(toRef);
  if(!from || !to || fromRef === toRef) return;
  let moved = 0;
  bankList().forEach(t => {
    if(t.link && t.link.type === from.type && t.link.id === from.id){ unlinkTx(t); linkTx(t, to.type, to.id); moved++; }
  });
  if(from.type === 'expense'){
    data.allocations = (data.allocations || []).map(a => a.expenseId === from.id ? (to.type === 'expense' ? { ...a, expenseId: to.id } : null) : a).filter(Boolean);
  } else {
    data.allocations = (data.allocations || []).map(a => a.debtId === from.id ? (to.type === 'debt' ? { ...a, debtId: to.id } : null) : a).filter(Boolean);
  }
  Object.keys(data.bank.linkMemory || {}).forEach(k => {
    const m = data.bank.linkMemory[k];
    if(m.type === from.type && m.id === from.id) data.bank.linkMemory[k] = { type: to.type, id: to.id, count: m.count || 1 };
  });
  if(to.type === 'debt' && from.type === 'expense' && !to.item.dueDate){
    to.item.dueDate = todayISO().slice(0,8) + String(Math.min(28, from.item.dueDay||1)).padStart(2,'0');
  }
  if(from.type === 'expense') data.expenses = data.expenses.filter(x => x.id !== from.id);
  else data.debts = data.debts.filter(x => x.id !== from.id);
  sheet = null;
  saveData(); render();
  showToast(`Listo: "${from.name}" quedó unido a "${to.name}"` + (moved ? ` (${moved} pago${moved===1?'':'s'} pasaron)` : '') + '. Ya no se cuenta dos veces.');
}
function mergeDupe(did, eid){ mergeItems('e:'+eid, 'd:'+did); }

// =====================================================================
//  COBROS QUE SE REPITEN (suscripciones, pagos mensuales)
// =====================================================================
function detectRecurring(){
  const groups = {};
  bankList().forEach(t => {
    if(t.dir !== 'out' || t.category === 'ignore' || t.category === 'own' || t.category === 'trading') return;
    const k = merchantKey(t.desc);
    (groups[k] = groups[k] || []).push(t);
  });
  const out = [];
  Object.keys(groups).forEach(k => {
    const list = groups[k];
    const byMonth = {};
    list.forEach(t => { const mk = t.date.slice(0,7); (byMonth[mk] = byMonth[mk] || []).push(t); });
    const months = Object.keys(byMonth).sort();
    if(months.length < 2) return;
    if(months.some(m => byMonth[m].length > 2)) return; // compras frecuentes (gasolina, comida), no un cobro fijo
    if(months.filter(m => byMonth[m].length === 1).length < Math.ceil(months.length * 2/3)) return;
    const picks = months.map(m => byMonth[m].slice().sort((a,b) => b.amount - a.amount)[0]);
    const amts = picks.map(p => p.amount).sort((a,b) => a-b);
    const med = amts[Math.floor(amts.length/2)];
    if(!picks.every(p => Math.abs(p.amount - med) <= Math.max(1, med*0.2))) return;
    const days = picks.map(p => +p.date.slice(8,10));
    if(Math.max(...days) - Math.min(...days) > 7) return;
    const last = picks[picks.length-1];
    const tracked = data.expenses.some(e => nameMatches(last.desc, e.name)) || data.debts.some(d => nameMatches(last.desc, d.name));
    out.push({
      key: k, name: niceName(k), amount: med,
      day: Math.round(days.reduce((a,b)=>a+b,0) / days.length),
      months: months.length, last: last.date, tracked, category: last.category
    });
  });
  return out.sort((a,b) => b.amount - a.amount);
}
function addRecurringAsExpense(key){
  const r = detectRecurring().find(x => x.key === key);
  if(!r) return;
  const twin = sameNameItem(r.name) || (data.expenses.find(e => Math.abs((e.amount||0) - r.amount) <= Math.max(1, r.amount*0.05) && Math.abs((e.dueDay||1) - r.day) <= 5) ? { type:'expense', item: data.expenses.find(e => Math.abs((e.amount||0) - r.amount) <= Math.max(1, r.amount*0.05) && Math.abs((e.dueDay||1) - r.day) <= 5) } : null);
  if(twin){
    upsertRule(key, 'out', twin.type === 'debt' ? 'debt' : 'fixed');
    bankList().forEach(t => { if(t.dir === 'out' && merchantKey(t.desc) === key && !t.link) linkTx(t, twin.type, twin.item.id); });
    saveData(); render();
    showToast(`Ese cobro ya lo tenías como "${twin.item.name}". Lo conecté ahí en vez de crearlo otra vez.`);
    return;
  }
  data.expenses.push({ id:'e'+Date.now(), name: r.name, amount: Math.round(r.amount*100)/100, dueDay: Math.min(28, Math.max(1, r.day)) });
  upsertRule(key, 'out', 'fixed');
  bankList().forEach(t => { if(t.dir === 'out' && merchantKey(t.desc) === key && t.auto !== 'manual-set' && t.auto !== 'manual') { t.category = 'fixed'; t.auto = 'rule'; } });
  saveData(); render();
  showToast(`"${r.name}" quedó como gasto fijo de ${fmtMoney(r.amount)} al mes`);
}

// =====================================================================
//  HISTORIAL POR MES
// =====================================================================
function currentMonthKey(){ return todayISO().slice(0,7); }
function monthKeysWithData(){
  const set = new Set();
  data.incomes.forEach(i => i.date && set.add(i.date.slice(0,7)));
  data.hormiga.forEach(h => h.date && set.add(h.date.slice(0,7)));
  bankList().forEach(t => set.add(t.date.slice(0,7)));
  return Array.from(set).sort().reverse();
}
function monthSummary(mk){
  const [y, m] = mk.split('-').map(Number);
  const s = new Date(y, m-1, 1);
  const e = new Date(y, m, 0, 23, 59, 59, 999);
  const income = sumInRange(allIncomes(), s, e);
  const spend = spendingByCategory(s, e);
  const totalOut = SPEND_CATS.reduce((a,c) => a + spend[c], 0);
  return {
    mk, s, e, label: capFirst(s.toLocaleDateString('es-ES', {month:'long', year:'numeric'})),
    income, spend, totalOut, net: income - totalOut,
    sources: incomeBySource(s, e), merchants: topMerchants(s, e, 5), trading: tradingSummary(s, e)
  };
}

// =====================================================================
//  GUARDAR ARCHIVOS (respaldo y resumen) — usa el puente nativo de la app
// =====================================================================
function saveTextFile(name, mime, text){
  try{
    if(window.Android && Android.saveFile){
      const where = Android.saveFile(name, mime, text);
      if(where){ showToast('Guardado en ' + where); return true; }
      showToast('No se pudo guardar el archivo');
      return false;
    }
  }catch(e){}
  // Navegador normal: descarga clásica
  const blob = new Blob([text], {type: mime});
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = name;
  document.body.appendChild(a); a.click(); document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 2000);
  return true;
}

// Resumen para pasarle a Claude: últimos 3 meses + todos los movimientos de ese periodo.
function buildSummaryText(){
  const lines = [];
  const now = new Date();
  lines.push('# Resumen de Control Financiero');
  lines.push('Generado: ' + now.toLocaleString('es-ES'));
  lines.push('');
  const c = computeAll();
  lines.push('## Situación actual');
  lines.push(`- Saldo actual (lo que tengo): ${fmtMoney(c.currentBalance)}`);
  lines.push(`- Meta ${PL().adj} promedio (deudas + gastos fijos): ${fmtMoney(c.weeklyTarget)}`);
  lines.push(`- Total mensual comprometido: ${fmtMoney(c.monthlyTotal)}`);
  lines.push(`- Deuda total: ${fmtMoney(c.totalDebt)}`);
  lines.push(`- ${capFirst(PL().art)}: ingresé ${fmtMoney(c.incomeThisWeek)}, gasté ${fmtMoney(c.hormigaThisWeek + c.gastoThisWeek)} (hormiga ${fmtMoney(c.hormigaThisWeek)}), me falta ${fmtMoney(Math.max(c.shortfall,0))}`);
  lines.push('');
  for(let i=0; i<3; i++){
    const b = monthBounds(-i);
    const mk = b.s.getFullYear() + '-' + String(b.s.getMonth()+1).padStart(2,'0');
    const ms = monthSummary(mk);
    lines.push(`## ${ms.label}${i===0?' (mes en curso)':''}`);
    lines.push(`- Ingresos: ${fmtMoney(ms.income)}`);
    ms.sources.forEach(src => lines.push(`  - ${src.name}: ${fmtMoney(src.total)} (${src.count})`));
    lines.push(`- Gastos: ${fmtMoney(ms.totalOut)}`);
    SPEND_CATS.forEach(cat => lines.push(`  - ${BANK_CATS[cat].label}: ${fmtMoney(ms.spend[cat])}`));
    lines.push(`- Neto: ${fmtMoney(ms.net)}`);
    if(ms.merchants.length) lines.push('- Donde más gasté: ' + ms.merchants.map(m => `${niceName(m.key)} ${fmtMoney(m.total)}`).join(', '));
    lines.push('');
  }
  lines.push('## Deudas');
  if(data.debts.length) data.debts.forEach(d => lines.push(`- ${d.name}: saldo ${fmtMoney(d.balance)}, pago ${fmtMoney(d.monthlyPayment)}/mes${d.rate?`, ${d.rate}% interés`:''}${d.dueDate?`, vence ${d.dueDate}`:''}`));
  else lines.push('- Ninguna');
  lines.push('');
  lines.push('## Gastos fijos mensuales');
  if(data.expenses.length) data.expenses.forEach(e => lines.push(`- ${e.name}: ${fmtMoney(e.amount)} el día ${e.dueDay}`));
  else lines.push('- Ninguno');
  lines.push('');
  const rec = detectRecurring();
  lines.push('## Cobros que se repiten (detectados en el banco)');
  if(rec.length) rec.forEach(r => lines.push(`- ${r.name}: ~${fmtMoney(r.amount)} cerca del día ${r.day} (${r.months} meses)${r.tracked?' — ya está como gasto fijo/deuda':''}`));
  else lines.push('- Ninguno detectado todavía');
  lines.push('');
  const pendingLoans = data.loans.filter(l => !l.returned);
  lines.push('## Préstamos que me deben');
  if(pendingLoans.length) pendingLoans.forEach(l => lines.push(`- ${l.person}: ${fmtMoney(l.amount)} desde ${l.date}`));
  else lines.push('- Ninguno');
  lines.push('');
  lines.push(`## Ahorro\n- Fondo de emergencia: ${fmtMoney(savingsTotal('emergency'))}\n- Inversiones: ${fmtMoney(savingsTotal('investments'))}`);
  lines.push('');
  // Movimientos de los últimos ~3 meses
  const since = monthBounds(-2).s;
  const rows = [];
  data.incomes.forEach(i => rows.push([i.date, i.source || 'Ingreso', i.amount, 'Ingreso', 'a mano']));
  data.hormiga.forEach(h => rows.push([h.date, h.desc || 'Gasto hormiga', -h.amount, 'Gasto hormiga', 'a mano']));
  bankList().forEach(t => rows.push([t.date, t.desc, t.dir==='out' ? -t.amount : t.amount, BANK_CATS[t.category] ? BANK_CATS[t.category].label : t.category, 'banco']));
  const csvCell = v => /[",\n]/.test(String(v)) ? '"' + String(v).replace(/"/g,'""') + '"' : String(v);
  lines.push('## Movimientos (desde ' + since.toLocaleDateString('es-ES') + ')');
  lines.push('```csv');
  lines.push('fecha,descripcion,monto,categoria,origen');
  rows.filter(r => new Date(r[0]+'T00:00:00') >= since)
      .sort((a,b) => b[0].localeCompare(a[0]))
      .forEach(r => lines.push([r[0], csvCell(r[1]), Number(r[2]).toFixed(2), csvCell(r[3]), r[4]].join(',')));
  lines.push('```');
  return lines.join('\n');
}
function exportSummary(){
  saveTextFile(`resumen-financiero-${todayISO()}.md`, 'text/markdown', buildSummaryText());
}

// =====================================================================
//  RECORDATORIOS DE PAGOS (notificaciones del teléfono)
// =====================================================================
function pushWidget(){
  if(!(window.Android && Android.updateWidget)) return;
  try{
    const c = computeAll();
    const when = d => d < 0 ? 'vencido' : d === 0 ? 'hoy' : d === 1 ? 'mañana' : 'en ' + d + ' días';
    const pct = c.need > 0 ? Math.max(0, Math.min(100, Math.round(c.availableThisWeek / c.need * 100))) : 100;
    let questions = 0; try{ questions = pendingQuestions().length; }catch(e){}
    Android.updateWidget(JSON.stringify({
      covered: c.shortfall <= 0,
      falta: fmtMoney(Math.max(c.shortfall, 0)),
      pct,
      meta: 'de ' + fmtMoney(c.need),
      entro: fmtMoney(c.incomeThisWeek),
      gastado: fmtMoney(c.hormigaThisWeek + c.gastoThisWeek),
      saldo: fmtMoney(c.currentBalance),
      saldoNeg: c.currentBalance < 0,
      pagos: c.upcoming.slice(0, 3).map(u => ({ t: u.name + ' · ' + when(u.days), a: fmtMoney(u.amount) })),
      semanas: c.weeksAhead.slice(1, 4).map(w => ({ r: fmtShortDate(dayISO(w.weekStart)), a: fmtMoney(w.need) })),
      hormiga: 'Hormiga ' + fmtMoney(c.hormigaThisWeek) + ' / ' + fmtMoney(c.hormigaLimit),
      deuda: 'Deuda ' + fmtMoney(c.totalDebt),
      revisar: questions ? questions + ' por revisar' : '',
      actualizado: new Date().toLocaleTimeString('es-ES', {hour:'numeric', minute:'2-digit'})
    }));
  }catch(e){}
}
function openSubApp(name){
  if(window.Android && Android.openApp){ Android.openApp(name); return; }
  showToast('Esta sección funciona dentro de la app del teléfono');
}

function scheduleNativeReminders(){
  if(typeof previewMode !== 'undefined' && previewMode) return;
  pushWidget();
  if(!(window.Android && Android.scheduleReminders)) return;
  const now = Date.now();
  const today = new Date(); today.setHours(0,0,0,0);
  const items = [];
  const push = (name, amount, due) => {
    [2, 0].forEach(off => {
      const at = new Date(due); at.setDate(at.getDate() - off); at.setHours(9, 0, 0, 0);
      if(at.getTime() <= now || at.getTime() - now > 35*86400000) return;
      items.push({
        title: off ? `En ${off} días vence: ${name}` : `Hoy vence: ${name}`,
        text: `${fmtMoney(amount || 0)} · Control Financiero`,
        at: at.getTime()
      });
    });
  };
  occurrencesInRange(today, addDays(today, 36)).forEach(o => { if(o.remaining > 0.004) push(o.name, o.remaining, o.date); });
  data.loans.forEach(l => { if(l.due && !l.returned){ const dd = isoToDate(l.due); if(dd >= today) push(`${l.person} te paga el préstamo`, l.amount - loanRepaid(l), dd); } });
  try{ Android.scheduleReminders(JSON.stringify(items.slice(0, 60))); }catch(e){}
}
