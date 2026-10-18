
// ================================================================ PREFERENCIAS Y BIENVENIDA
// Nombre, saludo, colores, fondo, letra y tamaño. Se guardan aparte ('app-prefs')
// para que las subapps (Inglés, Interés, Notas) también usen los mismos colores.
const PREFS_KEY = 'app-prefs';
const APP_VERSION = '3.11';
const ACCENTS = {
  verde:    { label:'Verde',    c:'#6FA98A', on:'#0E1A14' },
  azul:     { label:'Azul',     c:'#5B9BE6', on:'#0B1626' },
  morado:   { label:'Morado',   c:'#A18FF0', on:'#15112A' },
  rosa:     { label:'Rosa',     c:'#E07AA5', on:'#2A0F1C' },
  naranja:  { label:'Naranja',  c:'#E39A55', on:'#2A1708' },
  turquesa: { label:'Turquesa', c:'#4FB8B8', on:'#0A2222' },
  dorado:   { label:'Dorado',   c:'#D4B25A', on:'#241C06' },
  rojo:     { label:'Rojo',     c:'#E37070', on:'#2A0D0D' }
};
const BACKGROUNDS = {
  grafito:    { label:'Grafito',    bg:'#12161C', s:'#1B2129', r:'#232A35', l:'#2A313B' },
  medianoche: { label:'Medianoche', bg:'#0D1322', s:'#151D31', r:'#1D263D', l:'#27324C' },
  negro:      { label:'Negro',      bg:'#000000', s:'#0F1012', r:'#18191C', l:'#25272B' },
  cafe:       { label:'Café',       bg:'#16120E', s:'#201A15', r:'#2A231C', l:'#362D25' },
  bosque:     { label:'Bosque',     bg:'#0E1511', s:'#161F1A', r:'#1E2922', l:'#28352D' },
  vino:       { label:'Vino',       bg:'#170E12', s:'#21151A', r:'#2C1C23', l:'#3A262F' }
};
const FONTS = {
  clasica:    { label:'Clásica',    title:"Georgia, 'Noto Serif', serif",            body:"-apple-system, Roboto, 'Segoe UI', sans-serif" },
  moderna:    { label:'Moderna',    title:"-apple-system, Roboto, 'Segoe UI', sans-serif", body:"-apple-system, Roboto, 'Segoe UI', sans-serif" },
  condensada: { label:'Condensada', title:"'sans-serif-condensed', 'Roboto Condensed', 'Arial Narrow', sans-serif", body:"'sans-serif-condensed', 'Roboto Condensed', 'Arial Narrow', sans-serif" },
  maquina:    { label:'Máquina',    title:"'monospace', ui-monospace, Consolas, monospace", body:"-apple-system, Roboto, 'Segoe UI', sans-serif" },
  casual:     { label:'Casual',     title:"'casual', 'Comic Sans MS', 'Chalkboard SE', cursive", body:"-apple-system, Roboto, 'Segoe UI', sans-serif" },
  manuscrita: { label:'Manuscrita', title:"'cursive', 'Dancing Script', 'Brush Script MT', cursive", body:"-apple-system, Roboto, 'Segoe UI', sans-serif" }
};
const SIZES = { normal:{ label:'Normal', z:1 }, grande:{ label:'Grande', z:1.08 }, xl:{ label:'Muy grande', z:1.16 } };
const GREET_STYLES = { formal:'Buenos días, Brandon', parce:'¿Qué más, Brandon?', emoji:'☀️ Buenos días, Brandon' };

function defaultPrefs(){ return { name:'', onboarded:false, accent:'verde', bg:'grafito', font:'clasica', size:'normal', greet:'formal', weekStart:1, payFreq:'semanal', payDay:30, payAnchor:'', currency:'USD' }; }
let prefs = loadPrefs();
function loadPrefs(){ try{ return Object.assign(defaultPrefs(), JSON.parse(localStorage.getItem(PREFS_KEY) || '{}')); }catch(e){ return defaultPrefs(); } }
function savePrefs(){ if(typeof previewMode !== 'undefined' && previewMode) return; try{ localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); }catch(e){} }

// ---- cada cuánto te pagan ----
const PAY_FREQS = {
  semanal:  { label:'Cada semana',     noun:'semana',   art:'esta semana',   plural:'semanas',   adj:'semanal',  por:'por semana' },
  biweekly: { label:'Cada 2 semanas',  noun:'quincena', art:'esta quincena', plural:'quincenas', adj:'quincenal', por:'por quincena' },
  quincenal:{ label:'Quincenal (15 y fin de mes)', noun:'quincena', art:'esta quincena', plural:'quincenas', adj:'quincenal', por:'por quincena' },
  mensual:  { label:'Cada mes',        noun:'mes',      art:'este mes',      plural:'meses',     adj:'mensual',  por:'por mes' }
};
function payFreq(){ return (typeof prefs !== 'undefined' && prefs && PAY_FREQS[prefs.payFreq]) ? prefs.payFreq : 'semanal'; }
function PL(){ return PAY_FREQS[payFreq()]; }
function periodsPerMonth(){ return { semanal:4.345, biweekly:26/12, quincenal:2, mensual:1 }[payFreq()]; }
function startOfUserWeek(d){
  const date = new Date(d); date.setHours(0,0,0,0);
  const ws = (typeof prefs !== 'undefined' && prefs && prefs.weekStart != null) ? prefs.weekStart : 1;
  date.setDate(date.getDate() - ((date.getDay() - ws + 7) % 7));
  return date;
}
function periodBounds(d){
  const x = new Date(d); x.setHours(0,0,0,0);
  const f = payFreq();
  let s, e;
  if(f === 'quincenal'){
    if(x.getDate() <= 15){ s = new Date(x.getFullYear(), x.getMonth(), 1); e = new Date(x.getFullYear(), x.getMonth(), 15); }
    else { s = new Date(x.getFullYear(), x.getMonth(), 16); e = new Date(x.getFullYear(), x.getMonth() + 1, 0); }
  } else if(f === 'mensual'){
    const pd = Math.min(Math.max(1, +prefs.payDay || 1), 28);
    s = x.getDate() >= pd ? new Date(x.getFullYear(), x.getMonth(), pd) : new Date(x.getFullYear(), x.getMonth() - 1, pd);
    e = new Date(s.getFullYear(), s.getMonth() + 1, pd - 1);
  } else if(f === 'biweekly'){
    let a = prefs.payAnchor ? new Date(prefs.payAnchor + 'T00:00:00') : startOfUserWeek(new Date(2026, 0, 5));
    const days = Math.floor((x - a) / 86400000);
    s = new Date(a.getFullYear(), a.getMonth(), a.getDate() + Math.floor(days / 14) * 14);
    e = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 13);
  } else {
    s = startOfUserWeek(x); e = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 6);
  }
  e.setHours(23,59,59,999);
  return { s, e };
}
// ---- moneda ----
const CURRENCIES = {
  USD: { label:'Dólar (US$)', country:'🇺🇸 Estados Unidos', sym:'$', locale:'en-US', dec:2 },
  COP: { label:'Peso colombiano (COP)', country:'🇨🇴 Colombia', sym:'$', locale:'es-CO', dec:0 },
  MXN: { label:'Peso mexicano (MXN)', country:'🇲🇽 México', sym:'$', locale:'es-MX', dec:2 },
  EUR: { label:'Euro (€)', country:'🇪🇸 España / Europa', sym:'€', locale:'es-ES', dec:2, after:true },
  PEN: { label:'Sol peruano (S/)', country:'🇵🇪 Perú', sym:'S/ ', locale:'es-PE', dec:2 },
  CLP: { label:'Peso chileno (CLP)', country:'🇨🇱 Chile', sym:'$', locale:'es-CL', dec:0 },
  ARS: { label:'Peso argentino (ARS)', country:'🇦🇷 Argentina', sym:'$', locale:'es-AR', dec:2 },
  VES: { label:'Bolívar (Bs.)', country:'🇻🇪 Venezuela', sym:'Bs. ', locale:'es-VE', dec:2 },
  DOP: { label:'Peso dominicano (RD$)', country:'🇩🇴 Rep. Dominicana', sym:'RD$', locale:'es-DO', dec:2 },
  GTQ: { label:'Quetzal (Q)', country:'🇬🇹 Guatemala', sym:'Q', locale:'es-GT', dec:2 },
  HNL: { label:'Lempira (L)', country:'🇭🇳 Honduras', sym:'L ', locale:'es-HN', dec:2 },
  NIO: { label:'Córdoba (C$)', country:'🇳🇮 Nicaragua', sym:'C$', locale:'es-NI', dec:2 },
  CRC: { label:'Colón (₡)', country:'🇨🇷 Costa Rica', sym:'₡', locale:'es-CR', dec:0 },
  PAB: { label:'Balboa (B/.)', country:'🇵🇦 Panamá', sym:'B/. ', locale:'es-PA', dec:2 },
  BOB: { label:'Boliviano (Bs)', country:'🇧🇴 Bolivia', sym:'Bs ', locale:'es-BO', dec:2 },
  PYG: { label:'Guaraní (₲)', country:'🇵🇾 Paraguay', sym:'₲ ', locale:'es-PY', dec:0 },
  UYU: { label:'Peso uruguayo ($U)', country:'🇺🇾 Uruguay', sym:'$U ', locale:'es-UY', dec:2 },
  BRL: { label:'Real (R$)', country:'🇧🇷 Brasil', sym:'R$ ', locale:'pt-BR', dec:2 },
  CAD: { label:'Dólar canadiense (C$)', country:'🇨🇦 Canadá', sym:'C$', locale:'en-CA', dec:2 },
  GBP: { label:'Libra (£)', country:'🇬🇧 Reino Unido', sym:'£', locale:'en-GB', dec:2 }
};
// Tasas incluidas en la app (para usar sin internet); se actualizan solas cuando hay conexión.
const FX_SNAPSHOT = {"date": "2026-10-17", "rates": {"usd": 1, "cop": 3183.85066941, "mxn": 18.49139699, "eur": 0.88975695, "pen": 3.4377465, "clp": 984.67924764, "ars": 1509.36980844, "ves": 877.91625908, "dop": 61.29758568, "gtq": 7.64201221, "hnl": 26.93494493, "nio": 36.76795535, "crc": 454.30536956, "pab": 1, "bob": 11.66944131, "pyg": 5696.0458805, "uyu": 40.21168968, "brl": 5.06377409, "cad": 1.41415707, "gbp": 0.75024697, "cup": 23.99578299, "jpy": 158.02740002, "cny": 6.68730243}};
function currencyInfo(){
  const k = (typeof prefs !== 'undefined' && prefs && CURRENCIES[prefs.currency]) ? prefs.currency : 'USD';
  const c = Object.assign({ code:k }, CURRENCIES[k]);
  try{ c.dotThousands = (1.5).toLocaleString(c.locale).includes(','); }catch(e){ c.dotThousands = false; }
  return c;
}

function hexA(hex, a){ const n = parseInt(hex.slice(1), 16); return `rgba(${n>>16},${(n>>8)&255},${n&255},${a})`; }
function applyPrefs(){
  const r = document.documentElement.style;
  const ac = ACCENTS[prefs.accent] || ACCENTS.verde, bg = BACKGROUNDS[prefs.bg] || BACKGROUNDS.grafito, f = FONTS[prefs.font] || FONTS.clasica;
  r.setProperty('--accent', ac.c); r.setProperty('--on-accent', ac.on);
  r.setProperty('--accent-soft', hexA(ac.c, .16)); r.setProperty('--accent-line', hexA(ac.c, .5));
  r.setProperty('--bg', bg.bg); r.setProperty('--surface', bg.s); r.setProperty('--surface-raised', bg.r); r.setProperty('--line', bg.l);
  r.setProperty('--serif', f.title); r.setProperty('--sans', f.body);
  document.body && (document.body.style.zoom = (SIZES[prefs.size] || SIZES.normal).z);
  const meta = document.querySelector('meta[name=theme-color]'); if(meta) meta.content = bg.bg;
}
function setPref(k, v){ prefs[k] = v; savePrefs(); applyPrefs(); render(); }

function firstName(){ return (prefs.name || '').trim().split(/\s+/)[0] || ''; }
function greetingLine(){
  const n = firstName();
  const h = new Date().getHours();
  const part = h < 12 ? 'Buenos días' : h < 19 ? 'Buenas tardes' : 'Buenas noches';
  const who = n ? `<b class="greet-name">${escapeAttr(n)}</b>` : '';
  if(prefs.greet === 'parce'){
    const day = Math.floor(Date.now() / 86400000);
    const opts = h >= 19 || h < 5
      ? ['Buenas noches', '¿Cómo te fue hoy', '¿Qué más pues']
      : ['¿Qué más', 'Buenas', '¿Cómo va todo', '¿Qué hubo'];
    const o = opts[day % opts.length];
    const q = o.startsWith('¿');
    return n ? `${o}, ${who}${q ? '?' : ''}` : `${o}${q ? '?' : ''}`;
  }
  const icon = prefs.greet === 'emoji' ? (h < 12 ? '☀️ ' : h < 19 ? '🌤️ ' : '🌙 ') : '';
  return `${icon}${part}${n ? ', ' + who : ''}`;
}

// ---------------------------------------------------------------- bienvenida
let obStep = 0, obError = '';
function needsOnboarding(){ return !prefs.onboarded; }
function obGo(n){ obError = ''; obStep = n; render(); window.scrollTo(0,0); setTimeout(() => { const el = document.querySelector('.ob [autofocus]'); if(el) el.focus(); }, 60); }
function obSaveName(){ const v = (document.getElementById('ob-name') || {}).value || ''; prefs.name = v.trim(); savePrefs(); obGo(2); }
function obSaveBalance(){
  const el = document.getElementById('ob-bal');
  if(el && el.value.trim() !== ''){ data.settings.currentBalance = parseMoney(el.value); saveData(); }
  obGo(4);
}
function obFinish(){ prefs.onboarded = true; savePrefs(); obStep = 0; activeTab = 'home'; render(); window.scrollTo(0,0); if(!prefs.toured) setTimeout(() => startTour(), 350); else showToast(firstName() ? `¡Listo, ${firstName()}! Todo quedó guardado` : '¡Listo! Todo quedó guardado'); }
function reopenOnboarding(){ prefs.onboarded = false; savePrefs(); obGo(1); }
function obRestoreFile(input){
  const f = input.files && input.files[0]; input.value = '';
  if(!f) return;
  if(previewMode){ obError = 'En la vista previa no se restaura nada. Sal de la vista previa primero.'; render(); return; }
  readFileText(f).then(txt => {
    let parsed; try{ parsed = JSON.parse(txt); }catch(e){ obError = 'Ese archivo no es un respaldo de la app.'; render(); return; }
    if(!parsed || typeof parsed !== 'object' || !(parsed.debts || parsed.bank || parsed.incomes || parsed.ledger)){ obError = 'Ese archivo no parece un respaldo de Control Financiero.'; render(); return; }
    restoreAnyBackup(parsed);
    obGo(1); showToast('Respaldo restaurado ✓');
  }).catch(() => { obError = 'No pude abrir el archivo.'; render(); });
}
// Acepta el respaldo viejo (solo finanzas) y el completo (finanzas + subapps + preferencias).
function restoreAnyBackup(parsed){
  if(parsed.ledger){
    Object.keys(parsed.extra || {}).forEach(k => { try{ localStorage.setItem(k, typeof parsed.extra[k] === 'string' ? parsed.extra[k] : JSON.stringify(parsed.extra[k])); }catch(e){} });
    if(parsed.prefs){ prefs = Object.assign(defaultPrefs(), parsed.prefs, { onboarded: prefs.onboarded }); savePrefs(); applyPrefs(); }
    parsed = parsed.ledger;
  }
  data = normalizeData(parsed); saveBlocked = false; loadState = 'ok'; saveData();
}

function swatches(kind){
  if(kind === 'accent') return `<div class="swatches">${Object.entries(ACCENTS).map(([k,a]) => `<button class="sw ${prefs.accent===k?'on':''}" style="background:${a.c};" onclick="setPref('accent','${k}')" aria-label="${a.label}"></button>`).join('')}</div>`;
  if(kind === 'bg') return `<div class="opt-grid">${Object.entries(BACKGROUNDS).map(([k,b]) => `<button class="opt ${prefs.bg===k?'on':''}" onclick="setPref('bg','${k}')"><span class="bgdot" style="background:${b.bg};box-shadow:inset 0 0 0 6px ${b.r};"></span>${b.label}</button>`).join('')}</div>`;
  if(kind === 'font') return `<div class="opt-grid">${Object.entries(FONTS).map(([k,f]) => `<button class="opt ${prefs.font===k?'on':''}" onclick="setPref('font','${k}')"><span style="font-family:${f.title.replace(/"/g,"'")};font-size:17px;">Aa</span>${f.label}</button>`).join('')}</div>`;
  if(kind === 'size') return `<div class="tab-row">${Object.entries(SIZES).map(([k,s]) => `<button class="tab-btn ${prefs.size===k?'active':''}" onclick="setPref('size','${k}')">${s.label}</button>`).join('')}</div>`;
  if(kind === 'greet'){
    const n = firstName() || 'Brandon';
    const ex = { formal:`Buenos días, ${n}`, parce:`¿Qué más, ${n}?`, emoji:`☀️ Buenos días, ${n}` };
    return `<div class="tab-row" style="flex-wrap:wrap;">${Object.keys(GREET_STYLES).map(k => `<button class="tab-btn ${prefs.greet===k?'active':''}" onclick="setPref('greet','${k}')">${escapeAttr(ex[k])}</button>`).join('')}</div>`;
  }
  return '';
}
function renderPersonalize(){
  return `
    <p class="ob-label">Color</p>${swatches('accent')}
    <p class="ob-label">Fondo</p>${swatches('bg')}
    <p class="ob-label">Letra de los títulos</p>${swatches('font')}
    <p class="ob-label">Tamaño</p>${swatches('size')}
    <p class="ob-label">Saludo</p>${swatches('greet')}`;
}
function obDots(){ return `<div class="ob-dots">${[1,2,3,4].map(i => `<span class="${obStep>=i?'on':''}"></span>`).join('')}</div>`; }
function renderOnboarding(){
  const n = firstName();
  let body = '';
  if(obStep === 0){
    body = `<div class="ob-hero">
        <div class="ob-logo">${ICONS.home}</div>
        <p class="ob-title">Bienvenido a<br>Control Financiero</p>
        <p class="ob-text">Tus ingresos, gastos, deudas y pagos en un solo lugar. Te voy a hacer unas preguntas rápidas para dejarla a tu gusto.</p>
      </div>
      <button class="btn btn-solid ob-main" onclick="obGo(1)">Empezar</button>
      <label class="btn ob-main" for="ob-restore" style="display:block;text-align:center;margin-top:10px;">Vengo de otro teléfono: restaurar respaldo</label>
      <input type="file" id="ob-restore" accept=".json,application/json,text/plain" style="display:none" onchange="obRestoreFile(this)">
      ${obError ? `<div class="error-box">${escapeAttr(obError)}</div>` : ''}
      <p class="ob-credit">Creada por <b>Brandon Poveda</b></p>`;
  } else if(obStep === 1){
    body = `${obDots()}<p class="ob-q">¿Cómo te llamas?</p>
      <p class="ob-text">Para saludarte arriba cada vez que abras la app.</p>
      <input type="text" id="ob-name" class="ob-input" placeholder="Tu nombre" value="${escapeAttr(prefs.name)}" autofocus autocomplete="given-name" onkeydown="if(event.key==='Enter')obSaveName()">
      <button class="btn btn-solid ob-main" onclick="obSaveName()">Siguiente</button>`;
  } else if(obStep === 2){
    body = `${obDots()}<p class="ob-q">¿Cada cuánto te pagan?</p>
      <p class="ob-text">La app te muestra cuánto entra, cuánto sale y si vas cubierto en cada periodo de pago.</p>
      ${renderPayOptions()}
      <button class="btn btn-solid ob-main" onclick="obGo(3)">Siguiente</button>
      <button class="card-link ob-skip" onclick="obGo(1)">← Atrás</button>`;
  } else if(obStep === 3){
    const cur = (data.settings && data.settings.currentBalance) || 0;
    body = `${obDots()}<p class="ob-q">¿En qué moneda?</p>
      ${renderCurrencyOptions()}
      <p class="ob-q" style="margin-top:24px;">¿Cuánto tienes hoy en el banco?</p>
      <p class="ob-text">Es tu punto de partida. Después se actualiza solo cuando subes extractos o capturas.</p>
      <div class="ob-money"><span>${currencyInfo().sym}</span><input type="number" id="ob-bal" inputmode="decimal" step="0.01" placeholder="0.00" value="${cur ? cur.toFixed(2) : ''}" autofocus onkeydown="if(event.key==='Enter')obSaveBalance()"></div>
      <button class="btn btn-solid ob-main" onclick="obSaveBalance()">Siguiente</button>
      <button class="card-link ob-skip" onclick="obGo(4)">Saltar por ahora</button>`;
  } else {
    body = `${obDots()}<p class="ob-q">Ponla a tu gusto</p>
      <div class="ob-preview"><p class="greeting">${greetingLine()}</p><p class="page-title">Inicio</p></div>
      ${renderPersonalize()}
      <button class="btn btn-solid ob-main" style="margin-top:18px;" onclick="obFinish()">${n ? `¡Listo, ${escapeAttr(n)}!` : '¡Listo!'}</button>
      <button class="card-link ob-skip" onclick="obGo(3)">← Atrás</button>`;
  }
  return `<div class="ob">${body}</div>`;
}

// ---------------------------------------------------------------- respaldo completo
function fullBackupObject(){
  const extra = {};
  ['ingles-data','interes-data','notas-data','notes-data','rotador-data'].forEach(k => { try{ const v = localStorage.getItem(k); if(v) extra[k] = v; }catch(e){} });
  try{ for(let i = 0; i < localStorage.length; i++){ const k = localStorage.key(i); if(k && k !== STORAGE_KEY && k !== PREFS_KEY && !(k in extra)) extra[k] = localStorage.getItem(k); } }catch(e){}
  return { app:'control-financiero', version:2, date: todayISO(), ledger: data, prefs, extra };
}
