
// ================================================================ RECORRIDO GUIADO
// Oscurece la pantalla, ilumina una parte de la app y explica para qué sirve.
const TOUR = [
  { tab:'home', sel:null, title:'Bienvenido a tu app 👋', text:'Te muestro en un minuto dónde está cada cosa. Toca <b>Siguiente</b> para avanzar o <b>Saltar</b> si ya la conoces.' },
  { tab:'home', sel:'topbar', title:'Tu saludo', text:'Aquí arriba te saludo y ves en qué pantalla estás. A la derecha: <b>Tutorial</b> y 🔍 abren la ayuda, y ⚙ los ajustes.' },
  { tab:'home', sel:'apps', title:'Tus apps', text:'Rotador, Bot Señales, Notas, Inglés e Interés compuesto. Toca un ícono para abrir la app, o la flecha › para verlas en grande.' },
  { tab:'home', sel:'questions', title:'Preguntas de la app', text:'Cuando la app no está segura de qué es un movimiento del banco, te pregunta aquí. Respondes una vez y aprende para la próxima.' },
  { tab:'home', sel:'hero', title:'Tu {noun}', text:'Lo más importante: cuánto entró {art}, cuánto gastaste y si vas cubierto para lo que tienes que pagar. Verde = vas bien.' },
  { tab:'home', sel:'weeks', title:'Lo que viene', text:'Cuánto te conviene ganar en cada uno de los próximos periodos según lo que vence. Toca uno para ver qué pagos caen ahí.' },
  { tab:'home', sel:'upcoming', title:'Próximos pagos', text:'Lo que vence en los próximos 30 días. Toca <b>Ya lo pagué</b> cuando pagues, o <b>Mover</b> para correr un pago unos días.' },
  { tab:'home', sel:'spend', title:'En qué se te va la plata', text:'Tus gastos del mes por tipo: hormiga, fijos, deudas y otros. Más abajo ves de dónde entra la plata y tus semanas en gráfica.' },
  { tab:'mov', sel:'tab-mov', title:'Movimientos', text:'Aquí está todo lo que entra y sale.' },
  { tab:'mov', sel:'movseg', title:'Ingresos, hormiga, banco y préstamos', text:'<b>Ingresos</b>: tus pagos y tatuajes. <b>Hormiga</b>: gastos chiquitos. <b>Banco</b>: todo lo que vino del extracto. <b>Préstamos</b>: plata que prestas o te prestan.' },
  { tab:'debts', sel:'debtsum', title:'Deudas y gastos fijos', text:'Cuánto debes en total y cuánto pagas al mes. Abajo agregas deudas, gastos fijos (renta, teléfono…) y ves los cobros que se repiten.' },
  { tab:'debts', sel:'debtseg', title:'Si algo sale repetido', text:'Toca un gasto o deuda para editarlo. Si dos son lo mismo (como el carro), usa <b>Unir con…</b> para que no se cuente dos veces.' },
  { tab:'import', sel:'upload', title:'Subir extracto', text:'Descarga el PDF de TD Bank y súbelo aquí. La app saca todos los movimientos sola y no repite los que ya tenías.' },
  { tab:'import', sel:'shot', title:'O una captura', text:'Si el extracto no trae lo de esta semana, toma captura de pantalla en la app del banco y súbela aquí.' },
  { tab:'more', sel:'sec-savings', title:'Más', text:'Ahorro, estadísticas, resumen por fechas, historial y tus apps.' },
  { tab:'more', sel:'export', title:'Pásamelo a mí', text:'Exporta un resumen y pégamelo en un chat para que te ayude a analizar tus finanzas.' },
  { tab:'more', sel:'backup', title:'Respaldo', text:'Guarda un archivo con todo (finanzas y apps) de vez en cuando. Si cambias de teléfono, lo restauras y queda todo igual.' },
  { tab:'home', sel:'btn-settings', title:'Ajustes ⚙', text:'Tu nombre, colores, letra, tamaño, cada cuánto te pagan y la moneda. Cámbialos cuando quieras.' },
  { tab:'home', sel:'btn-help', title:'Ayuda', text:'Preguntas frecuentes, un buscador para encontrar cualquier parte de la app, el video y este recorrido.' },
  { tab:'home', sel:null, title:'¡Listo! 🎉', text:'Eso es todo. Si se te olvida algo, toca <b>Tutorial</b> arriba.' }
];
let tourIdx = -1, tourAuto = null;
function tourFill(s){ return String(s).replace(/\{noun\}/g, PL().noun).replace(/\{art\}/g, PL().art); }
function tourActive(){ return tourIdx >= 0; }
function startTour(auto){
  tourIdx = 0; tourAuto = auto || null;
  document.body.classList.add('touring');
  tourSavedZoom = document.body.style.zoom; document.body.style.zoom = 1;
  tourShow();
}
let tourSavedZoom = '';
function tourEnd(){
  tourIdx = -1; clearTimeout(tourAuto && tourAuto.t);
  document.body.classList.remove('touring');
  document.body.style.zoom = tourSavedZoom || '';
  const r = document.getElementById('tour-root'); if(r) r.innerHTML = '';
  prefs.toured = true; savePrefs();
  activeTab = 'home'; render(); window.scrollTo(0,0);
}
function tourNext(){ if(tourIdx < TOUR.length - 1){ tourIdx++; tourShow(); } else tourEnd(); }
function tourPrev(){ if(tourIdx > 0){ tourIdx--; tourShow(); } }
function tourEl(st){
  if(!st.sel) return null;
  const els = Array.from(document.querySelectorAll(`[data-tour="${st.sel}"]`));
  return els.find(e => e.offsetParent !== null || e.getClientRects().length) || null;
}
function tourShow(){
  let st = TOUR[tourIdx];
  if(st.tab && activeTab !== st.tab){ activeTab = st.tab; if(st.tab === 'mov') movSeg = 'incomes'; if(st.tab === 'debts') debtSeg = 'debts'; }
  if(st.sel && st.sel.startsWith('sec-')){ /* abre la sección para que se vea */ }
  render();
  let root = document.getElementById('tour-root');
  if(!root){ root = document.createElement('div'); root.id = 'tour-root'; document.body.appendChild(root); }
  let el = tourEl(st);
  // si esa parte no existe todavía (por ejemplo no hay preguntas), se salta
  if(st.sel && !el){ if(tourIdx < TOUR.length - 1){ tourIdx++; return tourShow(); } }
  if(el){
    const fixed = getComputedStyle(el).position === 'fixed' || el.closest('.tabbar');
    if(!fixed){ const r0 = el.getBoundingClientRect(); window.scrollTo(0, Math.max(0, window.scrollY + r0.top - Math.max(90, (window.innerHeight - Math.min(r0.height, window.innerHeight * .5)) / 2 - 60))); }
  } else window.scrollTo(0,0);
  setTimeout(() => tourDraw(el, st), 60);
}
function tourDraw(el, st){
  const root = document.getElementById('tour-root'); if(!root || tourIdx < 0) return;
  const vw = window.innerWidth, vh = window.innerHeight, pad = 8;
  let hole = '', tipPos;
  const n = TOUR.length;
  if(el){
    const r = el.getBoundingClientRect();
    const top = Math.max(6, r.top - pad), left = Math.max(6, r.left - pad);
    const w = Math.min(vw - 12, r.width + pad*2), h = Math.min(vh - 12, r.height + pad*2);
    hole = `<div class="tour-hole" style="top:${top}px;left:${left}px;width:${w}px;height:${h}px;"></div>`;
    const below = vh - (top + h), above = top;
    tipPos = below >= 210 || below > above ? `top:${Math.min(vh - 200, top + h + 12)}px;` : `bottom:${Math.min(vh - 200, vh - top + 12)}px;`;
  } else {
    hole = `<div class="tour-dim"></div>`;
    tipPos = `top:${Math.round(vh * .32)}px;`;
  }
  root.innerHTML = `${hole}
    <div class="tour-tip" style="${tipPos}">
      <div class="tour-count">${tourIdx + 1} de ${n}</div>
      <p class="tour-title">${tourFill(st.title)}</p>
      <p class="tour-text">${tourFill(st.text)}</p>
      <div class="tour-btns">
        <button class="card-link" onclick="tourEnd()" style="color:var(--ink-faint);">Saltar</button>
        <span style="flex:1;"></span>
        ${tourIdx > 0 ? `<button class="btn" onclick="tourPrev()">Atrás</button>` : ''}
        <button class="btn btn-solid" onclick="tourNext()">${tourIdx === n - 1 ? 'Terminar' : 'Siguiente'}</button>
      </div>
    </div>`;
  if(tourAuto){ clearTimeout(tourAuto.t); tourAuto.t = setTimeout(tourNext, tourAuto.ms || 4200); }
}
window.addEventListener('resize', () => { if(tourActive()) tourDraw(tourEl(TOUR[tourIdx]), TOUR[tourIdx]); });

// ---- video tutorial ----
let showVideo = false;
function openVideo(){
  let root = document.getElementById('tour-root');
  if(!root){ root = document.createElement('div'); root.id = 'tour-root'; document.body.appendChild(root); }
  root.innerHTML = `<div class="video-wrap" onclick="if(event.target===this)closeVideo()">
    <div class="video-box">
      <video src="tutorial.mp4" controls autoplay playsinline preload="auto" style="width:100%;border-radius:12px;background:#000;display:block;max-height:78vh;object-fit:contain;"></video>
      <div class="btn-row" style="margin-top:10px;"><button class="btn" style="flex:1;" onclick="closeVideo()">Cerrar</button><button class="btn btn-solid" style="flex:1;" onclick="closeVideo();startTour()">Hacer el recorrido</button></div>
    </div></div>`;
}
function closeVideo(){ const r = document.getElementById('tour-root'); if(r) r.innerHTML = ''; }
