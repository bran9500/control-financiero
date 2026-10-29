function renderSavingsTab(which){
  const label = which==='emergency' ? 'Fondo de emergencia' : 'Inversiones';
  const movements = data.savings[which].movements;
  const total = savingsTotal(which);
  const kind = 'savings-'+which;
  const editing = editingItem && editingItem.kind===kind ? movements.find(m=>m.id===editingItem.id) : null;
  const groups = groupByMonth(movements.slice().sort((a,b)=>new Date(b.date)-new Date(a.date)), m => m.type==='withdraw' ? -(m.amount||0) : (m.amount||0));

  let html = `<div class="hero">
    <p class="hero-label">${label} — total actual</p>
    <p class="hero-number good" style="font-size:30px;">${fmtMoney(total)}</p>
  </div>`;

  if(which === 'investments'){
    html += `<div class="balance-row">
      <label for="inv-rate">Interés / rendimiento (%)</label>
      <input type="text" inputmode="decimal" id="inv-rate" value="${data.savings.investments.rate||0}" style="width:90px;" onchange="setInvestmentRate(this.value)">
    </div>`;
  }

  html += groups.length ? groups.map((g,gi)=>{
    const open = isMonthOpen(kind, g.key, gi===0);
    return `
    <div class="month-header" onclick="toggleMonth('${kind}','${g.key}')"><span>${g.label}</span><span class="amt">${fmtMoney(g.total)} <span class="chevron ${open?'open':''}">›</span></span></div>
    ${open ? g.items.map(m=>`
      <div class="item-row">
        <div class="item-main clickable" onclick="startEdit('${kind}','${m.id}')">
          <div class="item-name">${m.type==='withdraw'?'Retiro':'Depósito'}${m.note?' · '+m.note:''}</div>
          <div class="item-meta">${new Date(m.date+'T00:00:00').toLocaleDateString('es-ES',{day:'numeric',month:'short'})}</div>
        </div>
        <div class="item-amounts"><span class="item-amount" style="${m.type==='withdraw'?'color:var(--warn);':'color:var(--good);'}">${m.type==='withdraw'?'-':'+'}${fmtMoney(m.amount)}</span></div>
        ${deleteControlSavings(which, m.id)}
      </div>`).join('') : ''}
  `;}).join('') : '<div class="empty-note">Sin movimientos registrados.</div>';

  if(formOpen[kind] || !!editing){
    html += `<div class="add-form" id="edit-anchor-${kind}">
      <div class="field-row">
        <div class="field"><label>Tipo</label>
          <select id="sav-${which}-type">
            <option value="deposit" ${editing&&editing.type==='deposit'?'selected':''}>Depósito</option>
            <option value="withdraw" ${editing&&editing.type==='withdraw'?'selected':''}>Retiro</option>
          </select>
        </div>
        <div class="field"><label>Monto</label><input type="text" inputmode="decimal" id="sav-${which}-amount" placeholder="0.00" value="${editing?editing.amount:''}"></div>
      </div>
      <div class="field-row">
        <div class="field"><label>Fecha</label><input type="date" id="sav-${which}-date" value="${editing?editing.date:new Date().toISOString().slice(0,10)}"></div>
        <div class="field"><label>Nota (opcional)</label><input type="text" id="sav-${which}-note" placeholder="Opcional" value="${editing?escapeAttr(editing.note):''}"></div>
      </div>
      <div class="field-row" style="gap:8px;">
        <button class="btn btn-primary" onclick="submitSavingsMovement('${which}')">${editing?'Guardar cambios':'Añadir movimiento'}</button>
        <button class="btn" onclick="${editing?'cancelEdit()':`toggleFormOpen('${kind}')`}">Cancelar</button>
      </div>
    </div>`;
  } else {
    html += `<button class="add-toggle-btn" id="edit-anchor-${kind}" onclick="toggleFormOpen('${kind}')">+ Añadir movimiento</button>`;
  }

  return html;
}

function renderSection(key, title, count, bodyHtml){
  const open = sectionsOpen[key];
  const countHtml = (count === '' ) ? '' : ` <span class="section-count">(${count})</span>`;
  return `<section class="fold" data-tour="sec-${key}">
    <div class="section-head" onclick="toggleSection('${key}')">
      <p class="section-title">${title}${countHtml}</p>
      <span class="chevron ${open?'open':''}">›</span>
    </div>
    <div class="section-body ${open?'open':''}">${bodyHtml}</div>
  </section>`;
}

function submitIncome(){
  const amount = document.getElementById('inc-amount').value;
  const date = document.getElementById('inc-date').value;
  const source = document.getElementById('inc-source').value;
  if(!amount || !date) return;
  if(editingItem && editingItem.kind==='incomes'){
    updateIncome(editingItem.id, amount, date, source);
    editingItem = null;
  } else {
    addIncome(amount, date, source);
  }
  formOpen['incomes'] = false;
}
function submitHormiga(){
  const amount = document.getElementById('hor-amount').value;
  const date = document.getElementById('hor-date').value;
  const desc = document.getElementById('hor-desc').value;
  if(!amount || !date) return;
  if(editingItem && editingItem.kind==='hormiga'){
    updateHormiga(editingItem.id, amount, date, desc);
    editingItem = null;
  } else {
    addHormiga(amount, date, desc);
  }
  formOpen['hormiga'] = false;
}
function submitDebt(){
  const name = document.getElementById('debt-name').value;
  const balance = document.getElementById('debt-balance').value;
  const rate = document.getElementById('debt-rate').value;
  const payment = document.getElementById('debt-payment').value;
  const due = document.getElementById('debt-due') ? document.getElementById('debt-due').value : '';
  const freq = (document.getElementById('debt-freq') || {}).value === 'week' ? 'week' : 'month';
  const extra = { payFreq: freq, weeklyPayment: (document.getElementById('debt-weekly') || {}).value || 0,
                  weekday: (document.getElementById('debt-weekday') || {}).value ?? 5, flexible: !!(document.getElementById('debt-flex') || {}).checked };
  if(!name || (freq === 'month' ? !payment : !parseMoney(extra.weeklyPayment))) return;
  if(editingItem && editingItem.kind==='debts'){
    updateDebt(editingItem.id, name, balance||0, rate||0, due, payment, extra);
    editingItem = null;
  } else {
    addDebt(name, balance||0, rate||0, due, payment, extra);
  }
  formOpen['debts'] = false;
}
function submitExpense(){
  const name = document.getElementById('exp-name').value;
  const amount = document.getElementById('exp-amount').value;
  const freq = document.getElementById('exp-freq').value;
  const day = document.getElementById('exp-day') ? document.getElementById('exp-day').value : 1;
  const weekday = document.getElementById('exp-weekday') ? document.getElementById('exp-weekday').value : 5;
  if(!name || !amount || (freq !== 'week' && !day)) return;
  if(editingItem && editingItem.kind==='expenses'){
    updateExpense(editingItem.id, name, amount, day, freq, weekday);
    editingItem = null;
  } else {
    addExpense(name, amount, day, freq, weekday);
  }
  formOpen['expenses'] = false;
}
function submitLoan(){
  const person = document.getElementById('loan-person').value;
  const amount = document.getElementById('loan-amount').value;
  const date = document.getElementById('loan-date').value;
  const note = document.getElementById('loan-note').value;
  const due = (document.getElementById('loan-due') || {}).value || '';
  if(!person || !amount || !date) return;
  if(editingItem && editingItem.kind==='loans'){
    const existing = data.loans.find(l=>l.id===editingItem.id);
    updateLoan(editingItem.id, person, amount, date, note, existing ? existing.returned : false, due);
    editingItem = null;
  } else {
    addLoan(person, amount, date, note, due);
  }
  formOpen['loans'] = false;
}
