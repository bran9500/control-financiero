// Se ejecuta solo al compilar el APK (GitHub Actions): comprime y revuelve el JavaScript
// de las pantallas para que no sea fácil de leer ni copiar desde el APK.
// El código legible queda en web-src/ (repositorio privado).
import { minify } from 'terser';
import fs from 'fs';
import path from 'path';

const root = 'app/src/main/assets';
const opts = { compress: { passes: 2 }, mangle: { toplevel: false }, format: { comments: false } };
const files = [];
(function walk(d){ for(const f of fs.readdirSync(d)){ const p = path.join(d, f); const st = fs.statSync(p);
  if(st.isDirectory()){ if(f !== 'pdfjs') walk(p); } else if(/\.(html|js)$/.test(f)) files.push(p); } })(root);

let before = 0, after = 0;
for(const f of files){
  const src = fs.readFileSync(f, 'utf8'); before += src.length;
  let out;
  if(f.endsWith('.js')){
    if(/^(words|phrases)\.js$/.test(path.basename(f))){ after += src.length; continue; }   // datos, no código
    out = (await minify(src, opts)).code;
  } else {
    const parts = src.split(/(<script(?![^>]*\bsrc=)[^>]*>)([\s\S]*?)(<\/script>)/i);
    out = '';
    for(let i = 0; i < parts.length; i++){
      if(i % 4 === 2){ out += (await minify(parts[i], opts)).code; }
      else out += parts[i];
    }
    out = out.replace(/<style>([\s\S]*?)<\/style>/g, (m, css) => '<style>' + css.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\s*\n\s*/g, '') + '</style>');
  }
  fs.writeFileSync(f, out); after += out.length;
  console.log('protegido', f);
}
console.log(`listo: ${before} → ${after} bytes`);
