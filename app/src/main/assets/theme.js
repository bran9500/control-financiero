// Colores, fondo y letra elegidos en "Más → Personalizar" de Control Financiero.
(function(){
  var A = { verde:['#6FA98A','#0E1A14'], azul:['#5B9BE6','#0B1626'], morado:['#A18FF0','#15112A'], rosa:['#E07AA5','#2A0F1C'],
            naranja:['#E39A55','#2A1708'], turquesa:['#4FB8B8','#0A2222'], dorado:['#D4B25A','#241C06'], rojo:['#E37070','#2A0D0D'] };
  var B = { grafito:['#12161C','#1B2129','#232A35','#2A313B'], medianoche:['#0D1322','#151D31','#1D263D','#27324C'], negro:['#000000','#0F1012','#18191C','#25272B'],
            cafe:['#16120E','#201A15','#2A231C','#362D25'], bosque:['#0E1511','#161F1A','#1E2922','#28352D'], vino:['#170E12','#21151A','#2C1C23','#3A262F'] };
  var F = { clasica:"Georgia, 'Noto Serif', serif", moderna:"-apple-system, Roboto, 'Segoe UI', sans-serif",
            condensada:"'sans-serif-condensed', 'Roboto Condensed', 'Arial Narrow', sans-serif", maquina:"'monospace', ui-monospace, Consolas, monospace",
            casual:"'casual', 'Comic Sans MS', cursive", manuscrita:"'cursive', 'Dancing Script', cursive" };
  var Z = { normal:1, grande:1.08, xl:1.16 };
  var p = {}; try{ p = JSON.parse(localStorage.getItem('app-prefs') || '{}') || {}; }catch(e){}
  var css = ':root{';
  var a = A[p.accent]; if(a) css += '--good:' + a[0] + ';--accent:' + a[0] + ';--on-accent:' + a[1] + ';';
  var b = B[p.bg]; if(b) css += '--bg:' + b[0] + ';--surface:' + b[1] + ';--surface-raised:' + b[2] + ';--line:' + b[3] + ';';
  if(F[p.font]) css += '--serif:' + F[p.font] + ';';
  if(p.font === 'condensada') css += '--sans:' + F.condensada + ';';
  css += '}';
  if(a) css += '.btn-solid,.fab.primary{color:' + a[1] + ' !important;}';
  if(Z[p.size] && Z[p.size] !== 1) css += 'body{zoom:' + Z[p.size] + ';}';
  var st = document.createElement('style'); st.textContent = css;
  (document.head || document.documentElement).appendChild(st);
})();
