
// ================================================================ CAMBIO DE MONEDA
// Convertidor (por ejemplo dólares → pesos colombianos para mandar plata).
// Usa tasas guardadas en la app y las actualiza solas cuando hay internet.
const FX_KEY = 'fx-rates';
let fxLastTry = 0;
let fxState = { from: null, to: null, amount: '' , busy:false, err:'' };
function fxRates(){
  try{ const c = JSON.parse(localStorage.getItem(FX_KEY) || 'null'); if(c && c.rates && c.date >= FX_SNAPSHOT.date) return c; }catch(e){}
  return FX_SNAPSHOT;
}
function fxConvert(amount, from, to){
  const r = fxRates().rates, a = r[from.toLowerCase()], b = r[to.toLowerCase()];
  if(!a || !b) return null;
  return amount / a * b;            // tasas con base en dólar
}
function fmtIn(code, n, precise){
  const c = CURRENCIES[code]; if(!c) return n.toFixed(2);
  const small = precise && Math.abs(n) < 10;
  const s = Math.abs(n).toLocaleString(c.locale, small ? { minimumFractionDigits: 2, maximumSignificantDigits: 4 } : { minimumFractionDigits: c.dec, maximumFractionDigits: c.dec });
  return (n < 0 ? '-' : '') + (c.after ? s + ' ' + c.sym : c.sym + s);
}
async function fxRefresh(manual){
  if(fxState.busy) return;
  const age = Date.now() - (fxRates().fetched || 0);
  if(!manual && (age < 6 * 3600 * 1000 || Date.now() - fxLastTry < 10 * 60 * 1000)) return;   // no insistir sin internet
  fxLastTry = Date.now();
  fxState.busy = true; fxState.err = ''; if(manual) render();
  const urls = ['https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json',
                'https://latest.currency-api.pages.dev/v1/currencies/usd.json'];
  let ok = false;
  for(const u of urls){
    try{
      const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 9000);
      const res = await fetch(u, { signal: ctl.signal }); clearTimeout(t);
      const d = await res.json();
      if(d && d.usd && d.usd.cop){
        localStorage.setItem(FX_KEY, JSON.stringify({ date: String(d.date).replace(/\./g,'-'), rates: d.usd, fetched: Date.now() }));
        ok = true; break;
      }
    }catch(e){}
  }
  if(!ok){
    // respaldo: otra fuente con distinto formato
    try{ const res = await fetch('https://open.er-api.com/v6/latest/USD'); const d = await res.json();
      if(d && d.rates && d.rates.COP){ const r = {}; Object.keys(d.rates).forEach(k => r[k.toLowerCase()] = d.rates[k]);
        localStorage.setItem(FX_KEY, JSON.stringify({ date: new Date((d.time_last_update_unix||0)*1000 || Date.now()).toISOString().slice(0,10), rates: r, fetched: Date.now() })); ok = true; } }catch(e){}
  }
  fxState.busy = false;
  if(!ok && manual) fxState.err = 'Sin internet ahora: uso las últimas tasas guardadas.';
  if(ok && (activeTab === 'more' || activeTab === 'fx')) render();
  else if(manual) render();
  if(ok && manual) showToast('Tasas actualizadas ✓');
}
function fxSet(k, v){ fxState[k] = v; const el = document.getElementById('fx-out'); if(k === 'amount' && el){ el.innerHTML = fxResult(); return; } render(); }
function fxSwap(){ const f = fxState.from; fxState.from = fxState.to; fxState.to = f; render(); }
function fxResult(){
  const from = fxState.from || currencyInfo().code, to = fxState.to || (from === 'USD' ? 'COP' : 'USD');
  const amt = parseFloat(String(fxState.amount).replace(/[^\d.]/g,'')) || 0;
  const v = fxConvert(amt || 1, from, to);
  if(v === null) return `<p class="item-meta">No tengo la tasa de esa moneda.</p>`;
  return `<div class="fx-big">${amt ? fmtIn(to, v) : '—'}</div>
    <p class="item-meta">1 ${from} = ${fmtIn(to, fxConvert(1, from, to), true)} · 1 ${to} = ${fmtIn(from, fxConvert(1, to, from), true)}</p>`;
}
function renderFxCard(){
  setTimeout(() => fxRefresh(false), 50);
  const from = fxState.from || currencyInfo().code, to = fxState.to || (from === 'USD' ? 'COP' : 'USD');
  const d = fxRates().date;
  return `<div class="card" data-tour="fx">
    <div class="card-head"><div><p class="card-title">Cambio de moneda</p><p class="card-sub">Tasas del ${fmtShortDate(d)}${fxState.busy ? ' · actualizando…' : ''}</p></div>
      <button class="card-link" onclick="fxRefresh(true)">↻ Actualizar</button></div>
    <div class="fx-row">
      <input type="text" inputmode="decimal" class="fx-amt" placeholder="Monto" value="${escapeAttr(fxState.amount)}" oninput="fxSet('amount', this.value)">
      ${currencySelect('fx-from', from, "fxSet('from', this.value)").replace('cur-select','cur-select fx-sel')}
    </div>
    <div style="text-align:center;margin:6px 0;"><button class="mini-btn" onclick="fxSwap()">⇅ Invertir</button></div>
    ${currencySelect('fx-to', to, "fxSet('to', this.value)").replace('cur-select','cur-select fx-sel')}
    <div id="fx-out" style="margin-top:12px;">${fxResult()}</div>
    ${fxState.err ? `<p class="item-meta" style="color:var(--warn);margin-top:6px;">${fxState.err}</p>` : ''}
    <p class="item-meta" style="margin-top:8px;">Es la tasa del mercado; las remesadoras y los bancos cobran un poco más.</p>
  </div>`;
}
