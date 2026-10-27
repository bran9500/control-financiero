
// =====================================================================
//  PANTALLAS
// =====================================================================
let showOlder = {}; // { lista: bool } — meses anteriores escondidos por defecto

const ICONS = {
  search:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>',
  gear:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
  camera:'<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>',
  home:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V20h5v-6h4v6h5V9.5"/></svg>',
  mov:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M7 4v16"/><path d="m3 8 4-4 4 4"/><path d="M17 20V4"/><path d="m21 16-4 4-4-4"/></svg>',
  debts:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="5" width="19" height="14" rx="2.5"/><path d="M2.5 10h19"/><path d="M6.5 15h4"/></svg>',
  import:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 15V3"/><path d="m7 8 5-5 5 5"/><path d="M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"/></svg>',
  more:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="19" cy="12" r="1.3"/></svg>'
};
const TAB_TITLES = { home:'Tu semana', mov:'Movimientos', debts:'Deudas y fijos', import:'Subir extracto', more:'Más', settings:'Ajustes', help:'Ayuda' };

function render(){
  const app = document.getElementById('app');
  if(!loaded){ app.innerHTML = '<p style="color:#8B93A0;padding-top:40px;">Cargando…</p>'; return; }

  if(loadState === 'unavailable'){
    app.innerHTML = `
      <div class="topbar"><div><p class="page-title">Control semanal</p></div></div>
      <div class="load-fail">
        <p class="load-fail-title">El guardado local está bloqueado</p>
        <p class="load-fail-body">Esto pasa sobre todo en modo incógnito/privado muy estricto. Abre la app normal para que pueda guardar tus datos.</p>
      </div>`;
    return;
  }

  if(needsOnboarding()){ app.innerHTML = previewBanner() + renderOnboarding(); return; }
  const c = computeAll();
  let body = '';
  if(activeTab === 'home') body = renderHome(c);
  else if(activeTab === 'mov') body = renderMovTab(c);
  else if(activeTab === 'debts') body = renderDebtsTab(c);
  else if(activeTab === 'import') body = renderImportTab();
  else if(activeTab === 'settings') body = renderSettingsPage();
  else if(activeTab === 'help') body = renderHelpPage();
  else body = renderMoreTab(c);

  app.innerHTML = previewBanner() + renderTopbar() + body + renderTabbar() + renderSheet(c);
  renderStatusOnly();
}

function renderTopbar(){
  const todayStr = capFirst(new Date().toLocaleDateString('es-ES', {weekday:'long', day:'numeric', month:'long'}));
  return `<div class="topbar" data-tour="topbar">
    <div>
      <p class="greeting">${greetingLine()}</p>
      <p class="page-title">${activeTab==='home' ? 'Tu ' + PL().noun : TAB_TITLES[activeTab]}</p>
      <p class="today">${todayStr}</p>
    </div>
    <div class="top-actions">
      <div class="top-btns">
        <button class="top-pill" data-tour="btn-help" onclick="openHelp()">Tutorial</button>
        <button class="top-ico" onclick="openHelp()" aria-label="Buscar">${ICONS.search}</button>
        <button class="top-ico" data-tour="btn-settings" onclick="openSettings()" aria-label="Ajustes">${ICONS.gear}</button>
      </div>
      <p class="save-status" id="save-status"></p>
    </div>
  </div>`;
}

function renderTabbar(){
  const tabs = [['home','Inicio'],['mov','Movimientos'],['debts','Deudas'],['import','Subir extracto'],['more','Más']];
  return `<nav class="tabbar">${tabs.map(([k,l]) => `
    <button class="${activeTab===k?'active':''}" data-tour="tab-${k}" onclick="setTab('${k}')"><span class="pill">${ICONS[k]}</span>${l}</button>`).join('')}
  </nav>`;
}

// ---------------------------------------------------------------- INICIO
const SUB_APPS = [
  ['rotador','Rotador','<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-2.6-6.4"/><path d="M21 4v5h-5"/></svg>'],
  ['bot','Bot Señales','<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 17l5-6 4 3 6-8"/><path d="M15 6h3v3"/></svg>'],
  ['notas','Notas','<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0"/><path d="M12 18v3"/></svg>'],
  ['ingles','Inglés','<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5h7"/><path d="M7.5 3v2c0 4-2 7-4.5 8.5"/><path d="M5.5 9c1 2 3 3.5 5 4"/><path d="M12 21l4.5-10 4.5 10"/><path d="M13.5 18h6"/></svg>'],
  ['interes','Interés','<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 20h18"/><path d="M5 16l4-4 3 2 7-8"/><path d="M15 6h4v4"/></svg>'],
  ['guardian','Guardián','<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6z"/><path d="M9 12l2 2 4-4"/></svg>']
];
function renderAppsRow(){
  return `<div class="apps-row" data-tour="apps">${SUB_APPS.map(([k,l,ic]) => `
    <button class="app-tile" onclick="openSubApp('${k}')"><span class="app-ico">${ic}</span><span>${l}</span></button>`).join('')}</div>`;
}

function renderHome(c){
  let html = renderAppsBar();
  html += renderQuestionsCard();
  html += renderLoansDueCard();
  html += renderPaydayCard(c);

  // Avisos de pagos que vencen en 3 días o menos (o ya vencidos esta semana sin pagar)
  const soon = c.upcoming.filter(u => u.days <= 3);
  if(soon.length){
    html += `<div class="card" style="border-color:var(--warn);">
      <div class="card-head" style="margin-bottom:6px;"><p class="card-title" style="font-size:15px;">⚠ Vence pronto</p></div>
      ${soon.map(renderUpcomingRow).join('')}
    </div>`;
  }

  // Tarjeta principal + próximas semanas
  const pct = c.need > 0 ? Math.max(0, Math.min(1, c.availableThisWeek / c.need)) : 1;
  const covered = c.shortfall <= 0;
  const next = c.weeksAhead.slice(1, 4);
  html += `<div class="card hero-card" data-tour="hero">
    <p class="hero-label">${covered ? 'Vas cubierto ' + PL().art : 'Te falta ganar ' + PL().art}</p>
    <p class="hero-number ${covered?'good':'warn'}">${fmtMoney(Math.max(c.shortfall,0))}</p>
    <div class="progress ${covered?'':'warn'}"><span style="width:${(pct*100).toFixed(1)}%"></span></div>
    <p class="hero-sub">Entró ${fmtMoney(c.incomeThisWeek)} · gastaste ${fmtMoney(c.hormigaThisWeek + c.gastoThisWeek)} · disponible ${fmtMoney(c.availableThisWeek)} de una meta de ${fmtMoney(c.need)}</p>
    <div class="hero-split">
      <span class="lbl">${c.realGap > 0 ? 'Sumando tu saldo, aún te faltan' : 'Sumando tu saldo, te sobran'}</span>
      <span class="val" style="color:${c.realGap>0?'var(--warn)':'var(--good)'};">${fmtMoney(Math.abs(c.realGap))}</span>
    </div>
    <p class="sub-head" style="margin:16px 0 8px 0;">Lo que conviene ganar ${PL().noun === 'mes' ? 'los próximos meses' : 'las próximas ' + PL().plural}</p>
    <div class="week-chips" data-tour="weeks">${next.map((w,i) => `
      <button class="week-chip" onclick="sheet={type:'week', idx:${i+1}};render()">
        <span class="wc-range">${fmtShortDate(dayISO(w.weekStart))} – ${fmtShortDate(dayISO(w.weekEnd))}</span>
        <span class="wc-amt">${fmtMoney(w.need)}</span>
        <span class="wc-due">${w.items.filter(o=>o.remaining>0).length} pago${w.items.filter(o=>o.remaining>0).length===1?'':'s'}</span>
      </button>`).join('')}
    </div>
    <p class="item-meta" style="margin-top:8px;">${PL().noun === 'mes' ? 'Cada mes' : 'Cada ' + PL().noun} es el mayor entre tu meta promedio (${fmtMoney(c.weeklyTarget)}) y lo que vence en ${PL().noun === 'mes' ? 'ese mes' : 'esa ' + PL().noun}. Toca uno para ver qué pagos caen ahí.</p>
  </div>`;

  // Mini tarjetas
  const hormigaOver = c.hormigaOver > 0;
  html += `<div class="mini-grid">
    <div class="mini">
      <p class="mini-label">Saldo (lo que tienes)</p>
      <input class="mini-input" type="text" inputmode="decimal" value="${c.currentBalance}" style="color:${c.currentBalance < 0 ? 'var(--warn)' : 'var(--good)'};" onchange="setCurrentBalance(this.value)">
      <p class="mini-foot">Toca para cambiarlo</p>
    </div>
    <div class="mini" onclick="setTab('debts')">
      <p class="mini-label">Deuda total</p>
      <p class="mini-value">${fmtMoney(c.totalDebt)}</p>
      <p class="mini-foot">${data.debts.length} deuda${data.debts.length===1?'':'s'}</p>
    </div>
    <div class="mini">
      <p class="mini-label">Hormiga ${PL().art}</p>
      <p class="mini-value" style="${hormigaOver?'color:var(--warn);':''}">${fmtMoney(c.hormigaThisWeek)}</p>
      <p class="mini-foot">${hormigaOver ? 'Te pasaste por '+fmtMoney(c.hormigaOver) : 'Límite '+fmtMoney(c.hormigaLimit)}</p>
    </div>
    <div class="mini">
      <p class="mini-label">Meta ${PL().adj}</p>
      <p class="mini-value">${fmtMoney(c.weeklyTarget)}</p>
      <p class="mini-foot">${fmtMoney(c.monthlyTotal)} al mes</p>
    </div>
  </div>`;

  html += renderSpendingCard();
  html += renderSourcesCard();
  html += renderTradingCard();
  html += renderWeeklyChartCard();

  // Próximos 30 días
  html += `<div class="card" data-tour="upcoming">
    <div class="card-head"><p class="card-title">Próximos 30 días</p></div>
    ${c.upcoming.length ? c.upcoming.map(renderUpcomingRow).join('') : '<div class="empty-note" style="padding:0;">Nada pendiente en los próximos 30 días.</div>'}
    <p class="item-meta" style="margin-top:10px;">Toca "Mover" para correr un pago unos días. Solo cambia esa vez; el resto se recalcula solo.</p>
  </div>`;

  return html;
}

function renderSpendingCard(){
  const b = monthBounds(0);
  const spend = spendingByCategory(b.s, b.e);
  const total = SPEND_CATS.reduce((a,k) => a + spend[k], 0);
  const max = Math.max(...SPEND_CATS.map(k => spend[k]), 0);
  let html = `<div class="card" data-tour="spend">
    <div class="card-head">
      <div><p class="card-title">En qué se te va la plata</p><p class="card-sub">${b.label}</p></div>
      <button class="card-link" onclick="openHistory()">Meses anteriores ›</button>
    </div>`;
  if(total <= 0){
    html += `<p class="empty-chart">Todavía no hay gastos este mes. Sube tu extracto de TD Bank y aquí verás en qué se te va la plata.</p>
      <button class="btn btn-primary btn-block" onclick="setTab('import')">Subir extracto</button></div>`;
    return html;
  }
  html += SPEND_CATS.map(k => {
    const v = spend[k];
    const w = max > 0 ? (v / max * 100) : 0;
    return `<div class="catbar" title="${BANK_CATS[k].label}: ${fmtMoney(v)}">
      <span class="name">${BANK_CATS[k].label}</span>
      <span class="track"><span class="fill" style="display:block;width:${w.toFixed(1)}%;background:var(--s2);${v<=0?'opacity:0;':''}"></span></span>
      <span class="amt">${fmtMoney(v)}<span class="pct">${total>0?Math.round(v/total*100):0}%</span></span>
    </div>`;
  }).join('');
  html += `<div class="total-line"><span>Total gastado</span><b>${fmtMoney(total)}</b></div>`;
  const tops = topMerchants(b.s, b.e, 5);
  if(tops.length){
    html += `<p class="sub-head">Donde más gastas · toca para clasificar</p>` + tops.map(m => `
      <div class="merchant" style="cursor:pointer;" onclick="openMerchantSheet('out', ${escapeAttr(JSON.stringify(m.key))})"><span class="m-name">${escapeAttr((data.bank.names||{})[m.key] || niceName(m.key))} <span class="item-meta" style="display:inline;">· ${m.count} ${m.count===1?'vez':'veces'}</span></span><span class="m-amt">${fmtMoney(m.total)}</span></div>`).join('');
  }
  html += `<p class="item-meta" style="margin-top:10px;">No incluye trading ni movimientos entre tus cuentas (cajero, transferencias propias).</p>`;
  return html + `</div>`;
}

function renderSourcesCard(){
  const b = monthBounds(0);
  const src = incomeBySource(b.s, b.e);
  if(!src.length) return '';
  const max = src[0].total || 1;
  const total = src.reduce((a,x) => a + x.total, 0);
  return `<div class="card">
    <div class="card-head"><div><p class="card-title">De dónde entra la plata</p><p class="card-sub">${b.label}</p></div></div>
    ${src.slice(0,8).map(x => `
      <div class="catbar" style="cursor:pointer;" onclick="sheet={type:'source', name:${escapeAttr(JSON.stringify(x.name))}};render()">
        <span class="name"><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escapeAttr(x.name)}${x.members.length?` <span class="item-meta" style="display:inline;">+${x.members.length}</span>`:''}</span></span>
        <span class="track"><span class="fill" style="display:block;width:${(x.total/max*100).toFixed(1)}%;background:var(--s3);"></span></span>
        <span class="amt">${fmtMoney(x.total)}</span>
      </div>`).join('')}
    <div class="total-line"><span>Total ingresos</span><b>${fmtMoney(total)}</b></div>
    <p class="item-meta" style="margin-top:8px;">Si una fuente sale repetida con otro nombre, tócala y elige "Es la misma que…". Para renombrar un depósito, ve a Movimientos → Banco y toca ✎.</p>
  </div>`;
}

function barPath(x, y, w, h, r){
  if(h <= 0) return '';
  r = Math.min(r, h, w/2);
  return `M${x},${y+h}V${y+r}Q${x},${y} ${x+r},${y}H${x+w-r}Q${x+w},${y} ${x+w},${y+r}V${y+h}Z`;
}
function renderWeeklyChartCard(){
  const weeks = weeklySeries(8);
  const any = weeks.some(w => w.income > 0 || w.spend > 0);
  let html = `<div class="card">
    <div class="card-head"><div><p class="card-title">Ingresos vs gastos</p><p class="card-sub">Últimas 8 semanas · toca una semana</p></div></div>`;
  if(!any) return html + `<p class="empty-chart">Aún no hay movimientos para comparar.</p></div>`;
  const W = 340, H = 170, L = 34, R = 4, T = 8, B = 26;
  const pw = W - L - R, ph = H - T - B;
  const max = niceMax(Math.max(...weeks.map(w => Math.max(w.income, w.spend))));
  const gw = pw / weeks.length;
  const bw = Math.min(14, (gw - 10) / 2);
  const sel = (chartWeekSel === null || chartWeekSel >= weeks.length) ? weeks.length - 1 : chartWeekSel;
  let svg = `<svg class="chart-svg" viewBox="0 0 ${W} ${H}" role="img" aria-label="Ingresos y gastos por semana">`;
  [0, 0.5, 1].forEach(f => {
    const y = T + ph - f*ph;
    svg += `<line x1="${L}" x2="${W-R}" y1="${y}" y2="${y}" stroke="var(--line)" stroke-width="1"/>`;
    svg += `<text x="${L-6}" y="${y+3}" text-anchor="end">${shortMoney(max*f)}</text>`;
  });
  weeks.forEach((w, i) => {
    const gx = L + i*gw;
    if(i === sel) svg += `<rect x="${gx+1}" y="${T}" width="${gw-2}" height="${ph}" rx="6" fill="rgba(255,255,255,.05)"/>`;
    const cx = gx + gw/2;
    const hi = w.income / max * ph, hs = w.spend / max * ph;
    svg += `<path d="${barPath(cx - bw - 1, T + ph - hi, bw, hi, 4)}" fill="var(--s3)"/>`;
    svg += `<path d="${barPath(cx + 1, T + ph - hs, bw, hs, 4)}" fill="var(--s2)"/>`;
    svg += `<text x="${cx}" y="${H-8}" text-anchor="middle">${w.ws.getDate()}/${w.ws.getMonth()+1}</text>`;
    svg += `<rect x="${gx}" y="0" width="${gw}" height="${H}" fill="transparent" style="cursor:pointer" onclick="selectChartWeek(${i})"/>`;
  });
  svg += `</svg>`;
  const s = weeks[sel];
  const range = `${s.ws.toLocaleDateString('es-ES',{day:'numeric',month:'short'})} – ${s.we.toLocaleDateString('es-ES',{day:'numeric',month:'short'})}`;
  html += `<div class="legend"><span><i class="dot" style="background:var(--s3)"></i>Ingresos</span><span><i class="dot" style="background:var(--s2)"></i>Gastos (hormiga + otros)</span></div>`;
  html += svg;
  html += `<div class="chart-tip"><span>${range}</span><span>Entró <b>${fmtMoney(s.income)}</b> · salió <b>${fmtMoney(s.spend)}</b></span></div>`;
  return html + `</div>`;
}

function openHistory(){
  activeTab = 'more';
  sectionsOpen.history = true;
  render();
  setTimeout(() => { const el = document.getElementById('history-anchor'); if(el) el.scrollIntoView({behavior:'smooth', block:'start'}); }, 50);
}

// ------------------------------------------------- listas por mes (solo el mes actual a la vista)
function monthGroupedList(listKey, items, rowFn, emptyText, amountFn){
  const groups = groupByMonth(items.slice().sort((a,b) => new Date(b.date) - new Date(a.date)), amountFn);
  const cur = currentMonthKey();
  const current = groups.filter(g => g.key === cur);
  const older = groups.filter(g => g.key !== cur);
  let html = '';
  if(current.length){
    const g = current[0];
    html += `<div class="month-header" style="cursor:default;"><span>${g.label}</span><span class="amt">${fmtMoney(g.total)}</span></div>`;
    html += g.items.map(rowFn).join('');
  } else {
    html += `<div class="empty-note" style="padding-top:0;">${emptyText}</div>`;
  }
  if(older.length){
    if(showOlder[listKey]){
      html += older.map(g => {
        const open = isMonthOpen(listKey, g.key, false);
        return `<div class="month-header" onclick="toggleMonth('${listKey}','${g.key}')"><span>${g.label}</span><span class="amt">${fmtMoney(g.total)} <span class="chevron ${open?'open':''}">›</span></span></div>
          ${open ? g.items.map(rowFn).join('') : ''}`;
      }).join('');
      html += `<button class="card-link" style="margin-top:6px;" onclick="toggleOlder('${listKey}')">Ocultar meses anteriores</button>`;
    } else {
      html += `<button class="card-link" style="margin-top:6px;" onclick="toggleOlder('${listKey}')">Ver meses anteriores (${older.length}) ›</button>`;
    }
  }
  return html;
}
function toggleOlder(k){ showOlder[k] = !showOlder[k]; render(); }

// ---------------------------------------------------------- MOVIMIENTOS
function renderMovTab(c){
  const segs = [['incomes','Ingresos'],['hormiga','Hormiga'],['bank','Banco'],['loans','Préstamos']];
  let html = `<div class="seg" data-tour="movseg">${segs.map(([k,l]) => `<button class="${movSeg===k?'active':''}" onclick="setMovSeg('${k}')">${l}</button>`).join('')}</div>`;
  html += `<div class="card">`;
  if(movSeg === 'incomes') html += incomesBody();
  else if(movSeg === 'hormiga') html += hormigaBody(c);
  else if(movSeg === 'bank') html += bankBody();
  else html += loansBody();
  return html + `</div>`;
}

function incomesBody(){
  const editingIncome = editingItem && editingItem.kind==='incomes' ? data.incomes.find(i=>i.id===editingItem.id) : null;
  const showIncomeForm = formOpen['incomes'] || !!editingIncome;
  const row = i => `
      <div class="item-row">
        <div class="item-main clickable" onclick="startEdit('incomes','${i.id}')">
          <div class="item-name">${i.source ? escapeAttr(i.source) : 'Ingreso'}</div>
          <div class="item-meta">${fmtShortDate(i.date)}</div>
        </div>
        <div class="item-amounts"><span class="item-amount in">${fmtMoney(i.amount)}</span></div>
        ${deleteControl('incomes', i.id)}
      </div>`;
  const bankCount = bankByCat('income').filter(t => t.date.slice(0,7) === currentMonthKey()).length;
  return `
    <p class="item-meta" style="margin:0 0 10px 0;">Ingresos anotados a mano.${bankCount ? ` Este mes también hay ${bankCount} depósito${bankCount===1?'':'s'} del banco (pestaña Banco).` : ''}</p>
    ${monthGroupedList('incomes', data.incomes, row, 'Sin ingresos anotados este mes.')}
    ${showIncomeForm ? `
    <div class="add-form" id="edit-anchor-incomes">
      <div class="field-row">
        <div class="field"><label>Monto</label><input type="text" inputmode="decimal" id="inc-amount" placeholder="0.00" value="${editingIncome?editingIncome.amount:''}"></div>
        <div class="field"><label>Fecha</label><input type="date" id="inc-date" value="${editingIncome?editingIncome.date:todayISO()}"></div>
      </div>
      <div class="field"><label>Fuente (opcional)</label><input type="text" id="inc-source" placeholder="Star Snacks, tatuajes, trading…" value="${editingIncome?escapeAttr(editingIncome.source):''}"></div>
      <div class="field-row">
        <button class="btn btn-primary" onclick="submitIncome()">${editingIncome?'Guardar cambios':'Añadir ingreso'}</button>
        <button class="btn" onclick="${editingIncome?'cancelEdit()':"toggleFormOpen('incomes')"}">Cancelar</button>
      </div>
    </div>` : `<button class="add-toggle-btn" id="edit-anchor-incomes" onclick="toggleFormOpen('incomes')">+ Añadir ingreso</button>`}`;
}

function hormigaBody(c){
  const hormigaWarn = c.hormigaOver > 0;
  const editingHormiga = editingItem && editingItem.kind==='hormiga' ? data.hormiga.find(h=>h.id===editingItem.id) : null;
  const row = h => `
      <div class="item-row">
        <div class="item-main clickable" onclick="startEdit('hormiga','${h.id}')">
          <div class="item-name">${h.desc ? escapeAttr(h.desc) : 'Gasto hormiga'}</div>
          <div class="item-meta">${fmtShortDate(h.date)}</div>
        </div>
        <div class="item-amounts"><span class="item-amount">${fmtMoney(h.amount)}</span></div>
        ${deleteControl('hormiga', h.id)}
      </div>`;
  return `
    <div class="item-row list-first" style="padding-top:0;">
      <div class="item-main"><div class="item-meta">Límite ${PL().adj}</div></div>
      <div class="item-amounts"><input type="text" inputmode="decimal" id="hormiga-limit" value="${c.hormigaLimit}" style="width:96px;text-align:right;" onchange="setHormigaLimit(this.value)"></div>
    </div>
    <div class="item-row">
      <div class="item-main"><div class="item-meta">Gastado ${PL().art} (a mano + banco)</div></div>
      <div class="item-amounts"><span class="item-amount" style="${hormigaWarn?'color:var(--warn);':''}">${fmtMoney(c.hormigaThisWeek)}</span></div>
    </div>
    ${hormigaWarn ? `<div class="empty-note" style="color:var(--warn);padding-top:4px;">Te pasaste del límite por ${fmtMoney(c.hormigaOver)} ${PL().art}.</div>` : ''}
    <div style="height:10px;"></div>
    ${monthGroupedList('hormiga', data.hormiga, row, 'Sin gastos hormiga anotados a mano este mes.')}
    ${(formOpen['hormiga'] || !!editingHormiga) ? `
    <div class="add-form" id="edit-anchor-hormiga">
      <div class="field-row">
        <div class="field"><label>Monto</label><input type="text" inputmode="decimal" id="hor-amount" placeholder="0.00" value="${editingHormiga?editingHormiga.amount:''}"></div>
        <div class="field"><label>Fecha</label><input type="date" id="hor-date" value="${editingHormiga?editingHormiga.date:todayISO()}"></div>
      </div>
      <div class="field"><label>Descripción (opcional)</label><input type="text" id="hor-desc" placeholder="Café, uber, antojo…" value="${editingHormiga?escapeAttr(editingHormiga.desc):''}"></div>
      <div class="field-row">
        <button class="btn btn-primary" onclick="submitHormiga()">${editingHormiga?'Guardar cambios':'Añadir gasto hormiga'}</button>
        <button class="btn" onclick="${editingHormiga?'cancelEdit()':"toggleFormOpen('hormiga')"}">Cancelar</button>
      </div>
    </div>` : `<button class="add-toggle-btn" id="edit-anchor-hormiga" onclick="toggleFormOpen('hormiga')">+ Añadir gasto hormiga</button>`}`;
}

function bankBody(){
  const all = bankList();
  if(!all.length){
    return `<p class="empty-chart">Aquí aparecen los movimientos de tus extractos de TD Bank.</p>
      <button class="btn btn-primary btn-block" onclick="setTab('import')">Subir extracto</button>`;
  }
  const filters = [['all','Todos'],['income','Ingresos'],['hormiga','Hormiga'],['gasto','Otros gastos'],['fixed','Fijos'],['debt','Deudas'],['trading','Trading'],['own','Mov. propio'],['ignore','No contar']];
  const list = bankFilter === 'all' ? all : all.filter(t => t.category === bankFilter);
  const row = t => {
    const isIn = t.dir === 'in';
    const editingSrc = editingSourceTx === t.id;
    const name = isIn ? incomeSourceName({bank:true, source:t.desc, name:displayName(t)}) : displayName(t);
    let linkInfo = '';
    if(t.link){
      const target = t.link.type === 'debt' ? data.debts.find(d => d.id === t.link.id) : data.expenses.find(e => e.id === t.link.id);
      linkInfo = target ? `<div class="item-meta note-good" style="cursor:pointer;" onclick="openLinkSheet('${t.id}','${t.link.type}')">↳ ${t.link.type==='debt'?'Abono a':'Pago de'} "${escapeAttr(target.name)}" · cambiar</div>` : '';
    } else if((t.category === 'debt' || t.category === 'fixed') && !isIn){
      linkInfo = `<button class="card-link" style="font-size:12.5px;padding:2px 0 0 0;" onclick="openLinkSheet('${t.id}','${t.category==='debt'?'debt':'expense'}')">＋ Elegir a qué ${t.category==='debt'?'deuda':'gasto fijo'} va</button>`;
    }
    return `<div class="item-row" style="align-items:flex-start;">
      <div class="item-main">
        <div class="item-name">${escapeAttr(name)}</div>
        <div class="item-meta">${fmtShortDate(t.date)} · ${escapeAttr(t.desc)}</div>
        ${t.auto === 'manual' ? `<div class="item-meta note-good">Ya lo tenías anotado a mano — no se cuenta dos veces</div>` : ''}
        ${linkInfo}
        ${editingSrc ? `<div class="field-row" style="margin-top:8px;">
            <input type="text" id="src-input-${t.id}" placeholder="${isIn?'Ej: Star Snacks, Trading':'Ej: Renta, Carlos (deuda)'}" value="${escapeAttr((data.bank.names||{})[merchantKey(t.desc)] || '')}">
            <button class="btn btn-primary" style="padding:8px 12px;" onclick="saveSourceName('${t.id}')">OK</button>
            <button class="btn" style="padding:8px 12px;" onclick="cancelSourceEdit()">✕</button>
          </div>` : ''}
      </div>
      <div class="item-amounts" style="display:flex;flex-direction:column;align-items:flex-end;gap:6px;">
        <span class="item-amount ${isIn?'in':''}">${isIn?'+':'-'}${fmtMoney(t.amount)}</span>
        <select class="cat-select" onchange="setBankCategory('${t.id}', this.value)">${catOptions(t.dir, t.category)}</select>
        ${!editingSrc ? `<button class="card-link" style="font-size:12px;padding:0;" onclick="startSourceEdit('${t.id}')">✎ nombre</button>` : ''}
      </div>
    </div>`;
  };
  return `
    <div class="chips">${filters.map(([k,l]) => `<button class="chip ${bankFilter===k?'active':''}" onclick="setBankFilter('${k}')">${l}</button>`).join('')}</div>
    <p class="item-meta" style="margin:0 0 10px 0;">Cambia la categoría y la app la recuerda para ese comercio. Toca ✎ para ponerle nombre: cuando llegue uno parecido, te pregunta.</p>
    ${monthGroupedList('bank-'+bankFilter, list, row, 'Sin movimientos del banco este mes con este filtro.', t => t.dir==='in' ? t.amount : ((t.category==='ignore'||t.category==='own'||t.category==='trading') ? 0 : -t.amount))}`;
}

function loansBody(){
  const loanGroups = groupByMonth(data.loans.slice().sort((a,b)=>new Date(b.date)-new Date(a.date)));
  const editingLoan = editingItem && editingItem.kind==='loans' ? data.loans.find(l=>l.id===editingItem.id) : null;
  const pending = data.loans.filter(l => !l.returned).reduce((s,l) => s + (l.amount||0) - loanRepaid(l), 0);
  const today = isoToDate(todayISO());
  const dueText = l => {
    if(!l.due || l.returned) return '';
    const d = Math.round((isoToDate(l.due) - today)/86400000);
    return ` · <span style="color:${d<0?'var(--warn)':'var(--ink-dim)'};">${d<0?`vencido hace ${-d} día${d===-1?'':'s'}`:d===0?'te paga hoy':`te paga el ${fmtShortDate(l.due)}`}</span>`;
  };
  return `
    <div class="item-row list-first" style="padding-top:0;">
      <div class="item-main"><div class="item-name">Te deben (sin devolver)</div></div>
      <div class="item-amounts"><span class="item-amount">${fmtMoney(pending)}</span></div>
    </div>
    ${loanGroups.length ? loanGroups.map((g,gi)=>{
      const open = isMonthOpen('loans', g.key, gi===0);
      return `
      <div class="month-header" style="border-top:1px solid var(--line);padding-top:10px;" onclick="toggleMonth('loans','${g.key}')"><span>${g.label}</span><span class="amt">${fmtMoney(g.total)} <span class="chevron ${open?'open':''}">›</span></span></div>
      ${open ? g.items.map(l=>`
      <div class="item-row">
        <div class="item-main clickable" onclick="startEdit('loans','${l.id}')">
          <div class="item-name">${escapeAttr(l.person)}${l.note && !l.fromBank?' · '+escapeAttr(l.note):''}</div>
          <div class="item-meta">${fmtShortDate(l.date)}${l.fromBank?' · del banco':''}${dueText(l)}
            <span class="badge ${l.returned?'returned':''}" onclick="event.stopPropagation(); toggleLoanReturned('${l.id}')">${l.returned?'Devuelto ✓':'Pendiente'}</span>
          </div>
          ${loanRepaid(l) > 0 && !l.returned ? `<div class="item-meta note-good">Te ha devuelto ${fmtMoney(loanRepaid(l))} · faltan ${fmtMoney(l.amount - loanRepaid(l))}</div>` : ''}
          ${!l.due && !l.returned ? `<button class="card-link" style="font-size:12.5px;padding:2px 0 0 0;" onclick="event.stopPropagation(); sheet={type:'loanDue', loanId:'${l.id}'};render()">＋ Poner fecha de pago</button>` : ''}
        </div>
        <div class="item-amounts"><span class="item-amount">${fmtMoney(l.amount)}</span></div>
        ${deleteControl('loans', l.id)}
      </div>`).join('') : ''}`;}).join('') : '<div class="empty-note">Sin préstamos registrados.</div>'}
    ${(formOpen['loans'] || !!editingLoan) ? `
    <div class="add-form" id="edit-anchor-loans">
      <div class="field"><label>¿A quién le prestaste?</label><input type="text" id="loan-person" placeholder="Nombre" value="${editingLoan?escapeAttr(editingLoan.person):''}"></div>
      <div class="field-row">
        <div class="field"><label>Monto</label><input type="text" inputmode="decimal" id="loan-amount" placeholder="0.00" value="${editingLoan?editingLoan.amount:''}"></div>
        <div class="field"><label>Fecha</label><input type="date" id="loan-date" value="${editingLoan?editingLoan.date:todayISO()}"></div>
      </div>
      <div class="field-row">
        <div class="field"><label>¿Hasta cuándo te paga?</label><input type="date" id="loan-due" value="${editingLoan&&editingLoan.due?editingLoan.due:''}"></div>
        <div class="field"><label>Nota (opcional)</label><input type="text" id="loan-note" placeholder="Opcional" value="${editingLoan?escapeAttr(editingLoan.note):''}"></div>
      </div>
      <p class="item-meta">${editingLoan && editingLoan.fromBank ? 'Este préstamo viene del banco: ya salió de tu cuenta.' : 'El monto se descuenta de tu saldo y se devuelve cuando lo marcas como "Devuelto".'} Te aviso 2 días antes y el día que te tiene que pagar.</p>
      <div class="field-row">
        <button class="btn btn-primary" onclick="submitLoan()">${editingLoan?'Guardar cambios':'Añadir préstamo'}</button>
        <button class="btn" onclick="${editingLoan?'cancelEdit()':"toggleFormOpen('loans')"}">Cancelar</button>
      </div>
    </div>` : `<button class="add-toggle-btn" id="edit-anchor-loans" onclick="toggleFormOpen('loans')">+ Añadir préstamo</button>`}
    <p class="item-meta" style="margin-top:10px;">Si le prestaste por Zelle, en Movimientos → Banco ponle la categoría "Préstamo (le presté)" y se crea aquí solo.</p>`;
}

// --------------------------------------------------------------- DEUDAS
function renderDebtsTab(c){
  const rec = detectRecurring();
  const newRec = rec.filter(r => !r.tracked).length;
  let html = `<div class="mini-grid" data-tour="debtsum">
    <div class="mini"><p class="mini-label">Deuda total</p><p class="mini-value" style="color:var(--warn);">${fmtMoney(c.totalDebt)}</p><p class="mini-foot">${data.debts.length} deuda${data.debts.length===1?'':'s'}</p></div>
    <div class="mini"><p class="mini-label">Pagos al mes</p><p class="mini-value">${fmtMoney(c.monthlyTotal)}</p><p class="mini-foot">${fmtMoney(c.weeklyTarget)} ${PL().por}</p></div>
  </div>`;
  const segs = [['debts','Deudas'],['expenses','Gastos fijos'],['recurring','Cobros repetidos' + (newRec ? ` (${newRec})` : '')]];
  html += `<div class="seg" data-tour="debtseg">${segs.map(([k,l]) => `<button class="${debtSeg===k?'active':''}" onclick="setDebtSeg('${k}')">${l}</button>`).join('')}</div>`;
  html += `<div class="card">`;
  if(debtSeg === 'debts') html += debtsBody();
  else if(debtSeg === 'expenses') html += expensesBody();
  else html += recurringBody(rec);
  return html + `</div>`;
}

let debtOpen = {};
function toggleDebtOpen(id){ debtOpen[id] = !debtOpen[id]; render(); }
function debtsBody(){
  const editingDebt = editingItem && editingItem.kind==='debts' ? data.debts.find(d=>d.id===editingItem.id) : null;
  const row = (d, i) => {
    const pays = debtPayments(d.id);
    const paid = pays.reduce((s,p)=>s+p.amount,0);
    const open = !!debtOpen[d.id];
    const done = (d.balance||0) <= 0.004;
    return `<div class="item-row ${i===0?'list-first':''}" style="align-items:flex-start;">
        <div class="item-main clickable" onclick="startEdit('debts','${d.id}')">
          <div class="item-name">${escapeAttr(d.name)}</div>
          <div class="item-meta">Debes ${fmtMoney(Math.max(0,d.balance||0))}${d.rate?` · ${d.rate}% interés`:''}${d.dueDate?` · vence día ${isoToDate(d.dueDate).getDate()}`:''}</div>
          ${paid > 0 ? `<div class="item-meta note-good">Pagado en la app: ${fmtMoney(paid)} (${pays.length} pago${pays.length===1?'':'s'})</div>` : ''}
        </div>
        <div class="item-amounts">
          <div class="item-amount">${fmtMoney(d.monthlyPayment)}/mes</div>
          <div class="item-weekly">${fmtMoney((d.monthlyPayment||0)/4.345)}/sem</div>
        </div>
        ${deleteControl('debts', d.id)}
      </div>
      ${done ? `<div class="done-box">¡Esta deuda llegó a cero! <button class="btn btn-solid" style="padding:7px 12px;" onclick="confirmPaidDebt('${d.id}')">Marcar saldada ✓</button></div>` : ''}
      <div style="display:flex;gap:16px;padding:0 0 10px 0;">
        <button class="card-link" style="font-size:12.5px;padding:0;" onclick="toggleDebtOpen('${d.id}')">${open?'Ocultar detalle':'Pagos y pagos programados ›'}</button>
        <button class="card-link" style="font-size:12.5px;padding:0;color:var(--ink-dim);" onclick="sheet={type:'mergeInto', ref:'d:${d.id}'};render()">Unir con…</button>
      </div>
      ${open ? `<div class="debt-detail">
        <p class="sub-head" style="margin-top:0;">Pagos registrados</p>
        ${pays.length ? pays.map(p => `<div class="mini-row"><span>${fmtShortDate(p.date)} · ${escapeAttr(p.label)}</span><span style="display:flex;gap:8px;align-items:center;"><b>${fmtMoney(p.amount)}</b>${p.kind==='alloc'?`<button class="item-del" style="font-size:16px;" onclick="removeAllocation('${p.id}')">×</button>`:''}</span></div>`).join('')
          : '<p class="item-meta">Todavía no hay pagos conectados. En Movimientos → Banco, marca un pago como "Pago de deuda" y elige esta deuda.</p>'}
        <p class="sub-head">Pagos programados</p>
        ${(d.schedule||[]).map(sc => `<div class="mini-row"><span>${fmtShortDate(sc.date)}</span><span style="display:flex;gap:8px;align-items:center;"><b>${fmtMoney(sc.amount)}</b><button class="item-del" style="font-size:16px;" onclick="removeScheduledPayment('${d.id}','${sc.id}')">×</button></span></div>`).join('') || '<p class="item-meta">Ninguno. Úsalo para pagos únicos con fecha, como los de una orden de la corte.</p>'}
        <div class="field-row" style="margin-top:8px;">
          <input type="date" id="sch-date-${d.id}">
          <input type="text" inputmode="decimal" id="sch-amt-${d.id}" placeholder="Monto" style="max-width:110px;">
          <button class="btn btn-primary" style="padding:8px 12px;" onclick="addScheduledPayment('${d.id}')">＋</button>
        </div>
      </div>` : ''}`;
  };
  return `
    ${data.debts.length ? data.debts.map(row).join('') : '<div class="empty-note" style="padding-top:0;">Sin deudas registradas.</div>'}
    ${(formOpen['debts'] || !!editingDebt) ? `
    <div class="add-form" id="edit-anchor-debts">
      <div class="field"><label>Nombre</label><input type="text" id="debt-name" placeholder="Ej: Child Support (atrasos)" value="${editingDebt?escapeAttr(editingDebt.name):''}"></div>
      <div class="field-row">
        <div class="field"><label>Saldo que debes hoy</label><input type="text" inputmode="decimal" id="debt-balance" placeholder="0.00" value="${editingDebt?editingDebt.balance:''}"></div>
        <div class="field"><label>Interés % (opcional)</label><input type="text" inputmode="decimal" id="debt-rate" placeholder="0" value="${editingDebt&&editingDebt.rate?editingDebt.rate:''}"></div>
      </div>
      <div class="field-row">
        <div class="field"><label>Pago mensual (0 si no hay)</label><input type="text" inputmode="decimal" id="debt-payment" placeholder="0.00" value="${editingDebt?editingDebt.monthlyPayment:''}"></div>
        <div class="field"><label>Próx. vencimiento</label><input type="date" id="debt-due" value="${editingDebt&&editingDebt.dueDate?editingDebt.dueDate:''}"></div>
      </div>
      <p class="item-meta">Los pagos que conectes desde el banco se restan solos del saldo.</p>
      <div class="field-row">
        <button class="btn btn-primary" onclick="submitDebt()">${editingDebt?'Guardar cambios':'Añadir deuda'}</button>
        <button class="btn" onclick="${editingDebt?'cancelEdit()':"toggleFormOpen('debts')"}">Cancelar</button>
      </div>
    </div>` : `<button class="add-toggle-btn" id="edit-anchor-debts" onclick="toggleFormOpen('debts')">+ Añadir deuda</button>`}`;
}

function expensesBody(){
  const editingExpense = editingItem && editingItem.kind==='expenses' ? data.expenses.find(e=>e.id===editingItem.id) : null;
  const today = new Date(); today.setHours(0,0,0,0);
  const row = (e, i) => {
    const per = expenseFreq(e) === 'week' ? weekPeriod(today) : monthPeriod(today);
    const occ = occurrencesInRange(per.s, per.e).find(o => o.refId === e.id && o.kind === 'expense');
    const paid = occ ? occ.paid : 0, over = occ ? occ.overpaid : 0;
    const left = occ ? occ.remaining : (e.amount||0);
    const perName = expenseFreq(e) === 'week' ? 'Esta semana' : 'Este mes';
    return `<div class="item-row ${i===0?'list-first':''}" style="align-items:flex-start;">
        <div class="item-main clickable" onclick="startEdit('expenses','${e.id}')">
          <div class="item-name">${escapeAttr(e.name)}</div>
          <div class="item-meta">${expenseWhen(e)} · <span class="link-inline" onclick="event.stopPropagation(); sheet={type:'mergeInto', ref:'e:${e.id}'};render()">Unir con…</span></div>
          <div class="item-meta" style="color:${left<=0.004?'var(--good)':'var(--ink-dim)'};">${perName}: pagado ${fmtMoney(paid)} de ${fmtMoney(e.amount||0)}${left>0.004?` · faltan ${fmtMoney(left)}`:' ✓'}</div>
          ${over > 0.004 ? `<button class="card-link" style="font-size:12.5px;padding:4px 0 0 0;" onclick="event.stopPropagation(); sheet={type:'alloc', expenseId:'${e.id}', periodDate:'${dayISO(occ.baseDate)}', amount:${over}};render()">Pagaste ${fmtMoney(over)} de más → Agregar a deuda</button>` : ''}
        </div>
        <div class="item-amounts">
          <div class="item-amount">${fmtMoney(e.amount)}/${expenseFreq(e)==='week'?'sem':'mes'}</div>
          <div class="item-weekly">${expenseFreq(e)==='week' ? fmtMoney(expenseMonthly(e))+'/mes' : fmtMoney((e.amount||0)/4.345)+'/sem'}</div>
        </div>
        ${deleteControl('expenses', e.id)}
      </div>`;
  };
  const ef = editingExpense ? expenseFreq(editingExpense) : 'month';
  return `
    ${data.expenses.length ? data.expenses.map(row).join('') : '<div class="empty-note" style="padding-top:0;">Sin gastos fijos registrados.</div>'}
    ${(formOpen['expenses'] || !!editingExpense) ? `
    <div class="add-form" id="edit-anchor-expenses">
      <div class="field"><label>Nombre</label><input type="text" id="exp-name" placeholder="Ej: Ethan, renta, T-Mobile" value="${editingExpense?escapeAttr(editingExpense.name):''}"></div>
      <div class="field-row">
        <div class="field"><label>Monto</label><input type="text" inputmode="decimal" id="exp-amount" placeholder="0.00" value="${editingExpense?editingExpense.amount:''}"></div>
        <div class="field"><label>¿Cada cuánto?</label>
          <select id="exp-freq" onchange="document.getElementById('exp-day-wrap').style.display=this.value==='week'?'none':'flex';document.getElementById('exp-wd-wrap').style.display=this.value==='week'?'flex':'none';">
            <option value="month" ${ef==='month'?'selected':''}>Cada mes</option>
            <option value="week" ${ef==='week'?'selected':''}>Cada semana</option>
          </select></div>
      </div>
      <div class="field" id="exp-day-wrap" style="display:${ef==='week'?'none':'flex'};"><label>Día del mes que vence</label><input type="number" id="exp-day" min="1" max="31" placeholder="1-31" value="${editingExpense?editingExpense.dueDay:''}"></div>
      <div class="field" id="exp-wd-wrap" style="display:${ef==='week'?'flex':'none'};"><label>Día de la semana</label>
        <select id="exp-weekday">${WEEKDAYS.map((w,k) => `<option value="${k}" ${(editingExpense ? (editingExpense.weekday ?? 5) : 5)===k?'selected':''}>${capFirst(w)}</option>`).join('')}</select></div>
      <div class="field-row">
        <button class="btn btn-primary" onclick="submitExpense()">${editingExpense?'Guardar cambios':'Añadir gasto'}</button>
        <button class="btn" onclick="${editingExpense?'cancelEdit()':"toggleFormOpen('expenses')"}">Cancelar</button>
      </div>
    </div>` : `<button class="add-toggle-btn" id="edit-anchor-expenses" onclick="toggleFormOpen('expenses')">+ Añadir gasto fijo</button>`}
    <p class="item-meta" style="margin-top:12px;">Los pagos del banco que marques como "Gasto fijo" se conectan aquí. La app te avisa 2 días antes y el mismo día de cada vencimiento.</p>`;
}

function recurringBody(rec){
  if(!bankList().length) return `<p class="empty-chart">Cuando subas extractos de al menos 2 meses, aquí aparecen solos los cobros que se repiten cada mes (suscripciones, seguros, planes de celular…).</p>
    <button class="btn btn-primary btn-block" onclick="setTab('import')">Subir extractos</button>`;
  if(!rec.length) return `<p class="empty-chart">Todavía no detecto cobros que se repitan. Necesito ver el mismo cobro en al menos 2 meses distintos.</p>`;
  const yearly = rec.filter(r => !r.tracked).reduce((a,r) => a + r.amount*12, 0);
  return `
    <p class="item-meta" style="margin:0 0 10px 0;">Cobros que aparecen todos los meses en tu banco.${yearly ? ` Los que no tienes anotados suman unos <b style="color:var(--ink);">${fmtMoney(yearly)}</b> al año — revisa si alguno se puede cancelar.` : ''}</p>
    ${rec.map((r,i) => `
      <div class="item-row ${i===0?'list-first':''}">
        <div class="item-main">
          <div class="item-name">${escapeAttr(r.name)}</div>
          <div class="item-meta">Cerca del día ${r.day} · visto en ${r.months} meses · último ${fmtShortDate(r.last)}</div>
          ${r.tracked ? `<div class="item-meta note-good">Ya está en tus gastos fijos o deudas</div>` : `<button class="card-link" style="font-size:12.5px;padding:4px 0 0 0;" onclick="addRecurringAsExpense('${escapeAttr(r.key)}')">+ Agregar como gasto fijo</button>`}
        </div>
        <div class="item-amounts"><div class="item-amount">${fmtMoney(r.amount)}</div><div class="item-weekly">al mes</div></div>
      </div>`).join('')}`;
}

// ------------------------------------------------------- SUBIR EXTRACTO
function renderImportTab(){
  if(importPreview) return renderImportPreview();
  let html = `<div class="card">
    ${importBusy ? `<div class="upload-zone" style="cursor:default;">
      <span class="ico">${ICONS.import}</span>
      <span class="big">Leyendo extractos…</span>
      <span class="small">Esto tarda unos segundos con los PDF</span>
    </div>` : `<label class="upload-zone" for="statement-input" data-tour="upload">
      <span class="ico">${ICONS.import}</span>
      <span class="big">Elegir extractos (PDF o CSV)</span>
      <span class="small">Puedes elegir varios a la vez, por ejemplo los últimos 3 meses</span>
    </label>`}
    <input type="file" id="statement-input" accept=".pdf,application/pdf,.csv,text/csv,text/comma-separated-values,text/plain,application/vnd.ms-excel" multiple style="display:none" onchange="handleStatementFiles(this)">
    ${shotBusy ? `<div class="upload-zone" style="cursor:default;margin-top:10px;">
      <span class="ico">${ICONS.camera}</span><span class="big">Leyendo la captura…</span><span class="small">Unos segundos</span></div>`
    : `<label class="upload-zone shot-zone" for="shot-input" style="margin-top:10px;" data-tour="shot">
      <span class="ico">${ICONS.camera}</span>
      <span style="display:flex;flex-direction:column;gap:3px;"><span class="big">Subir captura de pantalla</span>
      <span class="small">De los movimientos recientes en la app del banco. Puedes elegir varias.</span></span>
    </label>`}
    <input type="file" id="shot-input" accept="image/*" multiple style="display:none" onchange="handleShotFiles(this)">
    ${importError ? `<div class="error-box">${escapeAttr(importError)}</div>` : ''}
  </div>
  <div class="card">
    <div class="card-head"><p class="card-title">Cómo sacar el extracto de TD Bank</p></div>
    <ol class="steps">
      <li>Entra a <b>tdbank.com</b> desde el navegador (Chrome) e inicia sesión.</li>
      <li>Abre tu cuenta y entra a <b>Statements / Estados de cuenta</b>.</li>
      <li>Descarga el PDF de cada mes que quieras (por ejemplo los últimos 3).</li>
      <li>Vuelve aquí, toca <b>Elegir extractos</b> y selecciónalos todos juntos.</li>
    </ol>
    <p class="item-meta" style="margin-top:10px;">También sirve el archivo CSV de "Descargar movimientos" si alguna vez lo prefieres: trae los movimientos más recientes, incluso los de esta semana.</p>
    <p class="item-meta" style="margin-top:10px;"><b style="color:var(--ink);">¿El extracto no trae lo de esta semana?</b> Toma captura de pantalla de los movimientos en la app de TD y súbela con <b>Subir captura</b>. Cuando llegue el extracto con esos movimientos, la app los reconoce y no los cuenta dos veces.</p>
    <p class="item-meta" style="margin-top:10px;">No importa si subes fechas que ya habías subido: los movimientos repetidos se detectan y no se cuentan dos veces. Tampoco los que ya anotaste a mano.</p>
  </div>`;

  const imps = (data.bank.imports || []).slice().reverse();
  html += `<div class="card">
    <div class="card-head"><p class="card-title">Extractos subidos</p><span class="section-count">${imps.length}</span></div>
    ${imps.length ? imps.map((imp,i) => `
      <div class="item-row ${i===0?'list-first':''}">
        <div class="item-main">
          <div class="item-name">${fmtShortDate(imp.from)} – ${fmtShortDate(imp.to)}</div>
          <div class="item-meta">${imp.shot ? '📷 Captura · ' : ''}Subido el ${fmtShortDate(imp.date)} · ${imp.added} nuevos${imp.dup?` · ${imp.dup} repetidos`:''}${imp.files>1?` · ${imp.files} archivos`:''}</div>
        </div>
        ${confirmingDeleteId === imp.id ? `<span class="del-confirm">
            <button class="item-del-yes" onclick="undoImport('${imp.id}')">Sí, deshacer</button>
            <button class="item-del-no" onclick="cancelDelete()">No</button></span>`
          : `<button class="btn" style="padding:7px 11px;font-size:12px;" onclick="requestDelete('${imp.id}')">Deshacer</button>`}
      </div>`).join('') : '<div class="empty-note" style="padding:0;">Todavía no has subido ninguno.</div>'}
  </div>`;

  const rules = data.bank.rules || [];
  if(rules.length){
    html += `<div class="card">
      <div class="card-head"><p class="card-title">Reglas automáticas</p><span class="section-count">${rules.length}</span></div>
      <p class="item-meta" style="margin:-4px 0 8px 0;">Cuando cambias la categoría de un comercio, la app la recuerda aquí.</p>
      ${rules.map((r,i) => `
        <div class="item-row ${i===0?'list-first':''}">
          <div class="item-main"><div class="item-name">${escapeAttr(niceName(r.key))}</div><div class="item-meta">${r.dir==='in'?'Entradas':'Salidas'} → ${BANK_CATS[r.category]?BANK_CATS[r.category].label:r.category}</div></div>
          <button class="item-del" onclick="removeRule('${escapeAttr(r.key)}','${r.dir}')">×</button>
        </div>`).join('')}
    </div>`;
  }
  return html;
}

function renderImportPreview(){
  const p = importPreview;
  const counts = {};
  p.fresh.forEach(t => { counts[t.category] = (counts[t.category]||0) + 1; });
  let html = `<div class="card">
    <div class="card-head"><div><p class="card-title">Revisa antes de guardar</p><p class="card-sub">${p.shot ? (p.fileCount===1 ? '1 captura' : p.fileCount + ' capturas') : p.fileCount + ' archivo' + (p.fileCount===1?'':'s')} · ${fmtShortDate(p.from)} – ${fmtShortDate(p.to)}</p></div></div>
    <div class="sum-grid">
      <div class="sum-box"><div class="n" style="color:var(--good);">${p.fresh.length - p.manualDup}</div><div class="t">nuevos</div></div>
      <div class="sum-box"><div class="n">${p.dup}</div><div class="t">${p.shot ? 'ya estaban (se saltan)' : 'repetidos (se saltan)'}</div></div>
      <div class="sum-box"><div class="n">${p.manualDup}</div><div class="t">ya anotados a mano</div></div>
    </div>
    ${p.shot ? `<p class="item-meta" style="margin:0 0 10px 0;">Revisa que la app haya leído bien cada movimiento: toca <b>Corregir</b> para cambiar fecha, nombre o monto, o <b>Quitar</b> si sobra.</p>` : ''}
    ${p.fromShots ? `<p class="item-meta note-good" style="margin:0 0 10px 0;">${p.fromShots} movimiento${p.fromShots===1?'':'s'} de tus capturas aparece${p.fromShots===1?'':'n'} en este extracto: se confirman y no se duplican.</p>` : ''}
    ${p.warnings && p.warnings.length ? `<div class="error-box" style="margin-top:0;margin-bottom:10px;">${p.warnings.map(escapeAttr).join('<br>')}</div>` : ''}
    ${p.lastBalance !== null ? `<label class="check-row"><input type="checkbox" ${p.updateBalance?'checked':''} onchange="toggleUpdateBalance(this.checked)"><span>Actualizar mi saldo a <b style="color:var(--ink);">${fmtMoney(p.lastBalance)}</b> (saldo del banco al ${fmtShortDate(p.lastBalanceDate)})</span></label>` : ''}
    <div class="btn-row" style="margin-top:6px;">
      <button class="btn btn-solid" style="flex:1;" onclick="confirmImport()">${p.fresh.length ? `Guardar ${p.fresh.length} movimiento${p.fresh.length===1?'':'s'}` : 'Listo'}</button>
      <button class="btn" onclick="cancelImport()">Cancelar</button>
    </div>
  </div>`;
  if(!p.fresh.length){
    return html + `<div class="card"><p class="empty-chart">${p.shot ? 'Todos los movimientos de la captura ya estaban guardados. No hay nada nuevo.' : `Todos los movimientos de ${p.fileCount===1?'este extracto':'estos extractos'} ya estaban guardados. No hay nada nuevo.`}</p>${p.shot ? `<button class="btn" style="margin-top:10px;" onclick="addShotRow()">+ Agregar uno a mano</button>` : ''}</div>`;
  }
  html += `<div class="card">
    <div class="card-head"><p class="card-title">Movimientos nuevos</p>${p.shot ? `<button class="card-link" onclick="addShotRow()">+ Agregar</button>` : ''}</div>
    <p class="item-meta" style="margin:-4px 0 8px 0;">La app ya les puso categoría. Si alguna está mal, cámbiala aquí: los demás del mismo comercio se cambian solos.${p.fresh.some(t=>t.fromPdf) ? ` Si un movimiento ${p.shot ? 'de la captura' : 'del PDF'} quedó como entrada y era salida (o al revés), toca ⇄.` : ''}</p>
    ${p.fresh.map((t,i) => `
      <div class="item-row ${i===0?'list-first':''}" style="align-items:flex-start;">
        <div class="item-main">
          <div class="item-name">${escapeAttr(t.dir==='in' ? incomeSourceName({bank:true, source:t.desc}) : niceName(merchantKey(t.desc)))}</div>
          <div class="item-meta">${fmtShortDate(t.date)} · ${escapeAttr(t.desc)}</div>
          ${t.auto==='manual' ? `<div class="item-meta note-good">Ya lo tenías anotado a mano — no se cuenta dos veces</div>` : ''}
          ${p.shot ? (shotEditIdx === i ? `<div class="shot-edit">
              <div class="field-row"><div class="field"><label>Fecha</label><input type="date" id="se-date" value="${t.date}"></div>
              <div class="field"><label>Monto</label><input type="number" id="se-amt" inputmode="decimal" step="0.01" value="${t.amount || ''}"></div></div>
              <div class="field"><label>Nombre</label><input type="text" id="se-desc" value="${escapeAttr(t.desc)}"></div>
              <div class="btn-row" style="margin-top:8px;"><button class="btn btn-solid" style="flex:1;" onclick="saveShotRow(${i})">Guardar</button><button class="btn" onclick="editShotRow(${i})">Cancelar</button></div>
            </div>` : `<div style="display:flex;gap:14px;margin-top:6px;"><button class="card-link" style="font-size:12px;padding:0;" onclick="editShotRow(${i})">Corregir</button><button class="card-link" style="font-size:12px;padding:0;color:var(--warn);" onclick="removeShotRow(${i})">Quitar</button></div>`) : ''}
        </div>
        <div class="item-amounts" style="display:flex;flex-direction:column;align-items:flex-end;gap:6px;">
          <span class="item-amount ${t.dir==='in'?'in':''}">${t.dir==='in'?'+':'-'}${fmtMoney(t.amount)}</span>
          <select class="cat-select" onchange="setPreviewCategory(${i}, this.value)">${catOptions(t.dir, t.category)}</select>
          ${t.fromPdf ? `<button class="card-link" style="font-size:11.5px;padding:0;" onclick="flipPreviewDir(${i})">⇄ ${t.dir==='in'?'es salida':'es entrada'}</button>` : ''}
        </div>
      </div>`).join('')}
  </div>`;
  return html;
}

// ------------------------------------------------------------------ MÁS
function renderMoreTab(c){
  let html = '';

  html += renderSection('savings', 'Ahorro', '', `
    <div class="tab-row">
      <button class="tab-btn ${savingsTab==='emergency'?'active':''}" onclick="setSavingsTab('emergency')">Fondo de emergencia</button>
      <button class="tab-btn ${savingsTab==='investments'?'active':''}" onclick="setSavingsTab('investments')">Inversiones</button>
    </div>
    ${renderSavingsTab(savingsTab)}`);

  // Estadísticas
  const pastWeeksForStats = pastWeeksHistory(12);
  const allWeeksForAvg = [{income:c.incomeThisWeek, hormiga:c.hormigaThisWeek}, ...pastWeeksForStats.map(w=>({income:w.income, hormiga:w.hormiga}))];
  const avgIncome = allWeeksForAvg.reduce((s,w)=>s+w.income,0)/allWeeksForAvg.length;
  const avgHormiga = allWeeksForAvg.reduce((s,w)=>s+w.hormiga,0)/allWeeksForAvg.length;
  const totalPaidDebts = (data.history.paidDebts||[]).reduce((s,p)=>s+(p.balance||0),0);
  const totalLoansOut = data.loans.filter(l=>!l.returned).reduce((s,l)=>s+(l.amount||0),0);
  const totalSavings = savingsTotal('emergency') + savingsTotal('investments');
  const statRow = (name, val, color, meta) => `<div class="item-row"><div class="item-main"><div class="item-name">${name}</div>${meta?`<div class="item-meta">${meta}</div>`:''}</div><div class="item-amounts"><span class="item-amount" style="${color?'color:'+color+';':''}">${val}</span></div></div>`;
  html += renderSection('stats', 'Estadísticas', '', `<div class="stat-list">
    ${statRow('Ingreso ' + PL().adj + ' promedio', fmtMoney(avgIncome), '', `Últimas ${allWeeksForAvg.length} ${PL().plural} con datos`)}
    ${statRow('Gasto hormiga ' + PL().adj + ' promedio', fmtMoney(avgHormiga))}
    ${statRow('Deuda saldada hasta ahora', fmtMoney(totalPaidDebts), 'var(--good)')}
    ${statRow('Deuda pendiente', fmtMoney(c.totalDebt), 'var(--warn)')}
    ${statRow('Prestado sin devolver', fmtMoney(totalLoansOut))}
    ${statRow('Ahorro total (fondo + inversiones)', fmtMoney(totalSavings), 'var(--good)')}
  </div>`);

  // Resumen por rango
  const rangeSummary = computeRangeSummary();
  const rangeCount = rangeSummary ? (rangeSummary.incomes.length + rangeSummary.hormiga.length + rangeSummary.gastos.length + rangeSummary.loans.length + rangeSummary.dueItems.length) : '';
  html += renderSection('range', 'Resumen por rango de fechas', rangeCount, `
    <div class="field-row" style="padding-top:4px;margin-bottom:8px;">
      <div class="field"><label>Desde</label><input type="date" value="${rangeStart||''}" onchange="setRangeStart(this.value)"></div>
      <div class="field"><label>Hasta</label><input type="date" value="${rangeEnd||''}" onchange="setRangeEnd(this.value)"></div>
    </div>
    ${renderRangeSummary(rangeSummary)}`);

  // Historial: meses anteriores + semanas + deudas saldadas
  const cur = currentMonthKey();
  const months = monthKeysWithData().filter(mk => mk !== cur);
  const pastWeeks = pastWeeksHistory(12);
  const paidDebts = (data.history && data.history.paidDebts) || [];
  const fmtRange = (s,e) => `${s.toLocaleDateString('es-ES',{day:'numeric',month:'short'})} – ${e.toLocaleDateString('es-ES',{day:'numeric',month:'short'})}`;
  html += `<div id="history-anchor"></div>` + renderSection('history', 'Historial', months.length + pastWeeks.length + paidDebts.length, `
    <p class="sub-head" style="margin-top:0;">Meses anteriores</p>
    ${months.length ? months.map(mk => {
      const ms = monthSummary(mk);
      const open = isMonthOpen('hist-months', mk, false);
      return `<div class="month-header" style="border-top:1px solid var(--line);padding-top:10px;" onclick="toggleMonth('hist-months','${mk}')">
          <span>${ms.label}</span>
          <span class="amt" style="color:${ms.net<0?'var(--warn)':'var(--good)'};">${fmtMoney(ms.net)} <span class="chevron ${open?'open':''}">›</span></span>
        </div>
        ${open ? `
          <div class="item-row" style="border-top:none;"><div class="item-main"><div class="item-name">Ingresos</div></div><span class="item-amount in">${fmtMoney(ms.income)}</span></div>
          ${ms.sources.map(s => `<div class="item-row" style="padding:6px 0 6px 12px;"><div class="item-main"><div class="item-meta" style="margin:0;">${escapeAttr(s.name)}</div></div><span class="item-meta" style="margin:0;font-family:var(--mono);">${fmtMoney(s.total)}</span></div>`).join('')}
          <div class="item-row"><div class="item-main"><div class="item-name">Gastos</div></div><span class="item-amount">${fmtMoney(ms.totalOut)}</span></div>
          ${SPEND_CATS.filter(k => ms.spend[k] > 0).map(k => `<div class="item-row" style="padding:6px 0 6px 12px;"><div class="item-main"><div class="item-meta" style="margin:0;">${BANK_CATS[k].label}</div></div><span class="item-meta" style="margin:0;font-family:var(--mono);">${fmtMoney(ms.spend[k])}</span></div>`).join('')}
          ${ms.merchants.length ? `<p class="item-meta" style="margin:8px 0 10px 0;">Donde más gastaste: ${ms.merchants.map(m => escapeAttr(niceName(m.key)) + ' ' + fmtMoney(m.total)).join(' · ')}</p>` : ''}
          ${ms.trading && ms.trading.any ? `<p class="item-meta" style="margin:0 0 10px 0;">Trading (aparte): depositaste ${fmtMoney(ms.trading.dep)}, retiraste ${fmtMoney(ms.trading.wd)} → <b style="color:${ms.trading.net<0?'var(--warn)':'var(--good)'};">${ms.trading.net<0?'-':'+'}${fmtMoney(Math.abs(ms.trading.net))}</b></p>` : ''}
        ` : ''}`;
    }).join('') : '<div class="empty-note" style="padding-top:0;">Aquí se van guardando los meses que terminan.</div>'}
    <p class="sub-head">${capFirst(PL().plural)} anteriores</p>
    ${pastWeeks.length ? pastWeeks.map(w=>`
      <div class="item-row">
        <div class="item-main">
          <div class="item-name">${fmtRange(w.weekStart, w.weekEnd)}</div>
          <div class="item-meta">Entró ${fmtMoney(w.income)} · hormiga ${fmtMoney(w.hormiga)}${w.gasto?` · otros ${fmtMoney(w.gasto)}`:''}</div>
        </div>
        <div class="item-amounts"><span class="item-amount" style="${w.net<0?'color:var(--warn);':'color:var(--good);'}">${fmtMoney(w.net)}</span></div>
      </div>`).join('') : `<div class="empty-note" style="padding-top:0;">Sin ${PL().plural} anteriores registrad${PL().noun === 'mes' ? 'os' : 'as'} todavía.</div>`}
    <p class="sub-head">Deudas saldadas</p>
    ${paidDebts.length ? paidDebts.slice().sort((a,b)=>new Date(b.date)-new Date(a.date)).map(p=>`
      <div class="item-row">
        <div class="item-main">
          <div class="item-name">${escapeAttr(p.name)} ✓</div>
          <div class="item-meta">Saldada el ${new Date(p.date+'T00:00:00').toLocaleDateString('es-ES',{day:'numeric',month:'short',year:'numeric'})}</div>
        </div>
        <div class="item-amounts"><span class="item-amount" style="color:var(--good);">${fmtMoney(p.balance)}</span></div>
      </div>`).join('') : '<div class="empty-note" style="padding-top:0;">Ninguna deuda saldada todavía.</div>'}`);

  // Mis apps
  html += `<div class="card">
    <div class="card-head"><div><p class="card-title">Mis apps</p><p class="card-sub">Todo en un solo lugar</p></div></div>
    ${renderAppsRow()}
    <p class="item-meta" style="margin-top:4px;line-height:1.6;">Las ventanas flotantes del Rotador y del Bot se activan desde dentro de cada una, igual que antes. También puedes poner el widget "Control Financiero · Semana" en tu pantalla de inicio.</p>
  </div>`;

  // Exportar resumen
  html += `<div class="card" data-tour="export">
    <div class="card-head"><div><p class="card-title">Exportar resumen para Claude</p><p class="card-sub">Últimos 3 meses + todos los movimientos</p></div></div>
    <p class="item-meta" style="margin:0 0 12px 0;line-height:1.6;">Genera un archivo con tu situación, ingresos por fuente, gastos por categoría, cobros repetidos y movimientos. Súbeselo a Claude en el chat para que lo analice y te diga cómo mejorar.</p>
    <button class="btn btn-solid btn-block" onclick="exportSummary()">Exportar resumen</button>
  </div>`;

  // Ajustes y ayuda
  html += `<div class="card">
    <div class="card-head"><div><p class="card-title">Ajustes y ayuda</p><p class="card-sub">Nombre, colores, letra, pagos, moneda, tutorial</p></div></div>
    <div class="btn-row"><button class="btn btn-solid" style="flex:1;" onclick="openSettings()">⚙ Ajustes</button><button class="btn" style="flex:1;" onclick="openHelp()">❓ Ayuda</button></div>
  </div>`;
  html += renderFxCard();

  // Respaldo
  html += `<div class="card" data-tour="backup">
    <div class="card-head"><p class="card-title">Respaldo de tus datos</p></div>
    <div class="backup-row">
      <button class="btn backup-btn" onclick="toggleBackupBox()">📋 Copiar respaldo</button>
      <button class="btn backup-btn" onclick="downloadBackup()">⬇ Guardar archivo</button>
      <button class="btn backup-btn" onclick="toggleRestoreBox()">⬆ Restaurar</button>
    </div>
    ${renderBackupBox()}
    ${renderRestoreDialogs()}
  </div>`;

  html += `<p class="footnote">Tus datos se guardan solo en este teléfono (no dependen de internet). La meta ${PL().adj} reparte cada pago mensual entre los periodos de pago del mes. "Te falta" usa el mayor entre esa meta y lo que vence en ${PL().art}, para que nunca te agarre corto. Los gastos hormiga y los otros gastos del banco se restan de lo que entra antes de calcular lo que falta. Los pagos de gastos fijos y deudas no se restan otra vez, porque ya están en la meta.</p>`;
  return html;
}

function renderUpcomingRow(u){
  const when = u.days < 0 ? `venció hace ${-u.days} día${u.days===-1?'':'s'}` : u.days===0 ? 'hoy' : u.days===1 ? 'mañana' : 'en '+u.days+' días';
  const uj = escapeAttr(JSON.stringify({ key:u.key, kind:u.kind, refId:u.refId, name:u.name, amount:u.amount, date: dayISO(u.date) }));
  return `<div class="upcoming-row ${u.days<=7?'soon':''}" style="align-items:center;flex-wrap:wrap;">
    <span style="min-width:0;flex:1;">${escapeAttr(u.name)} <span class="days">${when}${u.shifted?' · movido':''}</span>
      ${u.paid > 0 ? `<span class="days" style="display:block;margin:2px 0 0 0;">pagado ${fmtMoney(u.paid)} de ${fmtMoney(u.full)}</span>` : ''}</span>
    <span class="amt">${fmtMoney(u.amount)}</span>
    <span class="up-btns">
      <button class="mini-btn good" onclick='openMarkPaid(${uj})'>Ya lo pagué</button>
      <button class="mini-btn" onclick="sheet={type:'shift', key:'${u.key}', baseIso:'${u.baseIso}', date:'${dayISO(u.date)}', name:${escapeAttr(JSON.stringify(u.name))}};render()">Mover</button>
    </span>
  </div>`;
}

function catChips(gk, dir, current){
  return `<div class="q-cats">${Object.keys(BANK_CATS).filter(k => BANK_CATS[k].dir === dir || BANK_CATS[k].dir === 'both')
    .map(k => `<button class="chip ${k===current?'active':''}" onclick="setGroupCategory('${gk}','${k}')">${BANK_CATS[k].label}</button>`).join('')}</div>`;
}
function renderQuestionsCard(){
  const qs = pendingQuestions();
  if(!qs.length) return '';
  const q = qs[0];
  const foot = (gk, extra) => `<div class="q-foot">${extra||''}
      <button class="card-link" onclick="dismissGroup('${gk}')">Descartar</button>
      <button class="card-link" style="color:var(--ink-dim);" onclick="snoozeGroup('${gk}')">Después</button></div>`;
  let body;
  if(q.kind === 'dupe'){
    const d = q.dupe;
    body = `<p class="q-ask" style="margin-top:0;">¿"${escapeAttr(d.a.name)}" y "${escapeAttr(d.b.name)}" son el mismo pago?</p>
      <p class="q-text">Tienes un ${itemDesc(d.a)} y un${d.b.type==='debt'?'a':''} ${itemDesc(d.b)}. Si son lo mismo, se está contando dos veces y te pide más plata de la que es.</p>
      <div class="q-btns" style="margin-top:10px;">
        <button class="btn btn-solid" onclick="mergeItems('${d.aRef}','${d.bRef}')">Sí, es el mismo</button>
        <button class="btn" onclick="ignoreDupe('${d.aRef}','${d.bRef}')">No, son distintos</button>
      </div>
      <p class="item-meta" style="margin-top:8px;">Con "Sí" se queda "${escapeAttr(d.b.name)}" y los pagos de "${escapeAttr(d.a.name)}" pasan ahí.</p>${foot(q.gk)}`;
  } else {
    const t = q.t, gk = q.gk;
    const more = q.txs && q.txs.length > 1 ? ` <span class="item-meta" style="display:inline;">· y ${q.txs.length-1} más igual${q.txs.length>2?'es':''}</span>` : '';
    const what = `<p class="q-text">${t.dir==='in'?'Entrada':'Pago'} de <b style="color:var(--ink);">${fmtMoney(t.amount)}</b> · ${fmtShortDate(t.date)}${more}<br>
      <span style="color:var(--ink);">${escapeAttr(displayName(t))}</span> <span class="item-meta" style="display:inline;">· ${escapeAttr(t.desc)}</span></p>`;
    const otherCats = questionCats === gk ? catChips(gk, t.dir, t.category) : '';
    const otherBtn = `<button class="card-link" onclick="toggleQuestionCats('${gk}')">${questionCats === gk ? 'Ocultar categorías' : 'Otra categoría…'}</button>`;
    if(questionRename === gk){
      body = `${what}<p class="q-text" style="margin-top:8px;">¿Qué es? Escríbele un nombre:</p>
        <div class="field-row"><input type="text" id="q-rename" placeholder="Ej: Star Snacks, Renta, Wash Hounds…">
        <button class="btn btn-primary" onclick="saveQuestionRename('${gk}')">Guardar</button></div>${foot(gk)}`;
    } else if(q.kind === 'name'){
      body = `${what}<p class="q-ask">¿Es <b>${escapeAttr(q.sug.name)}</b>?</p>
        <div class="q-btns"><button class="btn btn-solid" onclick="answerName('${gk}', true)">Sí</button>
        <button class="btn" onclick="answerName('${gk}', false)">No</button></div>${otherCats}${foot(gk, otherBtn)}`;
    } else if(q.kind === 'link'){
      const label = t.category === 'debt' ? 'deuda' : 'gasto fijo';
      body = `${what}${q.target ? `<p class="q-ask">¿Es para <b>${escapeAttr(q.target.name)}</b>?</p>
          <div class="q-btns"><button class="btn btn-solid" onclick="answerLink('${gk}', true)">Sí</button>
          <button class="btn" onclick="answerLink('${gk}', false)">No, es otro</button></div>`
        : `<p class="q-ask">Está como <b>${BANK_CATS[t.category].label}</b>. ¿A qué ${label} va?</p>
          <div class="q-btns"><button class="btn btn-solid" onclick="openLinkSheet('${t.id}', '${t.category==='debt'?'debt':'expense'}')">Elegir ${label}</button></div>`}
        <p class="item-meta" style="margin:10px 0 4px 0;">¿No es ${label}? Ponlo donde va:</p>${catChips(gk, t.dir, t.category)}${foot(gk)}`;
    } else if(q.kind === 'loanback'){
      body = `${what}<p class="q-ask">¿Es <b>${escapeAttr(q.loan.person)}</b> devolviéndote el préstamo?</p>
        <p class="q-text">Le prestaste ${fmtMoney(q.loan.amount)} el ${fmtShortDate(q.loan.date)}${loanRepaid(q.loan) ? `; ya te devolvió ${fmtMoney(loanRepaid(q.loan))}` : ''}.</p>
        <div class="q-btns" style="margin-top:10px;"><button class="btn btn-solid" onclick="answerLoanBack('${t.id}','${q.loan.id}', true)">Sí</button>
        <button class="btn" onclick="answerLoanBack('${t.id}','${q.loan.id}', false)">No</button></div>${otherCats}${foot(gk, otherBtn)}`;
    } else if(q.kind === 'loanpick'){
      body = `${what}<p class="q-ask">¿Quién te devolvió el préstamo?</p>
        <div class="q-btns"><button class="btn btn-solid" onclick="sheet={type:'loanPick', txId:'${t.id}'};render()">Elegir préstamo</button></div>${foot(gk)}`;
    } else if(q.kind === 'conflict'){
      body = `${what}<p class="q-ask">Este lo pusiste en ${q.options.length} lugares distintos. ¿Cuál es?</p>
        <p class="q-text">Si son lo mismo, elige uno para que no se cuente doble.</p>
        <div style="margin-top:8px;">${q.options.map(o => `<button class="pick" onclick="resolveConflict('${gk}','${o.id}')"><span>Todo es ${escapeAttr(o.label)}</span><span class="item-meta" style="margin:0;">${o.n} mov.</span></button>`).join('')}</div>
        <button class="btn btn-block" onclick="keepConflict('${gk}')">Son cosas distintas, déjalo así</button>${foot(gk)}`;
    } else {
      const sure = q.last ? `<p class="q-ask">La vez pasada lo pusiste como <b>${BANK_CATS[q.last] ? BANK_CATS[q.last].label : q.last}</b>. ¿Es lo mismo?</p>
          <div class="q-btns"><button class="btn btn-solid" onclick="setGroupCategory('${gk}','${q.last}')">Sí</button>
          <button class="btn" onclick="toggleQuestionCats('${gk}')">No</button></div>${questionCats === gk ? catChips(gk, t.dir, null) : ''}`
        : `<p class="q-ask">¿Qué es? <span class="item-meta" style="display:inline;">La app cree que es ${BANK_CATS[t.category] ? BANK_CATS[t.category].label : t.category}</span></p>
          ${catChips(gk, t.dir, t.category)}
          <div class="q-btns" style="margin-top:6px;"><button class="btn btn-solid" onclick="setGroupCategory('${gk}','${t.category}')">Sí, eso es</button></div>`;
      body = `${what}${sure}${foot(gk)}`;
    }
  }
  return `<div class="card q-card" data-tour="questions">
    <div class="card-head" style="margin-bottom:8px;"><p class="card-title" style="font-size:15px;">Revisar movimientos</p><span class="section-count">${qs.length} pendiente${qs.length===1?'':'s'}</span></div>
    ${body}
  </div>`;
}

function renderTradingCard(){
  const b = monthBounds(0);
  const t = tradingSummary(b.s, b.e);
  if(!t.any) return '';
  return `<div class="card">
    <div class="card-head"><div><p class="card-title">Trading</p><p class="card-sub">${b.label} · aparte de ingresos y gastos</p></div></div>
    <div class="item-row list-first"><div class="item-main"><div class="item-name">Depositaste en la plataforma</div></div><span class="item-amount">${fmtMoney(t.dep)}</span></div>
    <div class="item-row"><div class="item-main"><div class="item-name">Retiraste a tu banco</div></div><span class="item-amount">${fmtMoney(t.wd)}</span></div>
    <div class="item-row"><div class="item-main"><div class="item-name"><b>Resultado del mes</b></div></div><span class="item-amount" style="color:${t.net<0?'var(--warn)':'var(--good)'};">${t.net<0?'-':'+'}${fmtMoney(Math.abs(t.net))}</span></div>
  </div>`;
}

function renderSheet(c){
  if(!sheet) return '';
  let title = '', body = '';
  if(sheet.type === 'link'){
    const t = bankList().find(x => x.id === sheet.txId);
    if(!t){ sheet = null; return ''; }
    const isDebt = sheet.kind === 'debt';
    title = isDebt ? '¿A qué deuda va este pago?' : '¿Qué gasto fijo es?';
    const list = (isDebt ? data.debts : data.expenses).slice().sort((a,b) => Math.abs((isDebt?a.monthlyPayment:a.amount) - t.amount) - Math.abs((isDebt?b.monthlyPayment:b.amount) - t.amount));
    body = `<p class="item-meta" style="margin:0 0 10px 0;">${escapeAttr(displayName(t))} · ${fmtShortDate(t.date)} · <b style="color:var(--ink);">${fmtMoney(t.amount)}</b></p>
      ${list.length ? list.map(x => `<button class="pick" onclick="chooseLink('${t.id}','${isDebt?'debt':'expense'}','${x.id}')">
          <span>${escapeAttr(x.name)}</span><span class="item-meta" style="margin:0;">${isDebt ? 'debes '+fmtMoney(Math.max(0,x.balance||0)) : fmtMoney(x.amount)+' · '+expenseWhen(x).toLowerCase()}</span></button>`).join('')
        : `<p class="item-meta">No tienes ${isDebt?'deudas':'gastos fijos'} registrados todavía. Crea uno aquí abajo.</p>`}
      <p class="sub-head">¿No está en la lista? Crea ${isDebt?'una deuda nueva':'un gasto fijo nuevo'}</p>
      <p class="item-meta" style="margin:-2px 0 8px 0;">Revisa bien la lista de arriba antes de crear uno nuevo, para que no quede repetido.</p>
      <div class="field"><input type="text" id="nl-name" placeholder="${isDebt?'Ej: Carlos Mendez, Child Support':'Ej: Ethan, Renta'}"></div>
      <div class="field-row" style="margin-top:8px;">
        <input type="text" inputmode="decimal" id="nl-amount" placeholder="${isDebt?'Cuánto debes (antes de este pago)':'Monto'}">
        ${isDebt ? `<input type="text" inputmode="decimal" id="nl-pay" placeholder="Pago mensual (opcional)">` : `<select id="nl-freq"><option value="month">Cada mes</option><option value="week">Cada semana</option></select>`}
      </div>
      <button class="btn btn-solid btn-block" style="margin-top:10px;" onclick="createAndLink('${t.id}','${isDebt?'debt':'expense'}')">Crear y conectar</button>
      ${t.link ? `<button class="btn btn-block" style="margin-top:8px;" onclick="unlinkFromSheet('${t.id}')">Quitar conexión</button>` : `<button class="card-link" style="margin-top:8px;" onclick="unlinkFromSheet('${t.id}')">No conectar este pago</button>`}`;
  } else if(sheet.type === 'shift'){
    title = 'Mover este pago';
    body = `<p class="item-meta" style="margin:0 0 12px 0;">${escapeAttr(sheet.name)} · ahora el ${fmtShortDate(sheet.date)}${sheet.date!==sheet.baseIso?` (original: ${fmtShortDate(sheet.baseIso)})`:''}</p>
      <div class="btn-row">
        <button class="btn" style="flex:1;" onclick="shiftBy('${sheet.key}','${sheet.date}',1)">+1 día</button>
        <button class="btn" style="flex:1;" onclick="shiftBy('${sheet.key}','${sheet.date}',3)">+3 días</button>
        <button class="btn" style="flex:1;" onclick="shiftBy('${sheet.key}','${sheet.date}',7)">+1 semana</button>
      </div>
      <p class="sub-head">O elige la fecha</p>
      <div class="field-row"><input type="date" id="shift-date" value="${sheet.date}"><button class="btn btn-primary" onclick="shiftPayment('${sheet.key}', document.getElementById('shift-date').value)">Mover</button></div>
      ${sheet.date !== sheet.baseIso ? `<button class="btn btn-block" style="margin-top:10px;" onclick="shiftPayment('${sheet.key}', null)">Volver a la fecha original</button>` : ''}
      <p class="item-meta" style="margin-top:10px;">Solo cambia este pago. El próximo vuelve a su fecha normal. Para cambiarla siempre, edita el gasto o la deuda.</p>`;
  } else if(sheet.type === 'week'){
    const w = c.weeksAhead[sheet.idx];
    if(!w){ sheet = null; return ''; }
    title = `${capFirst(PL().noun)} del ${fmtShortDate(dayISO(w.weekStart))} al ${fmtShortDate(dayISO(w.weekEnd))}`;
    const items = w.items.slice().sort((a,b)=>a.date-b.date);
    body = `${items.length ? items.map(o => `<div class="mini-row"><span>${fmtShortDate(dayISO(o.date))} · ${escapeAttr(o.name)}${o.shifted?' <span class="item-meta" style="display:inline;">(movido)</span>':''}</span><b style="${o.remaining<=0.004?'color:var(--good);':''}">${o.remaining<=0.004?'pagado ✓':fmtMoney(o.remaining)}</b></div>`).join('') : '<p class="item-meta">No vence nada esta semana.</p>'}
      <div class="total-line"><span>Vence en ese periodo</span><b>${fmtMoney(w.due)}</b></div>
      <div class="total-line" style="border-top:none;margin-top:0;"><span>Conviene ganar</span><b style="color:var(--warn);">${fmtMoney(w.need)}</b></div>
      <p class="item-meta" style="margin-top:8px;">"Conviene ganar" es el mayor entre lo que vence en ese periodo y tu meta promedio (${fmtMoney(c.weeklyTarget)}), para ir guardando para los pagos grandes.</p>`;
  } else if(sheet.type === 'source'){
    const name = sheet.name;
    const b = monthBounds(0);
    const all = incomeBySource(monthBounds(-6).s, b.e);
    const me = all.find(x => x.name === name) || { members: [] };
    const others = all.filter(x => x.name !== name);
    title = name;
    body = `${me.members.length ? `<p class="sub-head" style="margin-top:0;">Unidos aquí</p>${me.members.map(m => `<div class="mini-row"><span>${escapeAttr(m)}</span><button class="btn" style="padding:6px 10px;font-size:12px;" onclick="unmergeSource(${escapeAttr(JSON.stringify(m))})">Separar</button></div>`).join('')}` : ''}
      <p class="sub-head" ${me.members.length?'':'style="margin-top:0;"'}>¿"${escapeAttr(name)}" es la misma que…?</p>
      ${others.length ? others.map(o => `<button class="pick" onclick="mergeSource(${escapeAttr(JSON.stringify(name))}, ${escapeAttr(JSON.stringify(o.name))})"><span>${escapeAttr(o.name)}</span><span class="item-meta" style="margin:0;">unir</span></button>`).join('') : '<p class="item-meta">No hay otras fuentes para unir.</p>'}
      ${(data.merges||{})[name.toLowerCase()] ? '' : ''}`;
  } else if(sheet.type === 'mergeInto'){
    const me = refItem(sheet.ref);
    if(!me){ sheet = null; return ''; }
    title = `Unir "${escapeAttr(me.name)}" con…`;
    const others = data.debts.map(d => 'd:'+d.id).concat(data.expenses.map(e => 'e:'+e.id)).filter(r => r !== sheet.ref).map(refItem).filter(Boolean)
      .sort((a,b) => Math.abs(a.amount - me.amount) - Math.abs(b.amount - me.amount));
    body = `<p class="item-meta" style="margin:0 0 10px 0;">Elige con cuál es el mismo pago. "${escapeAttr(me.name)}" se borra y sus pagos conectados pasan al que elijas.</p>
      ${others.map(o => `<button class="pick" onclick="mergeItems('${sheet.ref}','${o.type==='debt'?'d':'e'}:${o.id}')"><span>${escapeAttr(o.name)}</span><span class="item-meta" style="margin:0;">${o.type==='debt'?'deuda':'gasto fijo'} · ${fmtMoney(o.amount)}</span></button>`).join('') || '<p class="item-meta">No hay otros para unir.</p>'}`;
  } else if(sheet.type === 'markpaid'){
    const u = sheet.u;
    title = `¿Con cuál movimiento pagaste ${escapeAttr(u.name)}?`;
    const cands = markPaidCandidates(u.date, u.amount, sheet.wide);
    const linked = markPaidLinked(u);
    body = `${linked.length ? `<p class="sub-head" style="margin-top:0;">Ya conectado</p>
      ${linked.map(t => `<div class="pick" style="cursor:default;"><span style="min-width:0;"><span style="display:block;">${escapeAttr(displayName(t))}</span><span class="item-meta" style="margin:0;">${fmtShortDate(t.date)}${t.pending ? ' · pendiente' : ''}</span></span><b style="font-family:var(--mono);font-weight:normal;">${fmtMoney(t.amount)}</b></div>`).join('')}
      <p class="item-meta" style="margin:6px 0 10px 0;">Falta ${fmtMoney(u.amount)}. Si con eso ya quedó pagado, ciérralo aquí:</p>` : ''}
      <button class="btn btn-solid btn-block" style="margin-bottom:12px;" onclick="markPaidManual()">${linked.length ? 'Ya quedó pagado' : 'Lo pagué por otro lado (efectivo, otra cuenta)'}</button>
      <p class="item-meta" style="margin:0 0 10px 0;">${linked.length ? 'O elige otro movimiento que lo pagó' : 'O elige el movimiento del banco'}: ${fmtMoney(u.amount)} · ${fmtShortDate(u.date)}. Primero salen los que más se parecen en monto y fecha.</p>
      ${cands.length ? cands.map(t => `<button class="pick" onclick="chooseMarkPaid('${t.id}')"><span style="min-width:0;"><span style="display:block;">${escapeAttr(displayName(t))}</span><span class="item-meta" style="margin:0;">${fmtShortDate(t.date)} · ${escapeAttr(t.desc.slice(0,48))}</span></span><b style="font-family:var(--mono);font-weight:normal;">${fmtMoney(t.amount)}</b></button>`).join('')
        : '<p class="item-meta">No encontré pagos del banco cerca de esa fecha. ¿Ya subiste el extracto de esos días?</p>'}
      ${sheet.wide ? '' : `<button class="card-link" onclick="sheet.wide=true;render()">Ver más movimientos</button>`}
      <p class="item-meta" style="margin-top:10px;">La app recuerda ese comercio: cuando me lo confirmes 2 veces, los próximos se conectan solos.</p>`;
  } else if(sheet.type === 'loanDue'){
    const l = data.loans.find(x => x.id === sheet.loanId);
    if(!l){ sheet = null; return ''; }
    title = `¿Hasta cuándo te paga ${escapeAttr(l.person)}?`;
    const quick = [7, 14, 30].map(n => dayISO(addDays(isoToDate(todayISO()), n)));
    body = `<p class="item-meta" style="margin:0 0 12px 0;">Le prestaste ${fmtMoney(l.amount)} el ${fmtShortDate(l.date)}. Te aviso 2 días antes y ese mismo día.</p>
      <div class="btn-row">${quick.map((d,i) => `<button class="btn" style="flex:1;" onclick="setLoanDue('${l.id}','${d}')">${['1 semana','2 semanas','1 mes'][i]}</button>`).join('')}</div>
      <p class="sub-head">O elige la fecha</p>
      <div class="field-row"><input type="date" id="loan-due-pick" value="${l.due || ''}"><button class="btn btn-primary" onclick="setLoanDue('${l.id}', document.getElementById('loan-due-pick').value)">Guardar</button></div>
      <button class="card-link" style="margin-top:10px;" onclick="setLoanDue('${l.id}','')">Sin fecha por ahora</button>`;
  } else if(sheet.type === 'loanPick'){
    const t = bankList().find(x => x.id === sheet.txId);
    if(!t){ sheet = null; return ''; }
    const pend = data.loans.filter(l => !l.returned);
    title = '¿Quién te devolvió el préstamo?';
    body = `<p class="item-meta" style="margin:0 0 10px 0;">${escapeAttr(displayName(t))} · ${fmtShortDate(t.date)} · <b style="color:var(--ink);">${fmtMoney(t.amount)}</b></p>
      ${pend.length ? pend.map(l => `<button class="pick" onclick="pickLoanForRepayment('${t.id}','${l.id}')"><span>${escapeAttr(l.person)}</span><span class="item-meta" style="margin:0;">te debe ${fmtMoney(l.amount - loanRepaid(l))}</span></button>`).join('')
        : '<p class="item-meta">No tienes préstamos pendientes registrados.</p>'}`;
  } else if(sheet.type === 'merchant'){
    const t0 = bankList().find(x => groupKeyOf(x) === sheet.gk);
    title = t0 ? escapeAttr(displayName(t0)) : '';
    body = renderMerchantSheet();
  } else if(sheet.type === 'alloc'){
    const e = data.expenses.find(x => x.id === sheet.expenseId);
    title = `Agregar ${fmtMoney(sheet.amount)} a una deuda`;
    body = `<p class="item-meta" style="margin:0 0 10px 0;">Pagaste ${fmtMoney(sheet.amount)} más de lo que tocaba en "${escapeAttr(e ? e.name : '')}". ¿A qué deuda lo abonas?</p>
      ${data.debts.length ? data.debts.map(d => `<button class="pick" onclick="allocateExcess('${sheet.expenseId}','${sheet.periodDate}','${d.id}',${sheet.amount})"><span>${escapeAttr(d.name)}</span><span class="item-meta" style="margin:0;">debes ${fmtMoney(Math.max(0,d.balance||0))}</span></button>`).join('')
        : `<p class="item-meta">No tienes deudas registradas. Ve a Deudas y crea, por ejemplo, "Child Support (atrasos)".</p><button class="btn btn-primary btn-block" onclick="sheet=null;setTab('debts');formOpen['debts']=true;render()">Ir a Deudas</button>`}`;
  }
  return `<div class="sheet-bg" onclick="closeSheet()"></div>
    <div class="sheet">
      <div class="sheet-grip"></div>
      <div class="card-head"><p class="card-title" style="font-size:17px;">${title}</p><button class="item-del" onclick="closeSheet()">×</button></div>
      ${body}
    </div>`;
}

// ---- El día que cae la plata del pago: ¿ya llegó? ¿alcanza para lo de la semana? ----
function setDepositDay(d){ prefs.weekStart = d; prefs.depositConfirmed = true; savePrefs(); render(); showToast(`Listo: tu semana va del ${WEEKDAYS[d]} al ${WEEKDAYS[(d+6)%7]}. Con la plata de ese día se paga todo lo de la semana.`); }
function renderPaydayCard(c){
  if(payFreq() !== 'semanal') return '';
  if(!prefs.depositConfirmed){
    return `<div class="card">
      <div class="card-head" style="margin-bottom:6px;"><p class="card-title" style="font-size:15px;">¿Qué día te cae la plata del pago?</p></div>
      <p class="item-meta" style="margin:0 0 10px 0;">El día que llega a tu cuenta, no el día que te pagan. Con esa plata se cubre todo lo de la semana, y la app te avisa si no llega a tiempo.</p>
      <div class="opt-grid days">${[1,2,3,4,5,6,0].map(d => `<button class="opt ${prefs.weekStart===d?'on':''}" onclick="setDepositDay(${d})">${capFirst(WEEKDAYS[d])}</button>`).join('')}</div>
    </div>`;
  }
  const today = isoToDate(todayISO());
  const wk = c.weeksAhead.find(w => w.isCurrent);
  const dep = new Date(wk.weekStart); dep.setHours(0,0,0,0);
  const next = addDays(dep, 7);
  const winEnd = addDays(dep, 3); winEnd.setHours(23,59,59,999);
  const inWin = allIncomes().filter(i => { const d = isoToDate(i.date); return d >= dep && d <= winEnd; }).sort((a,b) => b.amount - a.amount);
  const pay = inWin[0];
  const daysSince = Math.round((today - dep) / 86400000);
  const bills = occurrencesInRange(wk.weekStart, wk.weekEnd).filter(o => o.remaining > 0.004).sort((a,b) => a.date - b.date);
  const due = bills.reduce((s,o) => s + o.remaining, 0);
  let status, color;
  if(pay){
    const late = Math.round((isoToDate(pay.date) - dep) / 86400000);
    status = `✓ Te cayó ${fmtMoney(pay.amount)} el ${fmtShortDate(pay.date)}${late > 0 ? ` (${late} día${late===1?'':'s'} tarde)` : ''}`;
    color = late > 0 ? 'var(--warn)' : 'var(--good)';
  } else if(daysSince <= 0){
    status = `Hoy te cae la plata del pago`; color = 'var(--ink)';
  } else {
    status = `No ha llegado la plata del ${WEEKDAYS[dep.getDay()]} ${fmtShortDate(dayISO(dep))}. Revisa con tu trabajo o sube el extracto si ya llegó.`;
    color = 'var(--warn)';
  }
  let cover = '';
  if(due > 0){
    const ok = pay && pay.amount >= due - 0.009;
    cover = `<p class="item-meta" style="margin:10px 0 4px 0;">Con esa plata pagas lo de esta semana: <b style="color:var(--ink);">${fmtMoney(due)}</b>${pay ? (ok ? ` · alcanza y sobran ${fmtMoney(pay.amount - due)}` : ` · faltan ${fmtMoney(due - pay.amount)}`) : ''}</p>
      ${bills.map(o => `<div class="upcoming-row"><span>${escapeAttr(o.name)} <span class="days">${WEEKDAYS[o.date.getDay()]} ${fmtShortDate(dayISO(o.date))}</span></span><span class="amt">${fmtMoney(o.remaining)}</span></div>`).join('')}`;
  } else if(pay){
    cover = `<p class="item-meta" style="margin:10px 0 0 0;">No te queda nada pendiente esta semana.</p>`;
  }
  return `<div class="card" style="border-color:${pay ? 'var(--line)' : daysSince > 0 ? 'var(--warn)' : 'var(--line)'};">
    <div class="card-head" style="margin-bottom:6px;"><div><p class="card-title" style="font-size:15px;">Plata del pago</p><p class="card-sub">Semana del ${fmtShortDate(dayISO(dep))} al ${fmtShortDate(dayISO(addDays(dep,6)))} · el próximo cae el ${WEEKDAYS[next.getDay()]} ${fmtShortDate(dayISO(next))}</p></div></div>
    <p style="margin:0;color:${color};font-size:14.5px;line-height:1.5;">${status}</p>
    ${cover}
  </div>`;
}

function renderLoansDueCard(){
  const today = isoToDate(todayISO());
  const list = data.loans.filter(l => !l.returned && l.due)
    .map(l => ({ l, days: Math.round((isoToDate(l.due) - today) / 86400000) }))
    .filter(x => x.days <= 3).sort((a,b) => a.days - b.days);
  if(!list.length) return '';
  return `<div class="card" style="border-color:var(--s3);">
    <div class="card-head" style="margin-bottom:6px;"><p class="card-title" style="font-size:15px;">Te tienen que pagar</p></div>
    ${list.map(x => `<div class="upcoming-row" style="cursor:pointer;" onclick="setTab('mov');setMovSeg('loans')">
      <span>${escapeAttr(x.l.person)} <span class="days">${x.days<0?`vencido hace ${-x.days} día${x.days===-1?'':'s'}`:x.days===0?'hoy':x.days===1?'mañana':'en '+x.days+' días'}</span></span>
      <span class="amt" style="color:var(--good);">${fmtMoney(x.l.amount - loanRepaid(x.l))}</span></div>`).join('')}
  </div>`;
}

function openMerchantSheet(dir, key){ sheet = { type:'merchant', gk: dir + '|' + key }; render(); }
function renderMerchantSheet(){
  const gk = sheet.gk;
  const txs = bankList().filter(x => groupKeyOf(x) === gk).sort((a,b) => b.date.localeCompare(a.date));
  if(!txs.length){ sheet = null; return ''; }
  const t = txs[0];
  return `<p class="item-meta" style="margin:0 0 6px 0;">${txs.length} movimiento${txs.length===1?'':'s'} · último ${fmtShortDate(t.date)} · ${fmtMoney(t.amount)}</p>
    <p class="item-meta" style="margin:0 0 10px 0;">${escapeAttr(t.desc)}</p>
    ${t.link ? `<p class="note-good" style="font-size:13px;margin:0 0 10px 0;">${escapeAttr(assignmentOf(t).label)}</p>` : ''}
    <p class="sub-head" style="margin-top:0;">¿Qué es?</p>
    ${catChips(gk, t.dir, t.category)}
    <button class="btn btn-block" style="margin-top:8px;" onclick="sheet=null;startSourceEdit('${t.id}');setTab('mov');setMovSeg('bank');startSourceEdit('${t.id}')">✎ Ponerle nombre</button>`;
}

// Cuando sale el teclado, la casilla donde escribes se acomoda a la vista (también dentro de la ventana de abajo).
function keepFocusVisible(){
  const el = document.activeElement;
  if(!el || !/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)) return;
  setTimeout(() => { try{ el.scrollIntoView({ block:'center', behavior:'smooth' }); }catch(e){} }, 60);
}
document.addEventListener('focusin', () => setTimeout(keepFocusVisible, 250));
window.addEventListener('resize', keepFocusVisible);
