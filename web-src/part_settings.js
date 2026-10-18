
// ================================================================ AJUSTES · AYUDA · VISTA PREVIA
// ---- opciones de pago y moneda (se usan en la bienvenida y en Ajustes) ----
function setPay(k, v){ prefs[k] = v; savePrefs(); render(); }
function renderPayOptions(){
  const f = payFreq();
  let extra = '';
  if(f === 'semanal'){
    extra = `<p class="ob-label">¿Qué día te pagan? (ahí empieza tu semana)</p>
      <div class="opt-grid days">${[1,2,3,4,5,6,0].map(d => `<button class="opt ${prefs.weekStart===d?'on':''}" onclick="setPay('weekStart', ${d})">${capFirst(WEEKDAYS[d])}</button>`).join('')}</div>`;
  } else if(f === 'biweekly'){
    extra = `<p class="ob-label">¿Cuándo fue tu último pago?</p>
      <input type="date" class="ob-input" value="${prefs.payAnchor || todayISO()}" onchange="setPay('payAnchor', this.value)">`;
  } else if(f === 'quincenal'){
    extra = `<p class="item-meta" style="margin:10px 0 14px 0;">Tus quincenas van del 1 al 15 y del 16 a fin de mes.</p>`;
  } else {
    extra = `<p class="ob-label">¿Qué día del mes te pagan?</p>
      <input type="number" class="ob-input" min="1" max="28" inputmode="numeric" value="${prefs.payDay || 30}" onchange="setPay('payDay', Math.min(28, Math.max(1, parseInt(this.value)||1)))">
      <p class="item-meta" style="margin:-6px 0 14px 0;">Si te pagan el 30 o 31, pon 28: tu mes va de ese día al anterior del mes siguiente.</p>`;
  }
  return `<div class="opt-grid two">${Object.entries(PAY_FREQS).map(([k,p]) => `<button class="opt ${f===k?'on':''}" onclick="${k==='biweekly' && !prefs.payAnchor ? `prefs.payAnchor='${todayISO()}';` : ''}setPay('payFreq','${k}')">${p.label}</button>`).join('')}</div>${extra}`;
}
function currencySelect(id, value, onchange){
  return `<select id="${id}" class="cur-select" onchange="${onchange}">${Object.entries(CURRENCIES).map(([k,c]) => `<option value="${k}" ${value===k?'selected':''}>${c.country} — ${c.label}</option>`).join('')}</select>`;
}
function renderCurrencyOptions(){
  const cur = currencyInfo().code;
  return `<div class="opt-grid two">${['USD','COP','MXN','EUR'].map(k => `<button class="opt ${cur===k?'on':''}" onclick="setPay('currency','${k}')">${CURRENCIES[k].country.split(' ')[0]} ${CURRENCIES[k].label}</button>`).join('')}</div>
    <p class="item-meta" style="margin:10px 0 6px 0;">¿Otro país?</p>
    ${currencySelect('cur-sel', cur, "setPay('currency', this.value)")}
    <p class="item-meta" style="margin-top:8px;">Así se verán tus montos: <b style="color:var(--ink);">${fmtMoney(1234567.5)}</b></p>`;
}

// ---- página de Ajustes ----
let settingsOpen = { perfil:true, apariencia:false, pagos:false, datos:false };
function toggleSet(k){ settingsOpen[k] = !settingsOpen[k]; render(); }
function setBlock(k, title, sub, body){
  return `<div class="card set-card">
    <button class="set-head" onclick="toggleSet('${k}')"><div><p class="card-title">${title}</p><p class="card-sub">${sub}</p></div><span class="chevron ${settingsOpen[k]?'open':''}">›</span></button>
    ${settingsOpen[k] ? `<div class="set-body">${body}</div>` : ''}
  </div>`;
}
function renderSettingsPage(){
  return `
    ${setBlock('perfil', 'Perfil', 'Tu nombre y cómo te saludo', `
      <p class="ob-label">Tu nombre</p>
      <input type="text" class="ob-input" value="${escapeAttr(prefs.name)}" placeholder="Tu nombre" onchange="setPref('name', this.value.trim())">
      <p class="ob-label">Saludo</p>${swatches('greet')}`)}
    ${setBlock('apariencia', 'Apariencia', 'Color, fondo, letra y tamaño', `
      <p class="ob-label">Color</p>${swatches('accent')}
      <p class="ob-label">Fondo</p>${swatches('bg')}
      <p class="ob-label">Letra de los títulos</p>${swatches('font')}
      <p class="ob-label">Tamaño</p>${swatches('size')}`)}
    ${setBlock('pagos', 'Pagos y moneda', `${PL().label} · ${currencyInfo().label}`, `
      <p class="ob-label">¿Cada cuánto te pagan?</p>${renderPayOptions()}
      <p class="ob-label">Moneda</p>${renderCurrencyOptions()}`)}
    ${setBlock('datos', 'Tus datos', 'Respaldo y restaurar', `
      <p class="item-meta" style="margin-bottom:10px;">El respaldo guarda todo: finanzas, Inglés, Interés compuesto, Notas y tus ajustes. Guárdalo de vez en cuando, por si cambias o se daña el teléfono.</p>
      <div class="backup-row">
        <button class="btn backup-btn" onclick="downloadBackup()">⬇ Guardar archivo</button>
        <button class="btn backup-btn" onclick="toggleBackupBox()">📋 Copiar</button>
        <button class="btn backup-btn" onclick="toggleRestoreBox()">⬆ Restaurar</button>
      </div>
      ${renderBackupBox()}
      ${renderRestoreDialogs()}`)}
    <div class="card">
      <p class="card-title">Ayuda</p>
      <div class="set-links">
        <button onclick="openHelp()">❓ Preguntas frecuentes y buscar</button>
        <button onclick="startTour()">▶ Recorrido guiado</button>
        <button onclick="openVideo()">🎬 Ver video tutorial</button>
        <button onclick="startPreview()">👀 Ver la app como alguien nuevo</button>
        <button onclick="reopenOnboarding()">↺ Repetir la bienvenida</button>
      </div>
    </div>
    <div class="card about">
      <p class="card-title">Acerca de</p>
      <p class="about-by">Creada por <b>Brandon Poveda</b></p>
      <p class="item-meta">Versión ${APP_VERSION} · © ${new Date().getFullYear()} Brandon Poveda. Todos los derechos reservados.</p>
      <p class="item-meta" style="margin-top:8px;">Traducciones del vocabulario: Wiktionary (CC BY-SA). Frases de ejemplo: Tatoeba (CC BY 2.0 FR). Lector de PDF: pdf.js (Apache 2.0).</p>
    </div>`;
}
function openSettings(){ activeTab = 'settings'; render(); window.scrollTo(0,0); }

// ---- apps plegables en Inicio ----
function toggleApps(){ prefs.appsOpen = !prefs.appsOpen; savePrefs(); render(); }
function renderAppsBar(){
  if(prefs.appsOpen){
    return `<div class="apps-fold open" data-tour="apps">
      <button class="apps-fold-head" onclick="toggleApps()"><span>Mis apps</span><span class="chevron open">›</span></button>
      ${renderAppsRow().replace(' data-tour="apps"', '')}
    </div>`;
  }
  return `<div class="apps-fold" data-tour="apps">
    <div class="apps-mini">${SUB_APPS.map(([k,l,ic]) => `<button class="app-mini" onclick="openSubApp('${k}')" aria-label="${l}">${ic}<span>${l.split(' ')[0]}</span></button>`).join('')}</div>
    <button class="apps-more" onclick="toggleApps()" aria-label="Desplegar apps"><span class="chevron">›</span></button>
  </div>`;
}

// ---- Centro de ayuda: buscar + preguntas frecuentes ----
const HELP_GO = [
  { t:'Ver tu semana / quincena / mes', k:'inicio resumen semana quincena mes cubierto falta meta', go:"setTab('home')" },
  { t:'Agregar un ingreso', k:'ingreso pago sueldo tatuaje agregar plata entra', go:"setTab('mov');setMovSeg('incomes')" },
  { t:'Gastos hormiga', k:'hormiga gasto pequeño cafe limite', go:"setTab('mov');setMovSeg('hormiga')" },
  { t:'Movimientos del banco', k:'banco movimientos extracto categoria cambiar', go:"setTab('mov');setMovSeg('bank')" },
  { t:'Préstamos', k:'prestamo preste me deben devolver', go:"setTab('mov');setMovSeg('loans')" },
  { t:'Agregar una deuda', k:'deuda debo tarjeta carro credito agregar', go:"setTab('debts');setDebtSeg('debts')" },
  { t:'Gastos fijos (renta, teléfono…)', k:'gasto fijo renta arriendo telefono luz servicios mensual semanal', go:"setTab('debts');setDebtSeg('expenses')" },
  { t:'Cobros que se repiten', k:'cobro repetido suscripcion netflix recurrente', go:"setTab('debts');setDebtSeg('recurring')" },
  { t:'Subir extracto del banco', k:'extracto pdf csv banco subir statement', go:"setTab('import')" },
  { t:'Subir captura de pantalla', k:'captura screenshot foto pantalla banco', go:"setTab('import')" },
  { t:'Ahorro y estadísticas', k:'ahorro fondo emergencia inversion estadisticas promedio', go:"setTab('more')" },
  { t:'Exportar resumen para Claude', k:'exportar resumen claude analizar', go:"setTab('more')" },
  { t:'Ajustes: color, letra, nombre, moneda', k:'ajustes configuracion color fondo letra fuente tamaño nombre saludo moneda pesos quincena', go:"openSettings()" },
  { t:'Cambio de moneda (dólares a pesos…)', k:'cambio moneda convertir dolares pesos tasa divisa remesa mandar plata', go:"setTab('more');setTimeout(()=>{const e=document.querySelector('[data-tour=fx]'); if(e) e.scrollIntoView({block:'center'});},80)" },
  { t:'Respaldo y restaurar', k:'respaldo backup restaurar otro telefono copia', go:"settingsOpen.datos=true;openSettings()" },
  { t:'App de Inglés', k:'ingles palabras aprender frases', go:"openSubApp('ingles')" },
  { t:'Interés compuesto', k:'interes compuesto victorias plan crecer', go:"openSubApp('interes')" },
  { t:'Guardián: riesgo y meta en vivo', k:'guardian riesgo meta capital trading broker saldo alerta stop', go:"openSubApp('guardian')" },
  { t:'Notas por voz', k:'notas voz dictar apuntar', go:"openSubApp('notas')" }
];
const FAQ = [
  { c:'Empezar', q:'¿Por dónde empiezo?', a:'1) En <b>Deudas</b> agrega lo que debes y tus gastos fijos (renta, teléfono…). 2) En <b>Subir extracto</b> sube el PDF de tu banco, o anota tus ingresos a mano en <b>Movimientos</b>. 3) En <b>Inicio</b> ves si vas cubierto.' },
  { c:'Empezar', q:'¿Qué significa "Vas cubierto" o "Te falta ganar"?', a:'Compara lo que ganaste en el periodo (semana, quincena o mes), menos lo que gastaste, contra lo que necesitas para tus deudas y gastos fijos. Si te sobra, vas cubierto; si no, te dice cuánto te falta.' },
  { c:'Empezar', q:'¿Qué es la "meta"?', a:'Es lo que deberías apartar en cada periodo para cubrir todos tus pagos del mes, repartido parejo. Si en un periodo vence algo grande, la meta sube a lo que vence.' },
  { c:'Empezar', q:'¿Cómo cambio si me pagan por semana, quincena o mes?', a:'En <b>⚙ Ajustes → Pagos y moneda</b>. Toda la app (Inicio, metas, estadísticas) se ajusta a ese periodo.' },
  { c:'Movimientos', q:'¿Cómo anoto un ingreso a mano?', a:'<b>Movimientos → Ingresos → + Añadir ingreso</b>. Pon el monto, la fecha y de dónde vino (trabajo, tatuaje, venta…).' },
  { c:'Movimientos', q:'¿Qué es un gasto hormiga?', a:'Los gastos pequeños del día a día (café, snacks, propinas) que sumados se llevan mucha plata. Ponles un límite en <b>Movimientos → Hormiga</b> y la app te avisa si te pasas.' },
  { c:'Movimientos', q:'¿Cómo registro que le presté plata a alguien?', a:'<b>Movimientos → Préstamos</b>. Puedes ponerle fecha de devolución y la app te recuerda. Cuando te paguen, márcalo como devuelto.' },
  { c:'Banco', q:'¿Cómo subo mi extracto?', a:'Descarga el extracto en PDF o CSV desde la página de tu banco y súbelo en <b>Subir extracto</b>. Puedes elegir varios meses a la vez; los repetidos no se cuentan dos veces.' },
  { c:'Banco', q:'El extracto no trae lo de esta semana, ¿qué hago?', a:'Toma captura de pantalla de tus movimientos en la app del banco y súbela con <b>Subir captura de pantalla</b>. Revisa lo que leyó, corrige si hace falta y guarda. Cuando llegue el extracto, la app reconoce esos movimientos y no los duplica.' },
  { c:'Banco', q:'Un movimiento quedó en la categoría equivocada', a:'Toca el movimiento en <b>Movimientos → Banco</b> y cámbiale la categoría. Si se lo confirmas dos veces, la app lo aprende para ese comercio.' },
  { c:'Banco', q:'¿Qué son las preguntas que salen en Inicio?', a:'Cuando la app no está segura de qué es un movimiento (por ejemplo una transferencia), te pregunta. Respondes una vez y aprende. Puedes tocar <b>Después</b> o <b>Descartar</b>.' },
  { c:'Banco', q:'¿Funciona con bancos de Colombia?', a:'Las capturas de pantalla sirven con casi cualquier app de banco. El extracto en PDF está hecho para TD Bank; con otros bancos lo más seguro es usar capturas o anotar a mano.' },
  { c:'Deudas y pagos', q:'¿Cómo agrego una deuda?', a:'<b>Deudas → Deudas → + Añadir deuda</b>. Pon cuánto debes, cuánto pagas al mes y la fecha de pago. Los pagos del banco se pueden conectar a la deuda para que el saldo baje solo.' },
  { c:'Deudas y pagos', q:'¿Cómo marco que ya pagué algo?', a:'En <b>Inicio → Próximos pagos</b> toca <b>Ya lo pagué</b>. Si el pago viene del banco, la app lo conecta sola.' },
  { c:'Deudas y pagos', q:'Necesito correr un pago unos días', a:'Toca <b>Mover</b> en el pago (en Inicio). Solo cambia esa vez; el siguiente vuelve a su fecha normal.' },
  { c:'Deudas y pagos', q:'Tengo algo repetido (como el carro dos veces)', a:'Toca la deuda o el gasto y usa <b>Unir con…</b> para juntarlos en uno solo.' },
  { c:'Apps', q:'¿Dónde están las otras apps?', a:'Arriba en <b>Inicio</b>, en la barra de apps. Toca un ícono para abrirla o la flecha ▾ para verlas en grande.' },
  { c:'Apps', q:'¿Cómo uso la app de Inglés?', a:'Ábrela desde Inicio y toca <b>Estudiar ahora</b>: 10 palabras nuevas por día con repaso. En <b>Mis frases</b> practicas frases y con <b>Practicar con Claude</b> exportas tu progreso para hablar en inglés.' },
  { c:'Apps', q:'¿Cómo uso Interés compuesto?', a:'Pon tu capital, el % por victoria y la meta. Cada vez que ganes toca <b>Gané</b>; si paras antes o pierdes, usa <b>Otro resultado</b> y pon en cuánto quedaste.' },
  { c:'Ajustes y datos', q:'¿Cómo cambio el color, la letra o mi nombre?', a:'Toca <b>⚙</b> arriba a la derecha → <b>Apariencia</b> o <b>Perfil</b>.' },
  { c:'Ajustes y datos', q:'¿Mis datos se guardan en internet?', a:'No. Todo queda solo en tu teléfono. Por eso conviene guardar un respaldo de vez en cuando (<b>⚙ Ajustes → Tus datos</b>).' },
  { c:'Ajustes y datos', q:'Cambié de teléfono, ¿cómo paso mis datos?', a:'En el teléfono viejo: <b>⚙ Ajustes → Tus datos → Guardar archivo</b>. Pasa ese archivo al nuevo (WhatsApp, correo, Drive). En el nuevo, al abrir la app toca <b>Vengo de otro teléfono</b> y elige el archivo.' },
  { c:'Ajustes y datos', q:'¿Puedo poner la app en pesos colombianos?', a:'Sí: <b>⚙ Ajustes → Pagos y moneda → Peso colombiano</b>. Los montos se ven como $1.500.000.' },
  { c:'Ajustes y datos', q:'¿Cuántos pesos son mis dólares?', a:'En <b>Más → Cambio de moneda</b> pones el monto y eliges las dos monedas. Las tasas se actualizan solas cuando hay internet; sin internet usa las últimas guardadas.' },
  { c:'Ajustes y datos', q:'Mi país no usa dólares, ¿qué moneda pongo?', a:'En <b>⚙ Ajustes → Pagos y moneda</b> eliges tu país: Colombia, México, Perú, Chile, Argentina, Venezuela, Centroamérica, Rep. Dominicana, España y más. Los montos se muestran como se escriben allá.' },
  { c:'Ajustes y datos', q:'¿Puedo ver otra vez el tutorial?', a:'Sí: botón <b>Tutorial</b> arriba, o <b>⚙ Ajustes → Ayuda</b>. Ahí está el recorrido, el video y la opción de ver la app como alguien nuevo.' }
];
const HELP_SUGGEST = ['subir extracto', 'captura', 'agregar deuda', 'ya lo pagué', 'cambio de moneda', 'gasto hormiga', 'quincena', 'pesos', 'cambiar color', 'respaldo', 'inglés'];
let helpQ = '', helpOpenIdx = -1;
function openHelp(q){ helpQ = q || ''; helpOpenIdx = -1; activeTab = 'help'; render(); window.scrollTo(0,0); setTimeout(() => { const el = document.getElementById('help-q'); if(el && !q) el.focus(); }, 80); }
function normTxt(s){ return String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/<[^>]+>/g, ' '); }
function helpScore(hay, q){
  const words = normTxt(q).split(/\s+/).filter(w => w.length >= 2);
  if(!words.length) return 0;
  const h = normTxt(hay); let sc = 0;
  for(const w of words){ const stem = w.length > 4 ? w.slice(0, w.length - 1) : w; if(h.includes(w)) sc += 3; else if(h.includes(stem)) sc += 2; else return 0; }
  return sc;
}
function setHelpQ(v){ helpQ = v; helpOpenIdx = -1; const el = document.getElementById('help-results'); if(el) el.innerHTML = renderHelpResults(); }
function pickSuggest(s){ helpQ = s; helpOpenIdx = -1; render(); }
function toggleFaq(i){ helpOpenIdx = helpOpenIdx === i ? -1 : i; const el = document.getElementById('help-results'); if(el) el.innerHTML = renderHelpResults(); }
function faqItem(f, i){
  const open = helpOpenIdx === i;
  return `<div class="faq ${open?'open':''}"><button class="faq-q" onclick="toggleFaq(${i})"><span>${f.q}</span><span class="chevron ${open?'open':''}">›</span></button>${open ? `<div class="faq-a">${f.a}</div>` : ''}</div>`;
}
function renderHelpResults(){
  const q = helpQ.trim();
  if(!q){
    const cats = [...new Set(FAQ.map(f => f.c))];
    return cats.map(c => `<p class="ob-label">${c}</p>` + FAQ.map((f,i) => [f,i]).filter(([f]) => f.c === c).map(([f,i]) => faqItem(f, i)).join('')).join('');
  }
  const goes = HELP_GO.map(g => ({ g, s: helpScore(g.t + ' ' + g.k, q) })).filter(x => x.s).sort((a,b) => b.s - a.s).slice(0, 4);
  const faqs = FAQ.map((f,i) => ({ f, i, s: helpScore(f.q + ' ' + f.a + ' ' + f.c, q) + helpScore(f.q, q) })).filter(x => x.s).sort((a,b) => b.s - a.s);
  if(!goes.length && !faqs.length) return `<p class="empty-note">No encontré nada con "${escapeAttr(q)}". Prueba con otra palabra, por ejemplo: ${HELP_SUGGEST.slice(0,3).join(', ')}.</p>`;
  return (goes.length ? `<p class="ob-label">Ir a</p><div class="help-go">${goes.map(x => `<button onclick="${x.g.go}">${x.g.t}<span>›</span></button>`).join('')}</div>` : '')
    + (faqs.length ? `<p class="ob-label">Preguntas</p>` + faqs.map(x => faqItem(x.f, x.i)).join('') : '');
}
function renderHelpPage(){
  return `<div class="help-search">
      <span class="help-ico">${ICONS.search}</span>
      <input type="search" id="help-q" placeholder="Buscar: extracto, deuda, color…" value="${escapeAttr(helpQ)}" oninput="setHelpQ(this.value)" autocomplete="off">
    </div>
    <div class="help-sugg">${HELP_SUGGEST.map(s => `<button class="chip" onclick="pickSuggest('${s}')">${s}</button>`).join('')}</div>
    <div class="card help-tut">
      <p class="card-title">Tutorial</p>
      <div class="set-links">
        <button onclick="startTour()">▶ Recorrido guiado por la app</button>
        <button onclick="openVideo()">🎬 Ver video tutorial con voz (2:30)</button>
        <button onclick="startPreview()">👀 Ver la app como alguien nuevo</button>
      </div>
    </div>
    <div class="card"><div id="help-results">${renderHelpResults()}</div></div>`;
}

// ---- Vista previa: la app vacía, como recién instalada, sin tocar tus datos ----
let previewMode = false, previewSaved = null;
function startPreview(){
  if(previewMode) return;
  previewSaved = { data: JSON.stringify(data), prefs: JSON.stringify(prefs), tab: activeTab };
  previewMode = true;
  data = normalizeData(emptyData());
  prefs = defaultPrefs();
  applyPrefs(); obStep = 0; activeTab = 'home';
  render(); window.scrollTo(0,0);
}
function endPreview(){
  if(!previewMode) return;
  if(tourActive()){ tourIdx = -1; document.body.classList.remove('touring'); const r = document.getElementById('tour-root'); if(r) r.innerHTML = ''; }
  data = normalizeData(JSON.parse(previewSaved.data));
  prefs = Object.assign(defaultPrefs(), JSON.parse(previewSaved.prefs));
  previewMode = false; activeTab = 'home'; previewSaved = null;
  applyPrefs(); render(); window.scrollTo(0,0);
  showToast('Saliste de la vista previa. Tus datos están intactos.');
}
function previewBanner(){ return previewMode ? `<div class="preview-bar"><span>👀 Vista previa: así la ve alguien nuevo. Nada se guarda.</span><button onclick="endPreview()">Salir</button></div>` : ''; }
