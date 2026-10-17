
// =====================================================================
//  NAVEGACIÓN (pestañas de abajo)
// =====================================================================
let activeTab = 'home';      // home | mov | debts | import | more
let movSeg = 'incomes';      // incomes | hormiga | bank | loans
let debtSeg = 'debts';       // debts | expenses
let bankFilter = 'all';
let chartMonthOffset = 0;    // 0 = este mes, -1 = el mes pasado…
let chartWeekSel = null;     // semana seleccionada en la gráfica de ingresos vs gastos

const TAB_FOR_KIND = {
  incomes:['mov','incomes'], hormiga:['mov','hormiga'], loans:['mov','loans'],
  debts:['debts','debts'], expenses:['debts','expenses'],
  'savings-emergency':['more',null], 'savings-investments':['more',null]
};

function setTab(t){
  activeTab = t;
  editingItem = null; confirmingDeleteId = null; formOpen = {};
  render();
  window.scrollTo(0,0);
}
function setMovSeg(s){ movSeg = s; editingItem = null; confirmingDeleteId = null; render(); }
function setDebtSeg(s){ debtSeg = s; editingItem = null; confirmingDeleteId = null; render(); }
function setBankFilter(f){ bankFilter = f; render(); }
function shiftChartMonth(d){ chartMonthOffset = Math.min(0, chartMonthOffset + d); render(); }
function selectChartWeek(i){ chartWeekSel = i; render(); }

// Botón "atrás" del teléfono: primero cierra lo abierto, luego vuelve a Inicio, y solo entonces sale.
function appBack(){
  if(typeof tourActive === 'function' && tourActive()){ tourEnd(); return true; }
  if(document.querySelector('.video-wrap')){ closeVideo(); return true; }
  if(sheet){ closeSheet(); return true; }
  if(importPreview){ cancelImport(); return true; }
  if(editingItem || Object.keys(formOpen).some(k=>formOpen[k])){ editingItem = null; formOpen = {}; render(); return true; }
  if(activeTab !== 'home'){ setTab('home'); return true; }
  return false;
}

let toastTimer = null;
function showToast(msg){
  const el = document.getElementById('toast');
  if(!el) return;
  el.textContent = msg;
  el.classList.add('show');
  if(toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(()=>el.classList.remove('show'), 3600);
}

function todayISO(){
  const d = new Date();
  return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
}
function fmtShortDate(iso){
  return new Date(iso+'T00:00:00').toLocaleDateString('es-ES',{day:'numeric',month:'short'});
}
function sumInRange(list, s, e){
  return list.filter(x=>{ const d=new Date(x.date+'T00:00:00'); return d>=s && d<=e; })
             .reduce((a,x)=>a+(x.amount||0),0);
}

// =====================================================================
//  MOVIMIENTOS DEL BANCO
// =====================================================================
const BANK_CATS = {
  income:  { label:'Ingreso',        dir:'in'   },
  hormiga: { label:'Gasto hormiga',  dir:'out'  },
  gasto:   { label:'Otros gastos',   dir:'out'  },
  fixed:   { label:'Gasto fijo',     dir:'out'  },
  debt:    { label:'Pago de deuda',  dir:'out'  },
  loan:    { label:'Préstamo (le presté)', dir:'out' },
  loanback:{ label:'Me devolvieron un préstamo', dir:'in' },
  trading: { label:'Trading',        dir:'both' },
  own:     { label:'Movimiento propio', dir:'both' },
  ignore:  { label:'No contar',      dir:'both' }
};
// Depósitos/retiros de trading y movimientos entre tus cuentas (cajero, transferencias propias)
// no cuentan como ingreso ni como gasto.
const TRADING_RE = /POCKET ?OPTION|POCKETOPTION|PO TRADE|QUOTEX|IQ ?OPTION|OLYMP|BINOMO|DERIV|BINANCE|COINBASE|SKRILL|NETELLER|PERFECT ?MONEY|BYBIT|KRAKEN/i;
const OWN_RE = /\bATM\b|CASH WITHDRAWAL|WITHDRAWAL AT|TRANSFER TO (SAV|CHK|CHECKING|SAVINGS)|TRANSFER FROM (SAV|CHK|CHECKING|SAVINGS)|ONLINE TRANSFER (TO|FROM) (SAV|CHK)/i;
// Orden fijo de las categorías de gasto en las gráficas (cada una con su color fijo).
const SPEND_CATS = ['hormiga','gasto','fixed','debt'];
const CAT_COLOR = { hormiga:'var(--s1)', gasto:'var(--s2)', fixed:'var(--s3)', debt:'var(--s4)', income:'var(--good)', ignore:'var(--ink-faint)' };
// Los gastos de débito de hasta este monto se proponen como "hormiga" la primera vez.
const HORMIGA_AUTO_MAX = 25;

function bankList(){ return (data.bank && data.bank.transactions) || []; }
function bankByCat(cat){ return bankList().filter(t=>t.category===cat); }

// Ingresos y gastos hormiga "de verdad": lo que anotas a mano + lo que viene del banco.
function allIncomes(){
  return data.incomes.concat(bankByCat('income').map(t=>({id:t.id, amount:t.amount, date:t.date, source:t.desc, name:displayName(t), bank:true})));
}
function allHormiga(){
  return data.hormiga.concat(bankByCat('hormiga').map(t=>({id:t.id, amount:t.amount, date:t.date, desc:displayName(t), bank:true})));
}
// Gastos variables que no son hormiga (mercado, compras grandes…). Solo vienen del banco.
function allOtherSpend(){ return bankByCat('gasto'); }

// Trading del periodo: lo que mandaste a la plataforma, lo que retiraste y el resultado.
function tradingSummary(s, e){
  let dep = 0, wd = 0;
  bankByCat('trading').forEach(t => {
    const d = new Date(t.date+'T00:00:00');
    if(d < s || d > e) return;
    if(t.dir === 'out') dep += t.amount; else wd += t.amount;
  });
  return { dep, wd, net: wd - dep, any: dep > 0 || wd > 0 };
}
function catOptions(dir, selected){
  return Object.keys(BANK_CATS)
    .filter(k => BANK_CATS[k].dir === dir || BANK_CATS[k].dir === 'both')
    .map(k => `<option value="${k}" ${k===selected?'selected':''}>${BANK_CATS[k].label}</option>`).join('');
}

// ---- Lectura del archivo CSV ----
function parseCSV(text){
  text = String(text).replace(/^﻿/, '');
  // Algunos bancos usan punto y coma; si la primera línea tiene más ";" que ",", se usa ";".
  const firstLine = text.split(/\r?\n/)[0] || '';
  const sep = (firstLine.split(';').length > firstLine.split(',').length) ? ';' : ',';
  const rows = [];
  let row = [], cell = '', q = false;
  for(let i=0; i<text.length; i++){
    const ch = text[i];
    if(q){
      if(ch === '"'){ if(text[i+1] === '"'){ cell += '"'; i++; } else q = false; }
      else cell += ch;
    } else if(ch === '"') q = true;
    else if(ch === sep){ row.push(cell); cell = ''; }
    else if(ch === '\n' || ch === '\r'){
      if(ch === '\r' && text[i+1] === '\n') i++;
      row.push(cell); rows.push(row); row = []; cell = '';
    } else cell += ch;
  }
  if(cell !== '' || row.length){ row.push(cell); rows.push(row); }
  return rows.map(r => r.map(c => c.trim())).filter(r => r.some(c => c !== ''));
}

function ymd(y, mo, d){
  if(!(y > 1900) || mo < 1 || mo > 12 || d < 1 || d > 31) return null;
  return y+'-'+String(mo).padStart(2,'0')+'-'+String(d).padStart(2,'0');
}
function parseBankDate(s){
  s = String(s || '').trim();
  if(!s) return null;
  const first = s.split(/[ T]/)[0];
  let m;
  if((m = first.match(/^(\d{4})[-\/](\d{1,2})[-\/](\d{1,2})$/))) return ymd(+m[1], +m[2], +m[3]);
  if((m = first.match(/^(\d{1,2})[\/-](\d{1,2})[\/-](\d{2,4})$/))){
    let y = +m[3]; if(y < 100) y += 2000;
    return ymd(y, +m[1], +m[2]); // formato de EE.UU.: mes/día/año
  }
  if(!/[a-z]/i.test(s)) return null;
  const d = new Date(s);
  if(isNaN(d)) return null;
  return ymd(d.getFullYear(), d.getMonth()+1, d.getDate());
}
function parseBankAmount(s){
  s = String(s == null ? '' : s).trim();
  if(!s) return null;
  let neg = false;
  if(/^\(.*\)$/.test(s)){ neg = true; s = s.slice(1,-1).trim(); }
  if(/-$/.test(s)){ neg = true; s = s.slice(0,-1).trim(); }
  s = s.replace(/USD|US\$|\$|\s|,/gi, '');
  if(s.startsWith('-')){ neg = !neg; s = s.slice(1); }
  if(s.startsWith('+')) s = s.slice(1);
  if(!/^\d*\.?\d+$/.test(s)) return null;
  const n = parseFloat(s);
  return neg ? -n : n;
}

// Encuentra qué columna es la fecha, la descripción y el monto.
function detectColumns(rows){
  for(let h=0; h<Math.min(rows.length, 12); h++){
    const cells = rows[h].map(c => c.toLowerCase());
    if(cells.some(c => parseBankDate(c))) continue; // fila de datos, no de títulos
    const find = (re, not) => cells.findIndex(c => re.test(c) && !(not && not.test(c)));
    const date = find(/date|fecha/);
    if(date < 0) continue;
    const debit = find(/debit|withdraw|retiro|cargo|money out|outflow/, /type|tipo/);
    const credit = find(/credit|deposit|dep[oó]sito|abono|money in|inflow/, /type|tipo/);
    const amount = find(/amount|monto|importe|valor/);
    let desc = find(/description|descripci|payee|memo|details|detalle|concept|narrative/);
    if(desc < 0) desc = find(/name|activity|merchant|comercio/, /type|tipo|date|number|account/);
    const balance = find(/balance|saldo/);
    const type = find(/type|tipo/);
    const hasDC = debit >= 0 && credit >= 0;
    if(hasDC || amount >= 0){
      return { header:h, date, desc, balance, type, debit: hasDC?debit:-1, credit: hasDC?credit:-1, amount: hasDC?-1:amount };
    }
  }
  return guessColumns(rows);
}
// Archivo sin títulos: adivina las columnas mirando el contenido.
function guessColumns(rows){
  const sample = rows.slice(0, 25);
  const width = Math.max(...sample.map(r => r.length));
  let date = -1, bestDate = 0;
  const numeric = [];
  let desc = -1, bestLen = 0;
  for(let c=0; c<width; c++){
    const vals = sample.map(r => r[c] || '');
    const nonEmpty = vals.filter(v => v !== '');
    if(!nonEmpty.length) continue;
    const dates = nonEmpty.filter(v => parseBankDate(v)).length;
    const nums = nonEmpty.filter(v => parseBankAmount(v) !== null).length;
    if(dates / nonEmpty.length > 0.6 && dates > bestDate){ bestDate = dates; date = c; continue; }
    if(nums / nonEmpty.length > 0.8){ numeric.push(c); continue; }
    const avg = nonEmpty.reduce((s,v)=>s+v.length,0) / nonEmpty.length;
    if(avg > bestLen){ bestLen = avg; desc = c; }
  }
  if(date < 0 || !numeric.length) return null;
  const col = { header:-1, date, desc, balance:-1, type:-1, debit:-1, credit:-1, amount:-1 };
  if(numeric.length === 1){ col.amount = numeric[0]; return col; }
  const [a, b] = numeric;
  // Si en cada fila solo una de las dos columnas tiene valor, son débito/crédito.
  const exclusive = sample.every(r => !((r[a]||'') !== '' && (r[b]||'') !== ''));
  if(exclusive){ col.debit = a; col.credit = b; if(numeric[2] !== undefined) col.balance = numeric[2]; }
  else { col.amount = a; col.balance = b; }
  return col;
}

function rowsToTransactions(rows, col){
  const out = [];
  let skipped = 0;
  for(let i=col.header+1; i<rows.length; i++){
    const r = rows[i];
    const date = parseBankDate(r[col.date]);
    if(!date){ skipped++; continue; }
    let amt = null, dir = null;
    if(col.amount >= 0){
      const a = parseBankAmount(r[col.amount]);
      if(a === null || a === 0){ skipped++; continue; }
      dir = a < 0 ? 'out' : 'in';
      amt = Math.abs(a);
      const type = col.type >= 0 ? (r[col.type] || '') : '';
      if(a > 0 && /debit|withdraw|purchase|payment|fee|retiro|cargo|compra/i.test(type) && !/credit|deposit|dep[oó]sito|abono/i.test(type)) dir = 'out';
    } else {
      const d = parseBankAmount(r[col.debit]);
      const c = parseBankAmount(r[col.credit]);
      if(d) { amt = Math.abs(d); dir = 'out'; }
      else if(c){ amt = Math.abs(c); dir = 'in'; }
      else { skipped++; continue; }
    }
    let desc = col.desc >= 0 ? (r[col.desc] || '') : '';
    if(!desc) desc = r.filter((c,j)=> j!==col.date && parseBankAmount(c)===null).join(' ');
    out.push({
      date,
      desc: desc.replace(/\s+/g,' ').trim() || 'Movimiento',
      amount: Math.round(amt*100)/100,
      dir,
      balance: col.balance >= 0 ? parseBankAmount(r[col.balance]) : null
    });
  }
  return { txs: out, skipped };
}

function normDesc(s){ return String(s || '').toUpperCase().replace(/\s+/g,' ').trim(); }
// Huella de un movimiento: misma fecha + mismo monto + misma dirección + misma descripción.
function txFingerprint(t){ return t.date+'|'+t.dir+'|'+Math.round(t.amount*100)+'|'+normDesc(t.desc); }

// Nombre corto del comercio, para agrupar ("VISA DDA PUR 461234 SHELL OIL 5744 JERSEY CITY" → "SHELL OIL").
const DESC_NOISE = /\b(VISA|DDA|PUR|PURCHASE|POS|DEBIT|CREDIT|CARD|CRD|DBT|CHECKCARD|DBCRD|CRCRD|ACH|ELECTRONIC|PMT|WEB|PAYMENT|RECURRING|ONLINE|XFER|AP|AUT|INTL|DES|ID|INDN|CO|PPD|CCD|TO|FROM|THE|SQ|TST|PP|DEPOSIT|DEP|WITHDRAWAL|WDRL|AUTHORIZATION|AUTHORIZ|TEMP|AUTH)\b/g;
function merchantKey(desc){
  const raw = normDesc(desc);
  // Zelle: separar por persona ("ZELLE CARLOS MENDEZ") en vez de juntar todos como "TD ZELLE".
  if(/\bZELLE\b/.test(raw)){
    const after = raw.slice(raw.lastIndexOf('ZELLE') + 5)
      .replace(/^[^A-Z]*/,'').replace(/^(PAYMENT|PMT|SENT|RECEIVED|TRANSFER)\b\s*/,'').replace(/^(TO|FROM)\b\s*/,'')
      .replace(/[^A-Z ]/g,' ').replace(/\s+/g,' ').trim();
    const who = after.split(' ').filter(w => w.length > 1).slice(0,2).join(' ');
    return who ? 'ZELLE ' + who : 'ZELLE';
  }
  if(/\bATM\b/.test(raw)) return /DEP/.test(raw) ? 'ATM DEPOSITO' : 'ATM';
  const s = normDesc(desc).replace(/\b([A-Z])-(?=[A-Z])/g,'$1').replace(/[^A-Z ]/g,' ').replace(DESC_NOISE,' ').replace(/\s+/g,' ').trim();
  const words = s.split(' ').filter(w => w.length > 1);
  return words.slice(0,2).join(' ') || normDesc(desc).slice(0,22) || 'MOVIMIENTO';
}
// Nombre que se muestra: el que tú le pusiste a ese comercio/fuente, o uno limpio sacado del banco.
function displayName(t){
  const key = merchantKey(t.desc);
  return (data.bank.names && data.bank.names[key]) || niceName(key);
}
function niceName(key){
  return key.toLowerCase().replace(/\b\w/g, ch => ch.toUpperCase());
}

function nameMatches(desc, name){
  const words = normDesc(name).replace(/[^A-Z0-9 ]/g,' ').split(' ').filter(w => w.length >= 4 && !/^\d+$/.test(w));
  const d = ' ' + normDesc(desc).replace(/[^A-Z0-9 ]/g,' ') + ' ';
  return words.some(w => d.includes(' '+w));
}
function findRule(key, dir){
  return (data.bank.rules || []).find(r => r.key === key && r.dir === dir);
}
function guessCategory(t){
  const rule = findRule(merchantKey(t.desc), t.dir);
  if(rule && BANK_CATS[rule.category]) return { category: rule.category, auto:'rule' };
  if(TRADING_RE.test(t.desc)) return { category:'trading', auto:'default' };
  const named = (data.bank.names || {})[merchantKey(t.desc)];
  if(named && /trading/i.test(named)) return { category:'trading', auto:'name' };
  if(t.dir === 'out' && OWN_RE.test(t.desc)) return { category:'own', auto:'default' };
  if(t.dir === 'in') return { category:'income', auto:'default' };
  if(data.expenses.some(e => nameMatches(t.desc, e.name))) return { category:'fixed', auto:'name' };
  if(data.debts.some(d => nameMatches(t.desc, d.name))) return { category:'debt', auto:'name' };
  if(t.amount <= HORMIGA_AUTO_MAX) return { category:'hormiga', auto:'default' };
  return { category:'gasto', auto:'default' };
}
// ¿Ya lo anotaste a mano? (mismo monto, hasta 3 días de diferencia) → no se cuenta dos veces.
function findManualMatch(t, used){
  const list = t.dir === 'in' ? data.incomes : data.hormiga;
  const td = new Date(t.date+'T00:00:00');
  return list.find(m => !used.has(m.id)
    && Math.abs((m.amount||0) - t.amount) < 0.005
    && Math.abs((new Date(m.date+'T00:00:00') - td) / 86400000) <= 3);
}

// ---- Vista previa antes de guardar ----
let importPreview = null;
let importError = null;

function readFileText(f){
  return new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(String(r.result || ''));
    r.onerror = () => rej(new Error('no se pudo abrir ' + f.name));
    r.readAsText(f);
  });
}

// Se pueden elegir varios extractos a la vez (por ejemplo los últimos 3 meses).
let importBusy = false;
async function handleStatementFiles(input){
  const files = Array.from(input.files || []);
  if(!files.length) return;
  importError = null;
  importBusy = true; render();
  try{
    const parsed = [];
    for(const f of files){
      const isPdf = /\.pdf$/i.test(f.name) || f.type === 'application/pdf';
      if(isPdf){
        let lines;
        try{ lines = await pdfToLines(await readFileBuffer(f)); }
        catch(e){ parsed.push({ name: f.name, error: 'no pude abrir el PDF (' + (e && e.message ? e.message : 'error') + ')' }); continue; }
        if(!lines.length){ parsed.push({ name: f.name, error: 'el PDF no tiene texto (parece una foto escaneada)' }); continue; }
        const r = parsePdfStatementLines(lines);
        parsed.push({ name: f.name, pdf: r });
      } else {
        parsed.push({ name: f.name, text: await readFileText(f) });
      }
    }
    buildImportPreview(parsed);
  }catch(err){
    importPreview = null;
    importError = 'No pude leer los archivos. Revisa que sean extractos de TD Bank (PDF o CSV) y vuelve a intentarlo.' + (err && err.message ? ' (' + err.message + ')' : '');
  }
  importBusy = false;
  input.value = '';
  render();
  window.scrollTo(0,0);
}

function parseStatement(text){
  if(/^\s*%PDF/.test(text)) throw new Error('es un PDF, no un CSV');
  const rows = parseCSV(text);
  if(!rows.length) throw new Error('el archivo está vacío');
  const col = detectColumns(rows);
  if(!col || col.date < 0) throw new Error('no encontré las columnas de fecha y monto');
  const r = rowsToTransactions(rows, col);
  if(!r.txs.length) throw new Error('no encontré movimientos');
  return r;
}

function buildImportPreview(files){
  const perFile = [];
  const warnings = [];
  let skipped = 0;
  files.forEach(f => {
    try{
      if(f.error) throw new Error(f.error);
      let r;
      if(f.pdf){
        if(!f.pdf.txs.length) throw new Error('no encontré movimientos en el PDF');
        r = f.pdf;
      } else r = parseStatement(f.text);
      r.txs.forEach(t => { t.fp = txFingerprint(t); });
      perFile.push({ name: f.name, txs: r.txs, endBalance: f.pdf ? f.pdf.endBalance : null, endDate: f.pdf ? f.pdf.endDate : null });
      skipped += r.skipped;
    }catch(e){ warnings.push(f.name + ': ' + e.message); }
  });
  if(!perFile.length) throw new Error(warnings.join(' · '));

  // Si dos extractos se pisan (mismas fechas), el mismo movimiento aparece en ambos:
  // por cada huella se toma el archivo donde más veces aparece, nunca la suma.
  const maxCount = {}, fromFile = {};
  perFile.forEach((pf, fi) => {
    const cnt = {};
    pf.txs.forEach(t => { cnt[t.fp] = (cnt[t.fp] || 0) + 1; });
    Object.keys(cnt).forEach(fp => {
      if(!(fp in maxCount) || cnt[fp] > maxCount[fp]){ maxCount[fp] = cnt[fp]; fromFile[fp] = fi; }
    });
  });
  const stored = {};
  bankList().forEach(t => { stored[t.fp] = (stored[t.fp] || 0) + 1; });

  const candidates = [];
  const taken = {};
  let dup = 0, total = 0, fromShots = 0;
  const shotUpgrades = [], shotUsed = new Set();
  perFile.forEach((pf, fi) => pf.txs.forEach(t => {
    total++;
    if(fromFile[t.fp] !== fi){ dup++; return; }          // repetido en otro extracto
    taken[t.fp] = (taken[t.fp] || 0) + 1;
    if(taken[t.fp] <= (stored[t.fp] || 0)){ dup++; return; } // ya estaba guardado
    // ya había llegado por una captura de pantalla: se completa con los datos del extracto
    const sh = bankList().find(s => s.src === 'shot' && !shotUsed.has(s.id) && sameMove(s, t));
    if(sh){ shotUsed.add(sh.id); shotUpgrades.push({ id: sh.id, date: t.date, desc: t.desc, balance: t.balance }); dup++; fromShots++; return; }
    candidates.push(t);
  }));

  candidates.sort((a,b) => b.date.localeCompare(a.date));
  const used = new Set(bankList().filter(t => t.manualId).map(t => t.manualId));
  let manualDup = 0;
  const stamp = Date.now().toString(36);
  candidates.forEach((t, i) => {
    const g = guessCategory(t);
    t.category = g.category;
    t.auto = g.auto;
    const m = findManualMatch(t, used);
    if(m){ used.add(m.id); t.manualId = m.id; t.category = 'ignore'; t.auto = 'manual'; manualDup++; }
    t.id = 'b' + stamp + '_' + i;
  });

  // Saldo final: el del extracto más reciente (si el archivo trae columna de saldo).
  let lastBalance = null, lastBalanceDate = null;
  perFile.forEach(pf => {
    if(pf.endBalance !== null && pf.endBalance !== undefined && pf.endDate){
      if(lastBalanceDate === null || pf.endDate > lastBalanceDate){ lastBalance = pf.endBalance; lastBalanceDate = pf.endDate; }
      return;
    }
    const withBal = pf.txs.filter(t => t.balance !== null && !isNaN(t.balance));
    if(!withBal.length) return;
    const newestFirst = pf.txs[0].date >= pf.txs[pf.txs.length-1].date;
    const b = newestFirst ? withBal[0] : withBal[withBal.length-1];
    if(lastBalanceDate === null || b.date > lastBalanceDate){ lastBalance = b.balance; lastBalanceDate = b.date; }
  });
  const all = perFile.flatMap(pf => pf.txs.map(t => t.date)).sort();
  const daysOld = lastBalanceDate ? Math.round((new Date(todayISO()+'T00:00:00') - new Date(lastBalanceDate+'T00:00:00')) / 86400000) : null;
  importPreview = {
    fileName: perFile.map(pf => pf.name).join(', '),
    fileCount: perFile.length,
    fresh: candidates, dup, manualDup, skipped, total, warnings,
    from: all[0], to: all[all.length-1],
    lastBalance, lastBalanceDate,
    updateBalance: lastBalance !== null && daysOld !== null && daysOld <= 7,
    shotUpgrades, fromShots
  };
}

function setPreviewCategory(i, cat){
  if(!importPreview || !importPreview.fresh[i]) return;
  const t = importPreview.fresh[i];
  t.category = cat;
  t.auto = 'manual-set';
  // Lo mismo para los demás del mismo comercio en este extracto.
  const key = merchantKey(t.desc);
  importPreview.fresh.forEach(o => {
    if(o !== t && o.dir === t.dir && o.auto !== 'manual-set' && o.auto !== 'manual' && merchantKey(o.desc) === key) o.category = cat;
  });
  upsertRule(key, t.dir, cat);
  render();
}
// Por si el PDF puso un movimiento al revés (entrada/salida).
function flipPreviewDir(i){
  if(!importPreview || !importPreview.fresh[i]) return;
  const t = importPreview.fresh[i];
  t.dir = t.dir === 'in' ? 'out' : 'in';
  t.fp = txFingerprint(t);
  const g = guessCategory(t);
  t.category = g.category; t.auto = g.auto;
  render();
}
function toggleUpdateBalance(v){ if(importPreview){ importPreview.updateBalance = !!v; } }

function confirmImport(){
  if(!importPreview) return;
  const p = importPreview;
  if(p.shot){
    const bad = p.fresh.findIndex(t => !(t.amount > 0));
    if(bad >= 0){ shotEditIdx = bad; render(); showToast('Hay un movimiento sin monto: corrígelo o quítalo'); return; }
  }
  (p.shotUpgrades || []).forEach(u => {
    const t = bankList().find(x => x.id === u.id); if(!t) return;
    t.date = u.date; t.desc = u.desc; t.src = 'stmt'; t.fromShot = true; t.fp = txFingerprint(t);
    if(u.balance !== null && u.balance !== undefined) t.balance = u.balance;
  });
  const imp = { id:'imp'+Date.now(), date: todayISO(), fileName: p.fileName, files: p.fileCount, added: p.fresh.length, dup: p.dup, from: p.from, to: p.to, shot: !!p.shot };
  p.fresh.forEach(t => { t.importId = imp.id; data.bank.transactions.push(t); });
  data.bank.imports.push(imp);
  const autoLinked = autoLinkNew(p.fresh);
  let msg = p.fresh.length ? `Listo: ${p.fresh.length} movimiento${p.fresh.length===1?'':'s'} nuevo${p.fresh.length===1?'':'s'} guardado${p.fresh.length===1?'':'s'}` : (p.shot ? 'No había movimientos nuevos en la captura' : 'No había movimientos nuevos en ese extracto');
  if(p.fromShots) msg += ` · ${p.fromShots} de tus capturas quedaron confirmados`;
  if(p.updateBalance && p.lastBalance !== null){
    data.settings.currentBalance = p.lastBalance;
    msg += ` · saldo actualizado a ${fmtMoney(p.lastBalance)}`;
  }
  if(autoLinked) msg += ` · ${autoLinked} pago${autoLinked===1?'':'s'} se conectaron solos`;
  importPreview = null;
  saveData();
  activeTab = 'home';
  render();
  window.scrollTo(0,0);
  showToast(msg);
}
function cancelImport(){ importPreview = null; shotEditIdx = null; render(); }

function undoImport(id){
  const imp = data.bank.imports.find(x => x.id === id);
  if(!imp) return;
  const before = data.bank.transactions.length;
  data.bank.transactions.forEach(t => { if(t.importId === id) unlinkTx(t); });
  data.bank.transactions = data.bank.transactions.filter(t => t.importId !== id);
  data.bank.imports = data.bank.imports.filter(x => x.id !== id);
  confirmingDeleteId = null;
  saveData(); render();
  showToast(`Extracto deshecho: se quitaron ${before - data.bank.transactions.length} movimientos`);
}

// ---- Reglas automáticas ----
// Cada vez que confirmas la misma categoría para un comercio, sube la confianza. Con 2, la app lo hace sola.
function upsertRule(key, dir, cat){
  const r = findRule(key, dir);
  if(r){ r.count = r.category === cat ? (r.count || 1) + 1 : 1; r.category = cat; }
  else data.bank.rules.push({ key, dir, category: cat, count: 1 });
}
function ruleTrusted(r){ return !!r && (r.count || 1) >= 2; }
function setBankCategory(id, cat){
  const t = bankList().find(x => x.id === id);
  if(!t || !BANK_CATS[cat]) return;
  if(t.link && !((cat === 'debt' && t.link.type === 'debt') || (cat === 'fixed' && t.link.type === 'expense'))) unlinkTx(t);
  if(t.category === 'loan' && cat !== 'loan') removeLoanFromTx(t);
  if(t.category === 'loanback' && cat !== 'loanback') removeRepayment(t);
  t.category = cat;
  t.reviewed = true;
  t.auto = 'manual-set';
  const key = merchantKey(t.desc);
  upsertRule(key, t.dir, cat);
  let n = 0;
  bankList().forEach(o => {
    if(o !== t && o.dir === t.dir && o.auto !== 'manual-set' && o.auto !== 'manual' && !o.link && merchantKey(o.desc) === key && o.category !== cat){
      o.category = cat; o.auto = 'rule'; n++;
    }
  });
  saveData();
  // Pago de deuda o gasto fijo: preguntar a cuál va. Préstamo: preguntar hasta cuándo.
  if((cat === 'debt' || cat === 'fixed') && !t.link){ openLinkSheet(t.id, cat === 'debt' ? 'debt' : 'expense'); return; }
  if(cat === 'loan'){ createLoanFromTx(t); saveData(); sheet = { type:'loanDue', loanId: t.loanId }; render(); return; }
  if(cat === 'loanback' && !t.loanId){ sheet = { type:'loanPick', txId: t.id }; render(); return; }
  render();
  showToast(`"${displayName(t)}" ahora es ${BANK_CATS[cat].label}` + (n ? ` (y ${n} más)` : '') + '. Los próximos extractos lo harán solos.');
}
function removeRule(key, dir){
  data.bank.rules = data.bank.rules.filter(r => !(r.key === key && r.dir === dir));
  saveData(); render();
}

// =====================================================================
//  DATOS PARA LAS GRÁFICAS
// =====================================================================
function monthBounds(offset){
  const now = new Date();
  const s = new Date(now.getFullYear(), now.getMonth() + offset, 1);
  const e = new Date(now.getFullYear(), now.getMonth() + offset + 1, 0, 23, 59, 59, 999);
  return { s, e, label: capFirst(s.toLocaleDateString('es-ES', {month:'long', year:'numeric'})) };
}
function spendingByCategory(s, e){
  const res = {};
  SPEND_CATS.forEach(c => res[c] = 0);
  res.hormiga += sumInRange(data.hormiga, s, e);
  bankList().forEach(t => {
    if(t.dir !== 'out' || !(t.category in res)) return;
    const d = new Date(t.date+'T00:00:00');
    if(d >= s && d <= e) res[t.category] += t.amount;
  });
  return res;
}
function topMerchants(s, e, n){
  const map = {};
  bankList().forEach(t => {
    if(t.dir !== 'out' || t.category === 'ignore' || t.category === 'own' || t.category === 'trading') return;
    const d = new Date(t.date+'T00:00:00');
    if(d < s || d > e) return;
    const k = merchantKey(t.desc);
    if(!map[k]) map[k] = { key:k, total:0, count:0, category:t.category };
    map[k].total += t.amount; map[k].count++;
  });
  return Object.values(map).sort((a,b)=>b.total-a.total).slice(0, n);
}
function weeklySeries(nWeeks){
  const today = new Date(); today.setHours(0,0,0,0);
  const cur = startOfUserWeek(today);
  const out = [];
  for(let w = nWeeks-1; w >= 0; w--){
    const ws = new Date(cur); ws.setDate(ws.getDate() - w*7);
    const we = new Date(ws); we.setDate(we.getDate()+6); we.setHours(23,59,59,999);
    out.push({
      ws, we,
      income: sumInRange(allIncomes(), ws, we),
      spend: sumInRange(allHormiga(), ws, we) + sumInRange(allOtherSpend(), ws, we)
    });
  }
  return out;
}
function niceMax(v){
  if(v <= 0) return 100;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  const m = v / p;
  return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * p;
}
function shortMoney(n){
  const sy = currencyInfo().sym;
  if(n >= 1e6) return sy + (n/1e6).toFixed(n >= 1e7 ? 0 : 1).replace(/\.0$/,'') + 'M';
  if(n >= 1000) return sy + (n/1000).toFixed(n >= 10000 ? 0 : 1).replace(/\.0$/,'') + 'k';
  return sy + Math.round(n);
}

// =====================================================================
//  EXTRACTOS EN PDF (TD Bank) — se leen en el teléfono, sin internet
// =====================================================================
let pdfLibPromise = null;
function loadScript(src){
  return new Promise((res, rej) => {
    const s = document.createElement('script');
    s.src = src; s.onload = res; s.onerror = () => rej(new Error('no se pudo cargar ' + src));
    document.head.appendChild(s);
  });
}
// pdf.js se carga solo la primera vez que subes un PDF (pesa un poco y no hace falta antes).
function loadPdfLib(){
  if(!pdfLibPromise){
    pdfLibPromise = loadScript('pdfjs/pdf.worker.min.js')     // corre en el mismo hilo (sin Worker)
      .then(() => loadScript('pdfjs/pdf.min.js'))
      .then(() => {
        const lib = window.pdfjsLib;
        if(!lib) throw new Error('lector de PDF no disponible');
        lib.GlobalWorkerOptions.workerSrc = 'pdfjs/pdf.worker.min.js';
        return lib;
      })
      .catch(e => { pdfLibPromise = null; throw e; });
  }
  return pdfLibPromise;
}
function readFileBuffer(f){
  return new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(r.result);
    r.onerror = () => rej(new Error('no se pudo abrir ' + f.name));
    r.readAsArrayBuffer(f);
  });
}
// Convierte cada página en renglones de texto, respetando la posición de las palabras.
async function pdfToLines(buffer){
  const lib = await loadPdfLib();
  const doc = await lib.getDocument({ data: new Uint8Array(buffer), isEvalSupported: false }).promise;
  const lines = [];
  for(let p = 1; p <= doc.numPages; p++){
    const page = await doc.getPage(p);
    const tc = await page.getTextContent();
    const rows = [];
    tc.items.forEach(it => {
      if(!it.str || !it.str.trim()) return;
      const x = it.transform[4], y = it.transform[5];
      let row = rows.find(r => Math.abs(r.y - y) < 2.5);
      if(!row){ row = { y, items: [] }; rows.push(row); }
      row.items.push({ x, w: it.width || 0, s: it.str });
    });
    rows.sort((a,b) => b.y - a.y);
    rows.forEach(r => {
      r.items.sort((a,b) => a.x - b.x);
      let txt = '', end = null;
      r.items.forEach(it => {
        if(end !== null && it.x - end > 1.5 && !/\s$/.test(txt)) txt += ' ';
        txt += it.s; end = it.x + it.w;
      });
      txt = txt.replace(/\s+/g, ' ').trim();
      if(txt) lines.push(txt);
    });
  }
  return lines;
}

const MONTHS_EN = {jan:1,feb:2,mar:3,apr:4,may:5,jun:6,jul:7,aug:8,sep:9,oct:10,nov:11,dec:12,
                   ene:1,abr:4,ago:8,dic:12};
function statementPeriod(lines){
  const joined = lines.slice(0, 80).join(' ');
  let m = joined.match(/statement period:?\s*([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})\s*[-–to]+\s*([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})/i);
  if(m){
    const m1 = MONTHS_EN[m[1].toLowerCase()], m2 = MONTHS_EN[m[4].toLowerCase()];
    if(m1 && m2) return { start: ymd(+m[3], m1, +m[2]), end: ymd(+m[6], m2, +m[5]) };
  }
  m = joined.match(/(\d{1,2})\/(\d{1,2})\/(\d{2,4})\s*(?:-|–|to|through|thru)\s*(\d{1,2})\/(\d{1,2})\/(\d{2,4})/i);
  if(m){
    const y1 = +m[3] < 100 ? +m[3] + 2000 : +m[3], y2 = +m[6] < 100 ? +m[6] + 2000 : +m[6];
    return { start: ymd(y1, +m[1], +m[2]), end: ymd(y2, +m[4], +m[5]) };
  }
  m = joined.match(/\b(20\d\d)\b/);
  return m ? { start: null, end: null, year: +m[1] } : null;
}

const PDF_IN_HEAD  = /^(electronic deposits|deposits|other credits|credits|deposits (and|&) (other )?(additions|credits)|interest (paid|earned)|incoming)/i;
const PDF_OUT_HEAD = /^(electronic payments|other withdrawals|withdrawals|checks paid|checks|service charges|fees|atm|point of sale|debit card|card purchases|purchases|withdrawals (and|&) (other )?(subtractions|debits))/i;
const PDF_STOP     = /^(daily balance summary|daily balance|balance summary|date\s+balance|how to balance|call 1-|for consumer accounts|in case of errors|your account balance)/i;
const PDF_SKIP     = /^(subtotal|total|beginning balance|ending balance|posting date|date description|description amount|page \d|continued)/i;

// Lee un extracto en PDF y devuelve los movimientos en el mismo formato que los CSV.
function parsePdfStatementLines(lines){
  const period = statementPeriod(lines);
  const endYear = period && period.end ? +period.end.slice(0,4) : (period && period.year) || new Date().getFullYear();
  const endMonth = period && period.end ? +period.end.slice(5,7) : 12;
  const toDate = (mo, d, y) => {
    if(y) return ymd(+y < 100 ? +y + 2000 : +y, +mo, +d);
    return ymd(+mo > endMonth ? endYear - 1 : endYear, +mo, +d); // extractos de diciembre–enero
  };
  const lineRe = /^(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?\s+(.*?)\s+(-?\(?\$?\s?[\d,]*\d\.\d{2}\)?-?)(?:\s+(-?\$?[\d,]*\d\.\d{2}-?))?$/;

  const txs = [];
  let section = null;     // 'in' | 'out' | null
  let inActivity = false; // ya pasamos el resumen de la cuenta
  let last = null, contLines = 0, skipped = 0;
  let endBalance = null;

  lines.forEach(raw => {
    const line = raw.trim();
    const bal = line.match(/^ending balance\s+(-?\$?[\d,]*\d\.\d{2})/i);
    if(bal && endBalance === null) endBalance = parseBankAmount(bal[1]);
    if(/daily account activity|account activity|transaction (history|detail)/i.test(line)){ inActivity = true; section = null; last = null; return; }
    if(PDF_STOP.test(line)){ section = null; last = null; return; }
    const headIn = PDF_IN_HEAD.test(line), headOut = PDF_OUT_HEAD.test(line);
    const m = line.match(lineRe);
    if(!m && (headIn || headOut)){ section = headIn ? 'in' : 'out'; last = null; return; }
    if(PDF_SKIP.test(line)){ last = null; return; }
    if(m){
      const date = toDate(m[1], m[2], m[3]);
      const a = parseBankAmount(m[5]);
      const desc = m[4].replace(/\s+/g, ' ').trim();
      // Una fila sin letras (solo números y fechas) es de la tabla de saldos diarios, no un movimiento.
      if(!date || a === null || a === 0 || !desc || !/[A-Za-z]{2,}/.test(desc)){ skipped++; last = null; return; }
      let dir = section;
      if(a < 0) dir = 'out';
      if(!dir) dir = /deposit|credit|refund|payroll|zelle.*from|transfer from|interest|reversal/i.test(desc) ? 'in' : 'out';
      last = { date, desc, amount: Math.round(Math.abs(a)*100)/100, dir, balance: null, fromPdf: true };
      txs.push(last);
      contLines = 0;
      return;
    }
    // Renglón que continúa la descripción del movimiento anterior.
    if(last && contLines < 2 && line.length < 90 && !/\d\.\d{2}$/.test(line)){
      last.desc = (last.desc + ' ' + line).replace(/\s+/g, ' ').trim();
      contLines++;
      return;
    }
    last = null;
  });
  return { txs, skipped, endBalance, endDate: period && period.end ? period.end : null, inActivity };
}
