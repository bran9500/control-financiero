// =====================================================================
//  EN PAREJA (Nosotros): las finanzas de los dos sin mezclar los datos.
//  - Tus datos (deudas, movimientos) se leen de donde ya están; aquí no se copian ni se cambian.
//  - Los de tu pareja se guardan aparte, en su propia llave ('pareja-data').
//  - Plan de deudas: avalancha (primero la de más interés) y bola de nieve (primero la más pequeña).
// =====================================================================
const COUPLE_KEY = 'pareja-data';
let coupleSeg = 'resumen';          // resumen | plan | ella | compartidos
let coupleCache = null, coupleRaw = null;
let coupleImportBusy = false, coupleImportMsg = null, coupleImportErr = null;
let coupleDebtEdit = null;          // id de la deuda de ella que se está editando ('new' = nueva)
let coupleSharedEdit = null;        // id del gasto compartido que se está editando ('new' = nuevo)
let coupleIncomeOpen = false;
let coupleMovFilter = 'mes';        // mes | todo

function coupleEmpty(){
  return { partnerName:'', debts:[], incomes:[], txs:[], imports:[], shared:[], rules:{},
           plan:{ extra:0, method:'avalanche' }, excludeMine:{} };
}
function coupleData(){
  let raw = null;
  try{ raw = localStorage.getItem(COUPLE_KEY); }catch(e){}
  if(coupleCache && raw === coupleRaw) return coupleCache;   // se vuelve a leer si cambió (p. ej. al restaurar un respaldo)
  let p = {};
  try{ p = raw ? JSON.parse(raw) : {}; }catch(e){ p = {}; }
  const d = coupleEmpty();
  Object.keys(d).forEach(k => { if(p[k] !== undefined) d[k] = p[k]; });
  d.plan = Object.assign({ extra:0, method:'avalanche' }, p.plan || {});
  coupleCache = d; coupleRaw = raw;
  return d;
}
function coupleSave(){
  if(typeof previewMode !== 'undefined' && previewMode) return;
  try{
    coupleRaw = JSON.stringify(coupleCache);
    localStorage.setItem(COUPLE_KEY, coupleRaw);
    setSaveStatus('ok');
  }catch(e){ setSaveStatus('error'); }
}
function partnerName(){ const n = (coupleData().partnerName || '').trim(); return n || 'Ella'; }
function myName(){ return (typeof firstName === 'function' && firstName()) || 'Yo'; }
function openCouple(seg){ if(seg) coupleSeg = seg; setTab('couple'); }
function setCoupleSeg(s){ coupleSeg = s; coupleDebtEdit = null; coupleSharedEdit = null; render(); window.scrollTo(0,0); }

// ---------------------------------------------------------------- DEUDAS DE LOS DOS
// Todas las deudas del plan en un mismo formato, con su dueño. Las tuyas se leen tal cual de Deudas.
function coupleDebts(){
  const cd = coupleData();
  const mine = (data.debts || []).filter(d => (d.balance || 0) > 0.004).map(d => ({
    id:'m:'+d.id, srcId:d.id, owner:'yo', name:d.name, balance:+d.balance || 0, rate:+d.rate || 0,
    min:+d.monthlyPayment || 0, excluded: !!cd.excludeMine[d.id] }));
  const hers = (cd.debts || []).filter(d => (d.balance || 0) > 0.004).map(d => ({
    id:'p:'+d.id, srcId:d.id, owner: d.owner === 'ambos' ? 'ambos' : 'ella', name:d.name, balance:+d.balance || 0,
    rate:+d.rate || 0, min:+d.min || 0, excluded:false }));
  return mine.concat(hers);
}
function ownerLabel(o){ return o === 'yo' ? myName() : o === 'ambos' ? 'Los dos' : partnerName(); }
function ownerColor(o){ return o === 'yo' ? 'var(--s1)' : o === 'ambos' ? 'var(--s3)' : 'var(--s2)'; }

// Simula mes a mes: se paga el mínimo de cada deuda y todo lo demás va a la deuda de turno.
// Cuando una deuda se termina, su mínimo pasa a la siguiente (por eso cada vez se paga más rápido).
// method: 'avalanche' (mayor interés primero) | 'snowball' (menor saldo primero) | 'minimos' (solo mínimos, sin pasar lo que sobra)
function simulateDebts(list, extra, method){
  const ds = list.map(d => ({ id:d.id, name:d.name, owner:d.owner, bal:d.balance, rate:d.rate, min:d.min, paidOff:null, interest:0, startBal:d.balance }));
  const budget = ds.reduce((s,d) => s + d.min, 0) + (method === 'minimos' ? 0 : Math.max(0, extra));
  const order = ds.slice().sort((a,b) => method === 'snowball'
    ? (a.bal - b.bal) || (b.rate - a.rate)
    : (b.rate - a.rate) || (a.bal - b.bal));
  let month = 0, totalInterest = 0, firstMonth = null;
  const MAX = 600;
  while(ds.some(d => d.bal > 0.005) && month < MAX){
    month++;
    ds.forEach(d => { if(d.bal > 0.005){ const i = d.bal * (d.rate / 100) / 12; d.bal += i; d.interest += i; totalInterest += i; } });
    let left = budget;
    const pays = {};
    // 1) mínimos
    ds.forEach(d => { if(d.bal > 0.005){ const p = Math.min(d.min, d.bal, left); d.bal -= p; left -= p; pays[d.id] = (pays[d.id]||0) + p; } });
    // 2) lo que sobra, a la deuda de turno (y si se termina, a la siguiente)
    if(method !== 'minimos'){
      for(const d of order){
        if(left <= 0.005) break;
        if(d.bal > 0.005){ const p = Math.min(d.bal, left); d.bal -= p; left -= p; pays[d.id] = (pays[d.id]||0) + p; }
      }
    }
    if(month === 1) firstMonth = pays;
    ds.forEach(d => { if(d.bal <= 0.005 && d.paidOff === null){ d.bal = 0; d.paidOff = month; } });
    // Si en 24 meses no baja nada (los pagos no cubren el interés), no se termina nunca.
    if(month === 24 && ds.every(d => d.bal >= d.startBal - 0.01 && d.paidOff === null)) break;
  }
  const done = ds.every(d => d.paidOff !== null);
  return { months: done ? month : null, totalInterest, budget, debts: ds, firstMonth: firstMonth || {}, stuck: !done,
           order: order.map(d => d.id) };
}
function monthsToDate(m){
  const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() + m);
  return capFirst(d.toLocaleDateString('es-ES', { month:'long', year:'numeric' }));
}
function fmtMonths(m){
  if(m === null) return 'Nunca';
  const y = Math.floor(m / 12), r = m % 12;
  return [y ? y + (y === 1 ? ' año' : ' años') : '', r ? r + (r === 1 ? ' mes' : ' meses') : ''].filter(Boolean).join(' y ') || '0 meses';
}

// ---------------------------------------------------------------- INGRESOS Y GASTOS PROMEDIO
// Promedio mensual con lo que haya de los últimos 90 días (si hay menos datos, divide entre lo que hay).
function monthlyAvg(items, maxDays){
  maxDays = maxDays || 90;
  const today = new Date(); today.setHours(0,0,0,0);
  const from = addDays(today, -maxDays);
  const inRange = items.filter(x => x.date && isoToDate(x.date) >= from && isoToDate(x.date) <= today);
  if(!inRange.length) return 0;
  const first = inRange.reduce((m,x) => x.date < m ? x.date : m, inRange[0].date);
  const days = Math.max(28, Math.min(maxDays, Math.round((today - isoToDate(first)) / 86400000) + 1));
  return inRange.reduce((s,x) => s + (x.amount || 0), 0) / (days / 30.44);
}
function coupleTxsByCat(cat){ return (coupleData().txs || []).filter(t => t.category === cat); }
function coupleNumbers(){
  const cd = coupleData();
  const myIncome = monthlyAvg(allIncomes());
  const myVar = monthlyAvg(allHormiga().concat(allOtherSpend()));
  const myFixed = (data.expenses || []).reduce((s,e) => s + expenseMonthly(e), 0);
  const herIncome = monthlyAvg((cd.incomes || []).concat(coupleTxsByCat('income')));
  const herSpend = monthlyAvg(coupleTxsByCat('gasto').concat(coupleTxsByCat('fixed')));
  // Los gastos compartidos: tu parte ya puede estar en tus gastos fijos; aquí se suman solo como referencia.
  const sharedTotal = (cd.shared || []).reduce((s,x) => s + (+x.amount || 0), 0);
  const debts = coupleDebts().filter(d => !d.excluded);
  const mins = debts.reduce((s,d) => s + d.min, 0);
  const income = myIncome + herIncome;
  const spend = myVar + myFixed + herSpend;
  const free = income - spend - mins;
  return { myIncome, myVar, myFixed, herIncome, herSpend, sharedTotal, mins, income, spend, free, debts,
           hasHer: (cd.txs || []).length + (cd.incomes || []).length + (cd.debts || []).length > 0 };
}

// ---------------------------------------------------------------- PANTALLA
function renderCoupleTab(){
  const segs = [['resumen','Nosotros'],['plan','Plan de deudas'],['ella', partnerName()],['compartidos','Compartidos']];
  let html = `<div class="seg">${segs.map(([k,l]) => `<button class="${coupleSeg===k?'active':''}" onclick="setCoupleSeg('${k}')">${escapeAttr(l)}</button>`).join('')}</div>`;
  if(coupleSeg === 'plan') html += renderCouplePlan();
  else if(coupleSeg === 'ella') html += renderCoupleHer();
  else if(coupleSeg === 'compartidos') html += renderCoupleShared();
  else html += renderCoupleSummary();
  return html;
}

function renderCoupleSummary(){
  const cd = coupleData();
  const n = coupleNumbers();
  let html = '';
  if(!cd.partnerName){
    html += `<div class="card">
      <div class="card-head"><div><p class="card-title">Finanzas en pareja</p><p class="card-sub">Tus datos se quedan donde están; los de tu pareja van aparte</p></div></div>
      <div class="field"><label>¿Cómo se llama tu pareja?</label><input type="text" id="cp-name" placeholder="Su nombre"></div>
      <button class="btn btn-solid btn-block" onclick="saveCoupleName()">Empezar</button>
      <p class="item-meta" style="margin-top:10px;">Después agrega sus deudas y sube sus extractos de Bank of America en la pestaña con su nombre.</p>
    </div>`;
  }
  const all = n.debts;
  const sumOwner = o => all.filter(d => d.owner === o).reduce((s,d) => s + d.balance, 0);
  const total = all.reduce((s,d) => s + d.balance, 0);
  const sim = all.length ? simulateDebts(all, Math.max(0, cd.plan.extra || 0), cd.plan.method === 'snowball' ? 'snowball' : 'avalanche') : null;
  html += `<div class="card hero-card">
    <p class="hero-label">Deuda de los dos</p>
    <p class="hero-number warn">${fmtMoney(total)}</p>
    ${total > 0 ? `<div class="stack-bar">${['yo','ella','ambos'].map(o => { const v = sumOwner(o); return v > 0 ? `<span style="width:${(v/total*100).toFixed(1)}%;background:${ownerColor(o)};"></span>` : ''; }).join('')}</div>` : ''}
    <div class="legend-row">${['yo','ella','ambos'].map(o => sumOwner(o) > 0 ? `<span><i style="background:${ownerColor(o)};"></i>${escapeAttr(ownerLabel(o))} ${fmtMoney(sumOwner(o))}</span>` : '').join('')}</div>
    ${sim ? `<div class="hero-split">
      <span class="lbl">${sim.stuck ? 'Con lo que pagan hoy, las deudas no bajan' : 'Libres de deudas en'}</span>
      <span class="val" style="color:${sim.stuck ? 'var(--warn)' : 'var(--good)'};">${sim.stuck ? '—' : fmtMonths(sim.months)}</span>
    </div>
    ${!sim.stuck ? `<p class="hero-sub">Para ${monthsToDate(sim.months)}, pagando ${fmtMoney(sim.budget)} al mes con el plan ${cd.plan.method === 'snowball' ? 'bola de nieve' : 'avalancha'}.</p>` : ''}
    <button class="card-link" style="padding:8px 0 0 0;" onclick="setCoupleSeg('plan')">Ver el plan paso a paso ›</button>` : `<p class="hero-sub">No hay deudas con saldo. Agrega las tuyas en Deudas y las de ${escapeAttr(partnerName())} en su pestaña.</p>`}
  </div>`;

  const row = (name, val, color, meta, strong) => `<div class="item-row"><div class="item-main"><div class="item-name" ${strong?'style="font-weight:600;"':''}>${name}</div>${meta?`<div class="item-meta">${meta}</div>`:''}</div><div class="item-amounts"><span class="item-amount" style="${color?'color:'+color+';':''}${strong?'font-weight:600;':''}">${val}</span></div></div>`;
  html += `<div class="card">
    <div class="card-head"><div><p class="card-title">El mes de los dos</p><p class="card-sub">Promedio de los últimos 3 meses con datos</p></div></div>
    <div class="stat-list">
      ${row('Entra (' + escapeAttr(myName()) + ')', fmtMoney(n.myIncome), 'var(--good)')}
      ${row('Entra (' + escapeAttr(partnerName()) + ')', fmtMoney(n.herIncome), 'var(--good)', n.hasHer ? '' : 'Sube sus extractos o anota sus ingresos')}
      ${row('Gastos fijos (' + escapeAttr(myName()) + ')', '−' + fmtMoney(n.myFixed))}
      ${row('Gasto del día a día (' + escapeAttr(myName()) + ')', '−' + fmtMoney(n.myVar))}
      ${row('Gastos (' + escapeAttr(partnerName()) + ')', '−' + fmtMoney(n.herSpend), '', 'Lo que sale de su cuenta, sin pagos de deudas ni movimientos entre ustedes')}
      ${row('Pagos mínimos de deudas', '−' + fmtMoney(n.mins))}
      ${row(n.free >= 0 ? 'Les sobra al mes' : 'Les falta al mes', fmtMoney(Math.abs(n.free)), n.free >= 0 ? 'var(--good)' : 'var(--warn)', n.free > 0 ? 'Eso es lo que podrían poner de más a las deudas.' : '', true)}
    </div>
    ${n.free > 1 && Math.abs((cd.plan.extra || 0) - Math.floor(n.free)) > 1 ? `<button class="btn btn-block" style="margin-top:10px;" onclick="setPlanExtra(${Math.floor(n.free)});setCoupleSeg('plan')">Usar ${fmtMoney(Math.floor(n.free))} extra en el plan</button>` : ''}
    <p class="item-meta" style="margin-top:10px;">Tus números salen de tu app tal como están; los de ${escapeAttr(partnerName())}, de sus extractos. Nada se mezcla: si borras algo aquí, tu parte no cambia.</p>
  </div>`;

  if((cd.shared || []).length){
    const mine = cd.shared.reduce((s,x) => s + (+x.amount||0) * (+x.myPct||0) / 100, 0);
    html += `<div class="card"><div class="card-head"><p class="card-title">Gastos compartidos</p><button class="card-link" onclick="setCoupleSeg('compartidos')">Ver ›</button></div>
      <div class="mini-row"><span>Total al mes</span><b>${fmtMoney(n.sharedTotal)}</b></div>
      <div class="mini-row"><span>Pones tú</span><b>${fmtMoney(mine)}</b></div>
      <div class="mini-row"><span>Pone ${escapeAttr(partnerName())}</span><b>${fmtMoney(n.sharedTotal - mine)}</b></div></div>`;
  }
  return html;
}
function saveCoupleName(){
  const el = document.getElementById('cp-name');
  const v = el ? el.value.trim() : '';
  if(!v){ showToast('Escribe su nombre.'); return; }
  coupleData().partnerName = v; coupleSave(); render();
}

// ---------------------------------------------------------------- PLAN DE DEUDAS
function setPlanExtra(v){ const cd = coupleData(); cd.plan.extra = Math.max(0, Math.round(parseMoney(v))); coupleSave(); render(); }
function setPlanMethod(m){ const cd = coupleData(); cd.plan.method = m; coupleSave(); render(); }
function toggleExcludeMine(id){ const cd = coupleData(); if(cd.excludeMine[id]) delete cd.excludeMine[id]; else cd.excludeMine[id] = true; coupleSave(); render(); }

function renderCouplePlan(){
  const cd = coupleData();
  const allDebts = coupleDebts();
  const debts = allDebts.filter(d => !d.excluded);
  const extra = Math.max(0, cd.plan.extra || 0);
  if(!allDebts.length){
    return `<div class="card"><p class="empty-chart">No hay deudas con saldo todavía. Agrega las tuyas en <b>Deudas</b> y las de ${escapeAttr(partnerName())} en su pestaña.</p>
      <div class="btn-row" style="margin-top:10px;"><button class="btn" style="flex:1;" onclick="setTab('debts')">Mis deudas</button><button class="btn" style="flex:1;" onclick="setCoupleSeg('ella')">Deudas de ${escapeAttr(partnerName())}</button></div></div>`;
  }
  const av = simulateDebts(debts, extra, 'avalanche');
  const sn = simulateDebts(debts, extra, 'snowball');
  const mn = simulateDebts(debts, 0, 'minimos');
  const method = cd.plan.method === 'snowball' ? 'snowball' : 'avalanche';
  const sel = method === 'snowball' ? sn : av;
  const n = coupleNumbers();
  const noRate = debts.filter(d => !d.rate).length;
  const noMin = debts.filter(d => !d.min).length;

  let html = `<div class="card">
    <div class="card-head"><div><p class="card-title">¿Cuánto extra pueden poner al mes?</p><p class="card-sub">Además de los mínimos (${fmtMoney(av.budget - extra)} al mes)</p></div></div>
    <div class="field-row"><input type="text" inputmode="decimal" id="plan-extra" value="${extra}" onchange="setPlanExtra(this.value)" style="font-family:var(--mono);font-size:18px;">
      <button class="btn btn-primary" onclick="setPlanExtra(document.getElementById('plan-extra').value)">Calcular</button></div>
    <div class="q-cats" style="margin-top:8px;">${[0,50,100,200,300,500].map(v => `<button class="chip ${extra===v?'active':''}" onclick="setPlanExtra(${v})">+${fmtMoney(v).replace(/\.00$/,'')}</button>`).join('')}
      ${n.free > 1 ? `<button class="chip ${extra===Math.floor(n.free)?'active':''}" onclick="setPlanExtra(${Math.floor(n.free)})">Lo que les sobra (${fmtMoney(Math.floor(n.free)).replace(/\.00$/,'')})</button>` : ''}</div>
  </div>`;

  const box = (key, title, s, tag) => `<button class="plan-box ${method===key?'on':''}" onclick="setPlanMethod('${key}')">
      <span class="pb-tag">${tag}</span>
      <span class="pb-title">${title}</span>
      <span class="pb-big">${s.stuck ? 'Nunca' : fmtMonths(s.months)}</span>
      <span class="pb-sub">${s.stuck ? 'Los pagos no cubren el interés' : monthsToDate(s.months)}</span>
      <span class="pb-sub">Intereses: <b>${fmtMoney(s.totalInterest)}</b></span>
    </button>`;
  html += `<div class="plan-grid">
    ${box('avalanche', 'Avalancha', av, 'Primero la de más interés')}
    ${box('snowball', 'Bola de nieve', sn, 'Primero la más pequeña')}
  </div>`;
  const saved = !mn.stuck && !av.stuck ? mn.totalInterest - av.totalInterest : null;
  html += `<div class="card">
    ${av.stuck ? `<div class="error-box" style="margin-top:0;">Con ${fmtMoney(av.budget)} al mes no alcanza ni para el interés. Suban el extra o revisen los mínimos.</div>` : ''}
    ${!av.stuck ? `<p class="item-meta" style="margin:0;">${mn.stuck ? 'Pagando solo los mínimos, las deudas <b>no se terminarían nunca</b>.' : `Pagando solo los mínimos tardarían <b>${fmtMonths(mn.months)}</b> y pagarían <b>${fmtMoney(mn.totalInterest)}</b> de intereses.`}
      ${saved !== null && saved > 1 ? ` Con la avalancha se ahorran <b style="color:var(--good);">${fmtMoney(saved)}</b> y terminan ${fmtMonths(mn.months - av.months)} antes.` : ''}
      ${Math.abs(sn.totalInterest - av.totalInterest) > 1 && !sn.stuck ? ` La bola de nieve cuesta ${fmtMoney(sn.totalInterest - av.totalInterest)} más en intereses, pero se ven deudas terminadas más rápido (motiva).` : ''}</p>` : ''}
    ${noRate ? `<p class="item-meta" style="margin:8px 0 0 0;color:var(--warn);">${noRate} deuda${noRate===1?'':'s'} sin interés anotado: si tienen interés, pónselo para que el orden sea el correcto.</p>` : ''}
    ${noMin ? `<p class="item-meta" style="margin:8px 0 0 0;">${noMin} deuda${noMin===1?'':'s'} sin pago mínimo: solo reciben plata cuando les llega el turno.</p>` : ''}
  </div>`;

  if(!sel.stuck){
    html += `<div class="card">
      <div class="card-head"><div><p class="card-title">Este mes</p><p class="card-sub">Cuánto ponerle a cada deuda (${fmtMoney(sel.budget)} en total)</p></div></div>
      ${sel.order.map((id, i) => {
        const d = sel.debts.find(x => x.id === id); const src = debts.find(x => x.id === id);
        const pay = sel.firstMonth[id] || 0;
        const isTarget = pay > (src.min + 0.01);
        return `<div class="item-row ${i===0?'list-first':''}">
          <div class="item-main"><div class="item-name">${escapeAttr(d.name)} ${isTarget ? '<span class="tag-good">← aquí va el extra</span>' : ''}</div>
            <div class="item-meta"><i class="own-dot" style="background:${ownerColor(d.owner)};"></i>${escapeAttr(ownerLabel(d.owner))} · debe ${fmtMoney(src.balance)}${src.rate ? ' · ' + src.rate + '%' : ''} · mínimo ${fmtMoney(src.min)}</div></div>
          <div class="item-amounts"><span class="item-amount">${fmtMoney(pay)}</span></div>
        </div>`;
      }).join('')}
    </div>`;

    html += `<div class="card">
      <div class="card-head"><div><p class="card-title">Orden y fecha en que se termina cada una</p><p class="card-sub">Cuando una se acaba, su pago pasa a la siguiente</p></div></div>
      ${sel.debts.slice().sort((a,b) => a.paidOff - b.paidOff).map((d, i) => {
        const pct = sel.months ? Math.max(4, d.paidOff / sel.months * 100) : 0;
        return `<div class="plan-step ${i===0?'list-first':''}">
          <div class="ps-head"><span class="ps-n">${i+1}</span><span class="ps-name">${escapeAttr(d.name)}</span><span class="ps-when">${monthsToDate(d.paidOff)}</span></div>
          <div class="ps-bar"><span style="width:${pct.toFixed(1)}%;background:${ownerColor(d.owner)};"></span></div>
          <div class="item-meta">${escapeAttr(ownerLabel(d.owner))} · ${fmtMoney(d.startBal)} · en ${fmtMonths(d.paidOff)} · intereses ${fmtMoney(d.interest)}</div>
        </div>`;
      }).join('')}
      <p class="item-meta" style="margin-top:10px;">Es una proyección: supone que cada mes pagan lo mismo y que el interés es anual sobre el saldo. Se recalcula sola cuando los pagos del banco restan de las deudas.</p>
    </div>`;
  }

  const mine = allDebts.filter(d => d.owner === 'yo');
  if(mine.length){
    html += `<div class="card">
      <div class="card-head"><div><p class="card-title">Tus deudas en el plan</p><p class="card-sub">Quita las que no quieras incluir (no se borran de tu app)</p></div></div>
      ${mine.map((d,i) => `<label class="check-row ${i===0?'list-first':''}" style="display:flex;gap:10px;align-items:center;padding:8px 0;">
        <input type="checkbox" ${d.excluded?'':'checked'} onchange="toggleExcludeMine('${d.srcId}')" style="width:20px;height:20px;">
        <span style="flex:1;">${escapeAttr(d.name)} <span class="item-meta" style="display:inline;">· ${fmtMoney(d.balance)}</span></span></label>`).join('')}
    </div>`;
  }
  return html;
}

// ---------------------------------------------------------------- SU PESTAÑA (datos de ella)
const COUPLE_CATS = { income:{label:'Ingreso', dir:'in'}, gasto:{label:'Gasto', dir:'out'}, fixed:{label:'Gasto fijo', dir:'out'},
                      debt:{label:'Pago de deuda', dir:'out'}, ignore:{label:'Entre ustedes / propio', dir:'both'} };
function coupleGuessCat(t){
  const d = t.desc || '';
  if(OWN_RE.test(d)) return 'ignore';
  if(t.dir === 'in') return 'income';
  if(/card ?(pmt|payment)|credit ?card|loan|capital one|discover|chase card|amex|american express|synchrony|affirm|klarna|afterpay|navient|nelnet|mohela|sallie|santander consumer|ally auto|car payment/i.test(d)) return 'debt';
  if(/rent|insurance|geico|progressive|state farm|verizon|t-mobile|tmobile|at&t|pseg|con ?ed|spectrum|optimum|xfinity|comcast|netflix|spotify|hulu|disney|apple\.com|google \*|youtube|gym|planet fitness/i.test(d)) return 'fixed';
  return 'gasto';
}
async function handleCoupleFiles(input){
  const files = Array.from(input.files || []);
  if(!files.length) return;
  coupleImportBusy = true; coupleImportMsg = null; coupleImportErr = null; render();
  const found = [], errors = [];
  let endBalance = null;
  for(const f of files){
    try{
      const isPdf = /\.pdf$/i.test(f.name) || f.type === 'application/pdf';
      let txs;
      if(isPdf){
        const lines = await pdfToLines(await readFileBuffer(f));
        if(!lines.length){ errors.push(f.name + ': el PDF no tiene texto (parece escaneado)'); continue; }
        const r = parsePdfStatementLines(lines);
        txs = r.txs; if(r.endBalance !== null) endBalance = r.endBalance;
      } else {
        txs = parseStatement(await readFileText(f)).txs;
      }
      if(!txs.length){ errors.push(f.name + ': no encontré movimientos'); continue; }
      found.push(...txs);
    }catch(e){ errors.push(f.name + ': ' + (e && e.message ? e.message : 'no se pudo leer')); }
  }
  const cd = coupleData();
  const have = new Set((cd.txs || []).map(txFingerprint));
  const seenNow = new Set();
  const impId = 'pi' + Date.now();
  let added = 0, dup = 0;
  found.forEach((t, i) => {
    const fp = txFingerprint(t);
    if(have.has(fp) || seenNow.has(fp)){ dup++; return; }
    seenNow.add(fp);
    const tx = { id:'p' + Date.now().toString(36) + '_' + i, date:t.date, desc:t.desc, amount:t.amount, dir:t.dir, imp:impId };
    tx.category = coupleGuessCat(tx);
    const rule = (cd.rules || {})[tx.dir + '|' + merchantKey(tx.desc)];
    if(rule) tx.category = rule;
    cd.txs.push(tx); added++;
  });
  if(found.length){
    const dates = found.map(t => t.date).sort();
    cd.imports.push({ id:impId, date:todayISO(), from:dates[0], to:dates[dates.length-1], added, dup, files:files.length, endBalance });
    coupleSave();
  }
  coupleImportBusy = false;
  coupleImportMsg = found.length ? `${added} movimiento${added===1?'':'s'} nuevo${added===1?'':'s'}${dup ? ` · ${dup} repetido${dup===1?'':'s'} (no se cuentan dos veces)` : ''}. Revisa abajo que cada uno tenga la categoría correcta.` : null;
  coupleImportErr = errors.length ? errors.join(' · ') : null;
  input.value = '';
  render();
}
function undoCoupleImport(id){
  const cd = coupleData();
  cd.txs = cd.txs.filter(t => t.imp !== id);
  cd.imports = cd.imports.filter(x => x.id !== id);
  confirmingDeleteId = null; coupleSave(); render();
}
function setCoupleTxCat(id, cat){
  const cd = coupleData();
  const t = cd.txs.find(x => x.id === id); if(!t) return;
  const key = t.dir + '|' + merchantKey(t.desc);
  cd.rules = cd.rules || {}; cd.rules[key] = cat;
  // Los demás del mismo comercio/persona se cambian solos.
  let n = 0;
  cd.txs.forEach(x => { if(x.dir + '|' + merchantKey(x.desc) === key && x.category !== cat){ x.category = cat; n++; } });
  coupleSave(); render();
  if(n > 1) showToast(`Cambié ${n} movimientos del mismo comercio.`);
}
function removeCoupleTx(id){ const cd = coupleData(); cd.txs = cd.txs.filter(t => t.id !== id); coupleSave(); render(); }

function submitCoupleDebt(){
  const g = id => (document.getElementById(id) || {}).value || '';
  const name = g('cpd-name').trim();
  if(!name){ showToast('Ponle un nombre a la deuda.'); return; }
  const cd = coupleData();
  const item = { name, balance:parseMoney(g('cpd-bal')), rate:parseMoney(g('cpd-rate')), min:parseMoney(g('cpd-min')), owner: g('cpd-owner') === 'ambos' ? 'ambos' : 'ella' };
  if(coupleDebtEdit && coupleDebtEdit !== 'new'){
    const i = cd.debts.findIndex(d => d.id === coupleDebtEdit);
    if(i > -1) cd.debts[i] = Object.assign({}, cd.debts[i], item);
  } else cd.debts.push(Object.assign({ id:'pd' + Date.now(), createdAt:todayISO() }, item));
  coupleDebtEdit = null; coupleSave(); render();
}
function removeCoupleDebt(id){ const cd = coupleData(); cd.debts = cd.debts.filter(d => d.id !== id); coupleDebtEdit = null; confirmingDeleteId = null; coupleSave(); render(); }
function submitCoupleIncome(){
  const amt = parseMoney((document.getElementById('cpi-amt') || {}).value);
  const date = (document.getElementById('cpi-date') || {}).value;
  const src = ((document.getElementById('cpi-src') || {}).value || '').trim();
  if(!amt || !date){ showToast('Pon el monto y la fecha.'); return; }
  coupleData().incomes.push({ id:'pin' + Date.now(), amount:amt, date, source:src });
  coupleIncomeOpen = false; coupleSave(); render();
}
function removeCoupleIncome(id){ const cd = coupleData(); cd.incomes = cd.incomes.filter(x => x.id !== id); coupleSave(); render(); }
let coupleRenaming = false;
function renameCouplePartner(){ coupleRenaming = !coupleRenaming; render(); }
function saveCoupleRename(){
  const el = document.getElementById('cp-rename');
  const v = el ? el.value.trim() : '';
  if(!v){ showToast('Escribe su nombre.'); return; }
  coupleData().partnerName = v; coupleRenaming = false; coupleSave(); render();
}

function renderCoupleHer(){
  const cd = coupleData();
  const pn = escapeAttr(partnerName());
  let html = '';
  // Deudas de ella
  const ed = coupleDebtEdit && coupleDebtEdit !== 'new' ? cd.debts.find(d => d.id === coupleDebtEdit) : null;
  const herTotal = cd.debts.reduce((s,d) => s + Math.max(0, +d.balance || 0), 0);
  html += `<div class="card">
    <div class="card-head"><div><p class="card-title">Deudas de ${pn}</p><p class="card-sub">${fmtMoney(herTotal)} en total</p></div><button class="card-link" onclick="renameCouplePartner()">Cambiar nombre</button></div>
    ${coupleRenaming ? `<div class="field-row" style="margin-bottom:10px;"><input type="text" id="cp-rename" value="${escapeAttr(cd.partnerName||'')}" placeholder="Su nombre"><button class="btn btn-primary" onclick="saveCoupleRename()">Guardar</button></div>` : ''}
    ${cd.debts.length ? cd.debts.map((d,i) => `<div class="item-row ${i===0?'list-first':''}">
        <div class="item-main clickable" onclick="coupleDebtEdit='${d.id}';render()">
          <div class="item-name">${escapeAttr(d.name)}${d.owner==='ambos' ? ' <span class="tag-good" style="color:var(--s3);">de los dos</span>' : ''}</div>
          <div class="item-meta">Debe ${fmtMoney(+d.balance||0)}${d.rate ? ' · ' + d.rate + '% interés' : ''} · mínimo ${fmtMoney(+d.min||0)}/mes</div></div>
        ${confirmingDeleteId === d.id ? `<span class="del-confirm"><button class="item-del-yes" onclick="removeCoupleDebt('${d.id}')">Sí, borrar</button><button class="item-del-no" onclick="cancelDelete()">Cancelar</button></span>`
          : `<button class="item-del" onclick="requestDelete('${d.id}')">×</button>`}
      </div>`).join('') : '<div class="empty-note" style="padding-top:0;">Sin deudas anotadas.</div>'}
    ${coupleDebtEdit ? `<div class="add-form">
      <div class="field"><label>Nombre</label><input type="text" id="cpd-name" placeholder="Ej: Tarjeta Bank of America" value="${ed ? escapeAttr(ed.name) : ''}"></div>
      <div class="field-row">
        <div class="field"><label>Saldo que debe hoy</label><input type="text" inputmode="decimal" id="cpd-bal" placeholder="0.00" value="${ed ? ed.balance : ''}"></div>
        <div class="field"><label>Interés % al año</label><input type="text" inputmode="decimal" id="cpd-rate" placeholder="Ej: 24.99" value="${ed && ed.rate ? ed.rate : ''}"></div>
      </div>
      <div class="field-row">
        <div class="field"><label>Pago mínimo al mes</label><input type="text" inputmode="decimal" id="cpd-min" placeholder="0.00" value="${ed && ed.min ? ed.min : ''}"></div>
        <div class="field"><label>¿De quién es?</label><select id="cpd-owner"><option value="ella" ${!ed || ed.owner!=='ambos'?'selected':''}>De ${pn}</option><option value="ambos" ${ed && ed.owner==='ambos'?'selected':''}>De los dos</option></select></div>
      </div>
      <p class="item-meta">Tus deudas no se anotan aquí: el plan las toma solas de tu pestaña Deudas.</p>
      <div class="field-row"><button class="btn btn-primary" onclick="submitCoupleDebt()">${ed ? 'Guardar cambios' : 'Añadir deuda'}</button><button class="btn" onclick="coupleDebtEdit=null;render()">Cancelar</button></div>
    </div>` : `<button class="add-toggle-btn" onclick="coupleDebtEdit='new';render()">+ Añadir deuda de ${pn}</button>`}
  </div>`;

  // Subir extractos
  html += `<div class="card">
    <div class="card-head"><div><p class="card-title">Extractos de ${pn}</p><p class="card-sub">Bank of America · PDF o CSV · se guardan aparte de los tuyos</p></div></div>
    ${coupleImportBusy ? `<div class="upload-zone" style="cursor:default;"><span class="ico">${ICONS.import}</span><span class="big">Leyendo extractos…</span><span class="small">Unos segundos con los PDF</span></div>`
      : `<label class="upload-zone" for="couple-input"><span class="ico">${ICONS.import}</span><span class="big">Subir extractos de ${pn}</span><span class="small">Puedes elegir varios meses a la vez</span></label>`}
    <input type="file" id="couple-input" accept=".pdf,application/pdf,.csv,text/csv,text/plain" multiple style="display:none" onchange="handleCoupleFiles(this)">
    ${coupleImportMsg ? `<p class="item-meta note-good" style="margin-top:10px;">${escapeAttr(coupleImportMsg)}</p>` : ''}
    ${coupleImportErr ? `<div class="error-box">${escapeAttr(coupleImportErr)}</div>` : ''}
    <p class="item-meta" style="margin-top:10px;">En bankofamerica.com: <b>Statements & Documents</b> → descarga el PDF de cada mes. Los movimientos repetidos no se cuentan dos veces.</p>
    ${(cd.imports || []).slice().reverse().map((imp,i) => `<div class="item-row ${i===0?'list-first':''}">
      <div class="item-main"><div class="item-name">${fmtShortDate(imp.from)} – ${fmtShortDate(imp.to)}</div><div class="item-meta">Subido el ${fmtShortDate(imp.date)} · ${imp.added} nuevos${imp.dup ? ` · ${imp.dup} repetidos` : ''}</div></div>
      ${confirmingDeleteId === imp.id ? `<span class="del-confirm"><button class="item-del-yes" onclick="undoCoupleImport('${imp.id}')">Sí, deshacer</button><button class="item-del-no" onclick="cancelDelete()">No</button></span>`
        : `<button class="btn" style="padding:7px 11px;font-size:12px;" onclick="requestDelete('${imp.id}')">Deshacer</button>`}
    </div>`).join('')}
  </div>`;

  // Ingresos a mano
  const incs = (cd.incomes || []).slice().sort((a,b) => b.date.localeCompare(a.date));
  html += `<div class="card">
    <div class="card-head"><div><p class="card-title">Ingresos anotados a mano</p><p class="card-sub">Lo que no sale en el banco (efectivo, propinas…)</p></div></div>
    ${incs.slice(0, 12).map((x,i) => `<div class="item-row ${i===0?'list-first':''}"><div class="item-main"><div class="item-name">${escapeAttr(x.source || 'Ingreso')}</div><div class="item-meta">${fmtShortDate(x.date)}</div></div>
      <div class="item-amounts"><span class="item-amount in">+${fmtMoney(x.amount)}</span></div><button class="item-del" onclick="removeCoupleIncome('${x.id}')">×</button></div>`).join('')}
    ${coupleIncomeOpen ? `<div class="add-form">
      <div class="field-row"><div class="field"><label>Monto</label><input type="text" inputmode="decimal" id="cpi-amt" placeholder="0.00"></div>
      <div class="field"><label>Fecha</label><input type="date" id="cpi-date" value="${todayISO()}"></div></div>
      <div class="field"><label>De dónde</label><input type="text" id="cpi-src" placeholder="Ej: trabajo, propinas"></div>
      <div class="field-row"><button class="btn btn-primary" onclick="submitCoupleIncome()">Añadir</button><button class="btn" onclick="coupleIncomeOpen=false;render()">Cancelar</button></div>
    </div>` : `<button class="add-toggle-btn" onclick="coupleIncomeOpen=true;render()">+ Añadir ingreso</button>`}
  </div>`;

  // Movimientos de ella
  const mk = currentMonthKey();
  const txs = (cd.txs || []).filter(t => coupleMovFilter === 'todo' || t.date.slice(0,7) === mk).sort((a,b) => b.date.localeCompare(a.date));
  const sumCat = c => txs.filter(t => t.category === c).reduce((s,t) => s + t.amount, 0);
  html += `<div class="card">
    <div class="card-head"><div><p class="card-title">Movimientos de ${pn}</p><p class="card-sub">${coupleMovFilter === 'todo' ? 'Todos' : 'Este mes'} · entró ${fmtMoney(sumCat('income'))} · gastos ${fmtMoney(sumCat('gasto') + sumCat('fixed'))} · deudas ${fmtMoney(sumCat('debt'))}</p></div>
      <button class="card-link" onclick="coupleMovFilter=coupleMovFilter==='todo'?'mes':'todo';render()">${coupleMovFilter === 'todo' ? 'Solo este mes' : 'Ver todos'}</button></div>
    ${txs.length ? txs.slice(0, 150).map((t,i) => `<div class="item-row ${i===0?'list-first':''}" style="align-items:flex-start;">
      <div class="item-main"><div class="item-name">${escapeAttr(niceName(merchantKey(t.desc)) || t.desc)}</div><div class="item-meta">${fmtShortDate(t.date)} · ${escapeAttr(t.desc)}</div></div>
      <div class="item-amounts" style="display:flex;flex-direction:column;align-items:flex-end;gap:6px;">
        <span class="item-amount ${t.dir==='in'?'in':''}">${t.dir==='in'?'+':'-'}${fmtMoney(t.amount)}</span>
        <select class="cat-select" onchange="setCoupleTxCat('${t.id}', this.value)">${Object.keys(COUPLE_CATS).filter(k => COUPLE_CATS[k].dir === t.dir || COUPLE_CATS[k].dir === 'both').map(k => `<option value="${k}" ${k===t.category?'selected':''}>${COUPLE_CATS[k].label}</option>`).join('')}</select>
      </div></div>`).join('') + (txs.length > 150 ? `<p class="item-meta">Mostrando 150 de ${txs.length}.</p>` : '')
      : `<div class="empty-note" style="padding-top:0;">${(cd.txs||[]).length ? 'Nada este mes. Toca "Ver todos".' : 'Sube sus extractos para ver sus movimientos.'}</div>`}
    <p class="item-meta" style="margin-top:10px;">Marca como <b>Entre ustedes / propio</b> lo que se pasan entre los dos o a la cuenta conjunta, para que no se cuente como gasto ni ingreso.</p>
  </div>`;
  return html;
}

// ---------------------------------------------------------------- GASTOS COMPARTIDOS
function submitCoupleShared(){
  const g = id => (document.getElementById(id) || {}).value || '';
  const name = g('cps-name').trim();
  const amount = parseMoney(g('cps-amt'));
  if(!name || !amount){ showToast('Pon el nombre y el monto.'); return; }
  const pct = Math.max(0, Math.min(100, parseMoney(g('cps-pct')) || 50));
  const cd = coupleData();
  const item = { name, amount, myPct:pct, day: Math.max(1, Math.min(31, parseInt(g('cps-day'), 10) || 1)) };
  if(coupleSharedEdit && coupleSharedEdit !== 'new'){
    const i = cd.shared.findIndex(x => x.id === coupleSharedEdit);
    if(i > -1) cd.shared[i] = Object.assign({}, cd.shared[i], item);
  } else cd.shared.push(Object.assign({ id:'ps' + Date.now() }, item));
  coupleSharedEdit = null; coupleSave(); render();
}
function removeCoupleShared(id){ const cd = coupleData(); cd.shared = cd.shared.filter(x => x.id !== id); confirmingDeleteId = null; coupleSharedEdit = null; coupleSave(); render(); }
function renderCoupleShared(){
  const cd = coupleData();
  const pn = escapeAttr(partnerName());
  const list = cd.shared || [];
  const total = list.reduce((s,x) => s + (+x.amount||0), 0);
  const mine = list.reduce((s,x) => s + (+x.amount||0) * (+x.myPct||0) / 100, 0);
  const ed = coupleSharedEdit && coupleSharedEdit !== 'new' ? list.find(x => x.id === coupleSharedEdit) : null;
  return `<div class="mini-grid">
    <div class="mini"><p class="mini-label">Pones tú</p><p class="mini-value">${fmtMoney(mine)}</p><p class="mini-foot">al mes</p></div>
    <div class="mini"><p class="mini-label">Pone ${pn}</p><p class="mini-value">${fmtMoney(total - mine)}</p><p class="mini-foot">al mes</p></div>
  </div>
  <div class="card">
    <div class="card-head"><div><p class="card-title">Gastos de la casa</p><p class="card-sub">Renta, servicios, mercado… y cuánto pone cada uno</p></div></div>
    ${list.length ? list.map((x,i) => `<div class="item-row ${i===0?'list-first':''}">
      <div class="item-main clickable" onclick="coupleSharedEdit='${x.id}';render()"><div class="item-name">${escapeAttr(x.name)}</div>
        <div class="item-meta">Día ${x.day || 1} · tú ${fmtMoney((+x.amount||0) * (+x.myPct||0) / 100)} (${x.myPct}%) · ${pn} ${fmtMoney((+x.amount||0) * (100 - (+x.myPct||0)) / 100)}</div></div>
      <div class="item-amounts"><span class="item-amount">${fmtMoney(+x.amount||0)}</span></div>
      ${confirmingDeleteId === x.id ? `<span class="del-confirm"><button class="item-del-yes" onclick="removeCoupleShared('${x.id}')">Sí, borrar</button><button class="item-del-no" onclick="cancelDelete()">Cancelar</button></span>`
        : `<button class="item-del" onclick="requestDelete('${x.id}')">×</button>`}
    </div>`).join('') : '<div class="empty-note" style="padding-top:0;">Todavía no hay gastos compartidos.</div>'}
    ${coupleSharedEdit ? `<div class="add-form">
      <div class="field"><label>Nombre</label><input type="text" id="cps-name" placeholder="Ej: Renta" value="${ed ? escapeAttr(ed.name) : ''}"></div>
      <div class="field-row">
        <div class="field"><label>Monto al mes</label><input type="text" inputmode="decimal" id="cps-amt" placeholder="0.00" value="${ed ? ed.amount : ''}"></div>
        <div class="field"><label>Día de pago</label><input type="number" inputmode="numeric" min="1" max="31" id="cps-day" value="${ed ? (ed.day || 1) : 1}"></div>
      </div>
      <div class="field"><label>¿Qué % pones tú?</label><input type="text" inputmode="decimal" id="cps-pct" value="${ed ? ed.myPct : 50}"></div>
      <p class="item-meta">Si tu parte ya está en tus gastos fijos, no hace falta cambiar nada allá: esto es solo para repartir.</p>
      <div class="field-row"><button class="btn btn-primary" onclick="submitCoupleShared()">${ed ? 'Guardar cambios' : 'Añadir'}</button><button class="btn" onclick="coupleSharedEdit=null;render()">Cancelar</button></div>
    </div>` : `<button class="add-toggle-btn" onclick="coupleSharedEdit='new';render()">+ Añadir gasto compartido</button>`}
  </div>
  <div class="card"><div class="card-head"><p class="card-title">Total de la casa</p><span class="item-amount">${fmtMoney(total)}/mes</span></div>
    <p class="item-meta" style="margin:0;">Cuando abran la cuenta conjunta, pueden programar estos pagos desde ahí.</p></div>`;
}

// Tarjeta corta en Inicio: solo aparece cuando ya configuraste la parte en pareja.
function renderCoupleHomeCard(){
  const cd = coupleData();
  if(!cd.partnerName) return '';
  const debts = coupleDebts().filter(d => !d.excluded);
  if(!debts.length) return '';
  const s = simulateDebts(debts, Math.max(0, cd.plan.extra || 0), cd.plan.method === 'snowball' ? 'snowball' : 'avalanche');
  const total = debts.reduce((a,d) => a + d.balance, 0);
  return `<div class="card clickable" style="cursor:pointer;" onclick="openCouple('resumen')">
    <div class="card-head" style="margin-bottom:4px;"><div><p class="card-title" style="font-size:15px;">Nosotros · ${escapeAttr(myName())} y ${escapeAttr(partnerName())}</p>
      <p class="card-sub">Deuda de los dos ${fmtMoney(total)}</p></div><span class="chevron">›</span></div>
    <p class="item-meta" style="margin:0;">${s.stuck ? 'Con los pagos de hoy las deudas no bajan. Revisen el plan.' : `Libres de deudas en <b style="color:var(--good);">${fmtMonths(s.months)}</b> (${monthsToDate(s.months)})`}</p>
  </div>`;
}
