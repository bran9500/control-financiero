
// ================================================================ CAPTURAS DEL BANCO
// Subir un screenshot de los movimientos recientes (app de TD Bank, Zelle, etc.):
// el teléfono lee el texto (ML Kit, sin internet), aquí se arman los movimientos,
// se revisan/corrigen y se guardan. Cuando después llega el extracto con esos mismos
// movimientos, se reconocen y no se duplican.

const SHOT_IN_RE = /\b(DEPOSIT|DEP\b|PAYROLL|DIRECT DEP|ZELLE (FROM|PAYMENT FROM)|RECEIVED|RECIBID|REFUND|REEMBOLSO|CREDIT\b|INTEREST|MOBILE DEPOSIT|CASH APP.*FROM|TRANSFER FROM|FROM\b.*ZELLE)/i;
const SHOT_SKIP_RE = /^(search|buscar|transactions?|activity|actividad|movimientos|filter|filtrar|accounts?|cuentas?|see all|ver todo|view all|details?|detalles|statements?|home|inicio|pay|deposit|transfer|menu|more|más|help|ayuda|back|atr[aá]s|done|listo|all|todos|posted|pending|pendientes?|\d{1,2}:\d{2}.*|[\d%\s.]+)$/i;
const SHOT_BAL_RE = /(available|current|ledger|account)?\s*balance|saldo|disponible/i;
const MONTHS_ES = {ene:1,feb:2,mar:3,abr:4,may:5,jun:6,jul:7,ago:8,sep:9,set:9,oct:10,nov:11,dic:12};

let shotBusy = false;
let ocrSeq = 0;
const ocrWaiters = {};
window.onOcrResult = function(id, json){
  const w = ocrWaiters[id]; if(!w) return;
  delete ocrWaiters[id];
  try{ w.res(typeof json === 'string' ? JSON.parse(json) : json); }catch(e){ w.rej(e); }
};
window.onOcrError = function(id, msg){ const w = ocrWaiters[id]; if(!w) return; delete ocrWaiters[id]; w.rej(new Error(msg || 'no se pudo leer la imagen')); };

function canOcr(){ return !!(window.__ocrMock || (window.Android && typeof Android.ocrImage === 'function')); }

function imageToJpegB64(file){
  return new Promise((res, rej) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      // Ancho máximo 1280 px: suficiente para leer y no satura el puente con Android.
      const sc = Math.min(1, 1280 / img.width);
      const c = document.createElement('canvas');
      c.width = Math.round(img.width * sc); c.height = Math.round(img.height * sc);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      res(c.toDataURL('image/jpeg', 0.92).split(',')[1]);
    };
    img.onerror = () => { URL.revokeObjectURL(url); rej(new Error('no es una imagen')); };
    img.src = url;
  });
}
function ocrFile(file){
  if(window.__ocrMock) return Promise.resolve(window.__ocrMock(file));
  return imageToJpegB64(file).then(b64 => new Promise((res, rej) => {
    const id = 'o' + (++ocrSeq);
    ocrWaiters[id] = { res, rej };
    setTimeout(() => { if(ocrWaiters[id]){ delete ocrWaiters[id]; rej(new Error('se demoró demasiado')); } }, 45000);
    Android.ocrImage(id, b64);
  }));
}

async function handleShotFiles(input){
  const files = Array.from(input.files || []);
  input.value = '';
  if(!files.length) return;
  if(!canOcr()){ importError = 'Leer capturas funciona dentro de la app del teléfono.'; render(); return; }
  importError = null; shotBusy = true; render();
  const all = [], warnings = []; let balance = null;
  for(const f of files){
    try{
      const ocr = await ocrFile(f);
      const r = parseShotOcr(ocr);
      if(!r.txs.length) warnings.push((f.name || 'captura') + ': no encontré movimientos con monto');
      all.push(...r.txs);
      if(r.balance !== null && balance === null) balance = r.balance;
    }catch(e){ warnings.push((f.name || 'captura') + ': ' + (e && e.message ? e.message : 'error')); }
  }
  shotBusy = false;
  if(!all.length){ importError = 'No pude sacar movimientos de la captura. ' + warnings.join(' · '); render(); return; }
  buildShotPreview(all, balance, files.length, warnings);
  render(); window.scrollTo(0,0);
}

// ---- leer la captura: cada monto es un movimiento; las líneas de texto de la izquierda
//      se juntan con el monto más cercano (en TD el nombre ocupa varias líneas) ----
const AMT_ONLY_RE = /^([-−–+]?)\s*\(?\s*\$\s?(\d{1,3}(?:,\d{3})+|\d+)[.,](\d{2})\)?\s*(-|CR|DR)?$/i;
const AMT_RE = /([-−–+]?)\s*\(?\s*\$\s?(\d{1,3}(?:,\d{3})+|\d+)[.,](\d{2})\)?/;
const SHOT_NOISE_RE = /^(deposit|zelle®?|transfer|accounts?|pay\s*&\s*transfer|products|more|info\s*&\s*manage|statements?|account activity|account\s*&\s*routing numbers|pending|posted|search|filter|see all|view all|td simple checking|td .*checking|.*savings|x\d{3,5}|cuentas?|movimientos|actividad|pendientes?)$/i;
const SHOT_SECTION_RE = /^(account activity|pending|actividad|movimientos|transactions|activity)$/i;

function shotLines(ocr){
  const H = ocr.h || 2400;
  return (ocr.lines || []).filter(l => l && l.t && String(l.t).trim())
    .map(l => ({ t:String(l.t).trim().replace(/^[^A-Za-z0-9$(+\-−–]+\s*/,'').replace(/^[£€¥@©®]\S*\s+/,''), x:l.x, y:l.y, w:l.w, h:l.h, cy:l.y + l.h/2, r:l.x + l.w }))
    .filter(l => l.t)
    .filter(l => l.cy > H * 0.035)
    .flatMap(l => {
      const m = l.t.match(/^(.*[A-Za-z].*?)\s+([-−–+]?\s?\$\s?[\d,]+[.,]\d{2})$/);
      if(!m) return [l];
      const cw = l.w / l.t.length, w2 = Math.round(m[2].length * cw);
      return [{ ...l, t:m[1].trim(), w:l.w - w2, r:l.x + l.w - w2 }, { ...l, t:m[2], x:l.r - w2, w:w2 }];
    })
    .sort((a,b) => a.cy - b.cy || a.x - b.x);
}
function parseAmt(t){
  const mm = String(t).replace(/\s+/g,'').replace(/[,.;:]+$/,'').match(AMT_ONLY_RE);
  if(!mm) return null;
  return { v: parseFloat(mm[2].replace(/,/g,'') + '.' + mm[3]), sign: (/[-−–]/.test(mm[1]||'') || mm[4] === '-' || /DR/i.test(mm[4]||'') || /^\(/.test(String(t).trim())) ? -1 : (mm[1] === '+' || /CR/i.test(mm[4]||'')) ? 1 : 0 };
}
function parseShotDate(s){
  const t = String(s || '').trim().toLowerCase().replace(/\./g,'');
  const today = todayISO();
  if(/^(today|hoy)\b/.test(t)) return today;
  if(/^(yesterday|ayer)\b/.test(t)){ const d = new Date(today + 'T00:00:00'); d.setDate(d.getDate() - 1); return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0'); }
  let m = t.match(/\b(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?\b/);
  let y, mo, d;
  if(m){ mo = +m[1]; d = +m[2]; y = m[3] ? (+m[3] < 100 ? 2000 + +m[3] : +m[3]) : null; }
  else {
    m = t.match(/\b([a-záéíóú]{3,10})\s+(\d{1,2})(?:st|nd|rd|th)?,?\s*(\d{4})?\b/);
    if(m && (MONTHS_EN[m[1].slice(0,3)] || MONTHS_ES[m[1].slice(0,3)])){ mo = MONTHS_EN[m[1].slice(0,3)] || MONTHS_ES[m[1].slice(0,3)]; d = +m[2]; y = m[3] ? +m[3] : null; }
    else {
      m = t.match(/\b(\d{1,2})\s+(?:de\s+)?([a-záéíóú]{3,10})(?:\s+(?:de\s+)?(\d{4}))?\b/);
      if(m && (MONTHS_ES[m[2].slice(0,3)] || MONTHS_EN[m[2].slice(0,3)])){ d = +m[1]; mo = MONTHS_ES[m[2].slice(0,3)] || MONTHS_EN[m[2].slice(0,3)]; y = m[3] ? +m[3] : null; }
      else return null;
    }
  }
  if(!(mo >= 1 && mo <= 12 && d >= 1 && d <= 31)) return null;
  if(!y){ y = +today.slice(0,4); if(ymd(y, mo, d) > today) y--; }
  return ymd(y, mo, d);
}
function isDateHeader(l){ return !!parseShotDate(l.t) && l.t.replace(/[^a-z]/gi,'').length <= 14 && !AMT_RE.test(l.t); }
function descOk(t){
  const letters = t.replace(/[^a-záéíóúñ]/gi,'');
  return letters.length >= 2 && (/[aeiouáéíóú]/i.test(letters) || letters.length >= 4);
}

function parseShotOcr(ocr){
  const W = ocr.w || 1080;
  const lines = shotLines(ocr);
  let balance = null;
  // 1) saldo disponible y fin de la parte de arriba (todo lo de arriba de "Account Activity"/"Pending" no son movimientos)
  let activityTop = -1;
  lines.forEach((l, k) => {
    if(/available balance|saldo disponible/i.test(l.t) && balance === null){
      const inl = l.t.match(AMT_RE);
      if(inl) balance = parseFloat(inl[2].replace(/,/g,'') + '.' + inl[3]);
      else { const nx = lines.slice(k+1, k+4).find(o => parseAmt(o.t)); if(nx) balance = parseAmt(nx.t).v; }
    }
    if(SHOT_SECTION_RE.test(l.t) && activityTop < 0 && balance !== null) activityTop = l.y;
  });
  const isSummary = l => lines.some(o => o !== l && Math.abs(o.cy - l.cy) < Math.max(o.h, l.h) * 0.6 && /balance|total|saldo|disponible|numbers/i.test(o.t));
  // 2) montos: principales y saldos corridos (el número gris debajo del monto)
  const amts = [];
  lines.forEach(l => {
    const a = parseAmt(l.t);
    if(!a || l.x < W * 0.4) return;
    if(activityTop >= 0 && l.cy < activityTop) return;
    if(isSummary(l)) return;
    const prev = amts[amts.length - 1];
    if(prev && !prev.sub && l.cy - prev.l.cy < Math.max(prev.l.h, l.h) * 1.7 && (Math.abs(l.r - prev.l.r) < W * 0.1 || (l.h < prev.l.h * 0.8 && l.x >= prev.l.x - W * 0.05))){
      prev.sub = l; return;                                   // saldo después del movimiento: no es un movimiento
    }
    amts.push({ l, a, desc: [] });
  });
  if(!amts.length) return { txs: [], balance };
  // 3) secciones por fecha
  const headers = lines.filter(isDateHeader).map(l => ({ cy: l.cy, d: parseShotDate(l.t) }));
  const pendingTops = lines.filter(l => /^(pending|pendientes?)$/i.test(l.t.replace(/[^a-z]/gi,''))).map(l => l.cy);
  const sectionOf = cy => { let h = null; headers.forEach(x => { if(x.cy < cy) h = x; }); return h; };
  // 4) texto de la izquierda → al monto más cercano dentro de la misma sección
  lines.forEach(l => {
    if(l.x > W * 0.6 || parseAmt(l.t) || isDateHeader(l) || SHOT_NOISE_RE.test(l.t) || !descOk(l.t)) return;
    if(activityTop >= 0 && l.cy < activityTop) return;
    const sec = sectionOf(l.cy);
    let best = null, bd = 1e9;
    amts.forEach(m => { if(sectionOf(m.l.cy) !== sec) return; const d = Math.abs(m.l.cy - l.cy); if(d < bd){ bd = d; best = m; } });
    if(best && bd < Math.max(best.l.h, l.h) * 3.6) best.desc.push(l);
  });
  // 5) armar movimientos
  const anyMinus = amts.some(m => m.a.sign < 0);
  const txs = amts.map(m => {
    const desc = m.desc.sort((a,b) => a.cy - b.cy).map(l => l.t).join(' ')
      .replace(/[…]|\.\.\.$/g,'').replace(/\s+/g,' ').trim() || 'MOVIMIENTO';
    const sec = sectionOf(m.l.cy);
    const pending = !sec || pendingTops.some(p => p < m.l.cy && (!sec || p > sec.cy));
    const dir = m.a.sign < 0 ? 'out' : m.a.sign > 0 ? 'in' : (anyMinus ? 'in' : (SHOT_IN_RE.test(desc) ? 'in' : 'out'));
    return { date: sec ? sec.d : todayISO(), desc: desc.toUpperCase(), amount: m.a.v, dir, balance: null, fromPdf: true, src: 'shot', pending: pending && !sec };
  });
  return { txs, balance };
}

function shotWords(d){ return new Set(String(d).toUpperCase().split(/[^A-Z0-9']+/).filter(w => w.length >= 4 && !/^(DEBIT|CARD|AUTHORIZATION|PURCHASE|PAYMENT|TEMP|AUTH|RECEIVED|SENT)$/.test(w))); }
function sameShotTx(a, b){
  if(a.date !== b.date || a.dir !== b.dir || Math.round(a.amount*100) !== Math.round(b.amount*100)) return false;
  if(merchantKey(a.desc) === merchantKey(b.desc)) return true;
  const wa = shotWords(a.desc), wb = shotWords(b.desc);
  for(const w of wa) if(wb.has(w)) return true;
  return false;
}

// ¿Es el mismo movimiento? Mismo sentido y monto, fechas a 5 días o menos
// (en la app sale la fecha de la compra y en el extracto la del cobro).
function sameMove(a, b){
  if(a.dir !== b.dir || Math.round(a.amount*100) !== Math.round(b.amount*100)) return false;
  const da = new Date(a.date + 'T00:00:00'), db = new Date(b.date + 'T00:00:00');
  return Math.abs(da - db) <= 5 * 86400000;
}

function buildShotPreview(txs, balance, nFiles, warnings){
  // repetidos entre capturas que se pisan
  const uniq = [];
  txs.forEach(t => { const u = uniq.find(u => sameShotTx(u, t)); if(!u) uniq.push(t); else if(t.desc.length > u.desc.length) u.desc = t.desc; });
  const usedStored = new Set();
  const fresh = []; let dup = 0;
  uniq.forEach(t => {
    const hit = bankList().find(s => !usedStored.has(s.id) && sameMove(s, t));
    if(hit){ usedStored.add(hit.id); dup++; return; }
    fresh.push(t);
  });
  fresh.sort((a,b) => b.date.localeCompare(a.date));
  const used = new Set(bankList().filter(t => t.manualId).map(t => t.manualId));
  let manualDup = 0;
  const stamp = Date.now().toString(36);
  fresh.forEach((t, i) => {
    t.fp = txFingerprint(t);
    const g = guessCategory(t); t.category = g.category; t.auto = g.auto;
    const m = findManualMatch(t, used);
    if(m){ used.add(m.id); t.manualId = m.id; t.category = 'ignore'; t.auto = 'manual'; manualDup++; }
    t.id = 's' + stamp + '_' + i;
  });
  const dates = uniq.map(t => t.date).sort();
  importPreview = {
    shot: true,
    fileName: nFiles === 1 ? 'Captura' : nFiles + ' capturas', fileCount: nFiles,
    fresh, dup, manualDup, skipped: 0, total: uniq.length, warnings,
    from: dates[0], to: dates[dates.length-1],
    lastBalance: balance, lastBalanceDate: balance !== null ? todayISO() : null,
    updateBalance: balance !== null, shotUpgrades: []
  };
}

// ---- corregir filas de la captura antes de guardar ----
let shotEditIdx = null;
function editShotRow(i){ shotEditIdx = shotEditIdx === i ? null : i; render(); }
function saveShotRow(i){
  const t = importPreview && importPreview.fresh[i]; if(!t) return;
  const d = document.getElementById('se-date').value, ds = document.getElementById('se-desc').value.trim(), a = parseFloat(document.getElementById('se-amt').value);
  if(d) t.date = d;
  if(ds) t.desc = ds.toUpperCase();
  if(a > 0) t.amount = Math.round(a * 100) / 100;
  t.fp = txFingerprint(t);
  if(t.auto !== 'manual-set'){ const g = guessCategory(t); t.category = g.category; t.auto = g.auto; }
  shotEditIdx = null; render();
}
function removeShotRow(i){
  if(!importPreview) return;
  importPreview.fresh.splice(i, 1); shotEditIdx = null; render();
}
function addShotRow(){
  if(!importPreview) return;
  importPreview.fresh.unshift({ date: todayISO(), desc: 'MOVIMIENTO', amount: 0, dir: 'out', balance: null, fromPdf: true, src: 'shot', category: 'gasto', auto: 'manual-set', id: 's' + Date.now().toString(36) + '_n' });
  shotEditIdx = 0; render();
}
