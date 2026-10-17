#!/bin/sh
# Arma app/src/main/assets/index.html a partir de las piezas de esta carpeta (código legible).
cd "$(dirname "$0")"
{ cat part_head.html; cat part_logic.js part_bank.js part_extras.js part_sched.js part_old_helpers.js part_render.js part_shot.js part_prefs.js part_settings.js part_fx.js part_tour.js; printf '\nloadData();\n</script>\n</body>\n</html>\n'; } > ../app/src/main/assets/index.html
echo "index.html armado"
