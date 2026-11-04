// Botón ⇄ para saltar entre las apps sin volver a Inicio. Lo usan todas las subapps.
(function(){
  if(!window.AppSwitch) return;
  var APPS = [
    ['inicio','Inicio','🏠'], ['rotador','Trading','📈'],
    ['bot','Bot Señales','🤖'], ['notas','Notas','🎙'], ['ingles','Inglés','🔤'], ['interes','Interés','💹']
  ];
  var here = (location.pathname.match(/\/([a-z]+)\/index\.html$/) || [])[1] || '';
  if(here === 'diario') here = 'rotador';
  var css = document.createElement('style');
  css.textContent =
    '#appsw{position:fixed;right:0;top:56%;z-index:2147483000;width:30px;height:46px;border-radius:12px 0 0 12px;border:1px solid rgba(217,180,90,.55);border-right:none;' +
    'background:rgba(20,24,31,.88);color:#D9B45A;font:700 17px sans-serif;display:flex;align-items:center;justify-content:center;padding:0;cursor:pointer;box-shadow:-2px 2px 10px rgba(0,0,0,.35)}' +
    'body.mini #appsw,body.vert #appsw,body.deskmini #appsw{display:none}' +
    '#appsw-bg{position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:2147483001;display:flex;align-items:flex-end;justify-content:center}' +
    '#appsw-sh{background:#12161C;border:1px solid #2A313B;border-radius:18px 18px 0 0;width:100%;max-width:560px;padding:16px 14px calc(22px + env(safe-area-inset-bottom)) 14px;color:#E8E6E0;font-family:-apple-system,Roboto,sans-serif}' +
    '#appsw-sh p{margin:0 0 12px 2px;font-size:13px;color:#8B93A0}' +
    '#appsw-g{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}' +
    '#appsw-g button{background:#1B2129;border:1px solid #2A313B;border-radius:14px;color:#E8E6E0;padding:12px 4px;font:500 11.5px -apple-system,Roboto,sans-serif;display:flex;flex-direction:column;align-items:center;gap:6px;cursor:pointer;min-width:0}' +
    '#appsw-g button i{font-style:normal;font-size:22px;line-height:1}' +
    '#appsw-g button.here{border-color:#D9B45A;color:#D9B45A}';
  document.head.appendChild(css);
  var b = document.createElement('button');
  b.id = 'appsw'; b.type = 'button'; b.textContent = '⇄'; b.setAttribute('aria-label', 'Ir a otra app');
  b.onclick = function(){
    var bg = document.createElement('div'); bg.id = 'appsw-bg';
    bg.innerHTML = '<div id="appsw-sh"><p>Ir a otra app</p><div id="appsw-g">' + APPS.map(function(a){
      return '<button data-a="' + a[0] + '" class="' + (a[0] === here ? 'here' : '') + '"><i>' + a[2] + '</i>' + a[1] + '</button>';
    }).join('') + '</div></div>';
    bg.onclick = function(e){
      var t = e.target.closest ? e.target.closest('button[data-a]') : null;
      if(t){ if(t.getAttribute('data-a') !== here) AppSwitch.open(t.getAttribute('data-a')); }
      bg.remove();
    };
    document.body.appendChild(bg);
  };
  function add(){ document.body.appendChild(b); }
  if(document.body) add(); else document.addEventListener('DOMContentLoaded', add);
})();
