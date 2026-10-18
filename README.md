# Control Financiero

App Android (WebView) del control financiero semanal de Bran: ingresos, deudas,
gastos fijos y gastos hormiga, con proyección de las próximas semanas.

Versión 2.0:
- Diseño con pestañas abajo (Inicio, Movimientos, Deudas, Subir extracto, Más) y
  tarjetas con gráficas en Inicio. Inicio y las listas muestran solo el mes actual;
  los meses anteriores quedan en Más → Historial.
- Subir extractos de TD Bank en CSV (uno o varios a la vez). Los movimientos
  repetidos —entre archivos, con lo ya subido, o con lo anotado a mano— no se
  cuentan dos veces. Cada carga se puede deshacer.
- Categorías automáticas con reglas por comercio, ingresos por fuente, detección de
  cobros que se repiten, recordatorios de pagos (notificaciones) y exportar un
  resumen para analizarlo con Claude.

Versión 2.1 — todo en una sola app:
- Rotador de Lotes (de bran9500/rotador-lotes) y Bot Señales (de bran9500/Prueba-1-bot-)
  como subaplicaciones, cada una con su ventana flotante. Código en
  `com.ktrsis.rotador` y `com.ktrsis.bot`, pantallas en `assets/rotador` y `assets/bot`.
- Notas por voz (`com.ktrsis.notas`, `assets/notas`): dictar con el reconocimiento de voz
  del teléfono y escuchar la nota en voz alta.
- Widget de pantalla de inicio con lo de la semana y accesos a las demás apps.

Versión 2.2: los extractos de TD Bank también se pueden subir en PDF. Se leen en el
teléfono con pdf.js (`assets/pdfjs`, v3.11.174, licencia Apache-2.0), sin internet.

Los datos se guardan con `localStorage` dentro del WebView (almacenamiento local
del propio dispositivo), así que no dependen de ninguna conexión ni servicio
externo — persisten igual que en un navegador normal.

## Descargar el APK

Cada push a `main` compila automáticamente y publica el APK en
[Releases](../../releases) — descárgalo desde ahí en el teléfono e instálalo
(puede pedir habilitar "orígenes desconocidos" la primera vez).

## Actualizar la app

Para actualizar el contenido, reemplaza `app/src/main/assets/index.html` con
la versión nueva y vuelve a hacer push a `main`.

Versión 2.3 — todo conectado:
- Los pagos del banco marcados como "Pago de deuda" o "Gasto fijo" se conectan a una deuda o
  gasto fijo (o crean uno). A las deudas se les resta el monto; se deshace si cambias la
  categoría o deshaces el extracto. Pagos programados por deuda (ej. los de una orden).
- Gastos fijos mensuales o semanales, con lo pagado contra lo que toca en el periodo; si pagas
  de más, el sobrante se puede abonar a una deuda.
- Mover un pago unos días (solo esa vez); resumen de lo que conviene ganar las próximas semanas.
- Nombres por comercio/fuente que la app aprende, con preguntas Sí/No al abrir la app;
  fusionar fuentes de ingreso; Zelle separado por persona.
- Categorías "Trading" (resultado neto aparte) y "Movimiento propio" (cajero, transferencias).
- Widget con barra de avance, próximos pagos, próximas semanas, hormiga, deuda y pendientes.

Versión 2.4 — revisar movimientos:
- Tarjeta "Revisar movimientos" agrupada por comercio/persona: todas las categorías a un toque,
  Descartar (no vuelve) y Después (vuelve en 3 días; a la segunda se descarta). Solo los últimos 45 días.
- La app aprende con 2 confirmaciones (categoría y a qué deuda/gasto va un comercio); antes, pregunta.
- Si un mismo comercio quedó en dos cosas distintas, o una deuda y un gasto fijo tienen casi el mismo
  monto, pregunta si es lo mismo para no contarlo doble.
- "Ya lo pagué" en cada pago pendiente para elegir el movimiento del banco que lo pagó.
- Préstamos desde el banco (Zelle a alguien) con fecha de pago, aviso, y detección cuando te devuelven.
- Los pagos anteriores a cuando registraste una deuda se conectan sin volver a restarse del saldo.

Versión 2.5: "Unir con…" en cada gasto fijo y deuda (los pagos conectados pasan al que queda) y
detección de gastos fijos repetidos entre sí (mismo nombre o casi el mismo monto).

Versión 2.6: el teclado ya no tapa las casillas (la pantalla se encoge y la casilla se acomoda a la vista) y detección de deudas repetidas con el mismo nombre.

Versión 2.7: los extractos en PDF ya no leen como pagos las filas de la tabla de saldos diarios; los movimientos falsos que ya se habían guardado se borran solos al abrir.

### 2.8
- Nueva subapp **Inglés**: 6,000 palabras ordenadas por las que más usas (sacadas de tus conversaciones) y luego las más comunes del inglés. 10 nuevas por día (configurable), repaso espaciado, voz en inglés, práctica de pronunciación con el micrófono, quiz, frases y lista con buscador. Traducciones de Wiktionary (CC BY-SA), frases de Tatoeba (CC BY 2.0 FR).

### 2.9
- Inglés → **Mis frases**: 190 frases con tu forma de hablar (tus muletillas, trabajo, tatuajes, trading, dinero, llamadas…) en inglés natural. Cada una se abre cuando ya estudiaste sus palabras, con repaso propio y micrófono. En la tarjeta de cada palabra aparece "Tu frase".
- Inglés → **Practicar con Claude**: exporta tu progreso (palabras, las que te cuestan, tus frases y reglas de práctica) para practicar por voz con Claude. Las frases que Claude te da al final se pegan en "Mis frases → Agregar".
- Se agregaron palabras básicas que faltaban (I, me, it, why, again, números, hola…); la lista queda en 6,040 sin perder tu progreso.

### 3.0
- Nueva subapp **Interés compuesto** (igual que el Excel "A por 100 victorias"): capital inicial, % por victoria y meta editables; botón "Gané (W)" que marca la fecha, registro de pérdidas que recalcula lo que falta, deshacer, varios planes, gráfica, metas ($1,000, $10k…) y la tabla # / USD / % / INT. / USD F. / W / FECHA.

### 3.1
- Interés compuesto: tabla nueva más clara (Entra / Resultado / Queda / Fecha, cada fila separada, cifras grandes abreviadas).
- "Otro resultado": registrar cualquier ganancia o pérdida poniendo el %, el monto o en cuánto quedó el capital; puedes decidir si cuenta como victoria.

### 3.2
- **Subir captura de pantalla**: en "Subir extracto" ahora puedes subir screenshots de los movimientos recientes de la app de TD. El teléfono lee el texto (Google ML Kit, sin internet), arma los movimientos (pendientes con fecha de hoy, los demás con la fecha del título), ignora el saldo gris debajo de cada monto y toma el "Available balance" para actualizar tu saldo. Puedes corregir, quitar o agregar filas antes de guardar.
- Las capturas que se pisan no se duplican, y cuando luego subes el extracto, los movimientos que ya llegaron por captura se confirman con los datos del extracto en vez de contarse dos veces.

### 3.3
- **Bienvenida** la primera vez que abres la app: tu nombre, qué día empieza tu semana, tu saldo de arranque y personalización. Opción de restaurar un respaldo si vienes de otro teléfono.
- **Saludo arriba** con tu nombre, según la hora ("Buenos días / Buenas tardes / Buenas noches, Brandon"), estilo relajado ("¿Qué más, Brandon?") o con emoji.
- **Personalizar** (Más → Personalizar): color, fondo, letra de los títulos, tamaño de letra, saludo y día de inicio de la semana. Las subapps (Inglés, Interés, Notas) usan los mismos colores.
- **Respaldo completo**: "Guardar archivo" ahora guarda finanzas + Inglés + Interés compuesto + Notas + tus preferencias, y "Restaurar" lo recupera todo.
- **Acerca de**: Creada por Brandon Poveda, hecha con la ayuda de Claude.

### 3.4
- **Recorrido guiado**: después de la bienvenida, la app oscurece la pantalla e ilumina cada parte (saludo, apps, preguntas, tu semana, próximos pagos, movimientos, deudas, subir extracto/captura, más, respaldo) con una explicación corta. Se repite desde Más → Ayuda.
- **Video tutorial** (1:37) dentro de la app: Más → Ayuda → Ver video.

### 3.5
- **Botones arriba**: "Tutorial", 🔍 buscar y ⚙ Ajustes en todas las pantallas.
- **⚙ Ajustes**: perfil (nombre, saludo), apariencia (color, fondo, letra, tamaño), pagos y moneda, respaldo/restaurar, ayuda y acerca de.
- **Cada cuánto te pagan**: semanal, cada 2 semanas, quincenal (1–15 y 16–fin) o mensual. Inicio, metas, estadísticas e historial se ajustan al periodo ("Tu quincena", "Tu mes").
- **Moneda**: dólar, peso colombiano ($1.500.000), peso mexicano o euro.
- **Centro de ayuda**: buscador con sugerencias que encuentra preguntas y te lleva directo a cada parte de la app, y 24 preguntas frecuentes por tema.
- **Ver la app como alguien nuevo**: vista previa vacía con bienvenida y recorrido, sin tocar tus datos.
- **Apps plegables** en Inicio: una barra delgada con íconos; la flecha las muestra en grande.
- Arreglo: los gastos fijos semanales y los pagos marcados ya no se corren si cambias el día de inicio de la semana.

### 3.6
- Video tutorial en MP4 (se veía cortado con fondo gris en algunos teléfonos).

### 3.7
- Video tutorial **con voz en español** (acento latino) y música suave de fondo (2:30).
- **Monedas de 20 países** (Colombia, México, Perú, Chile, Argentina, Venezuela, Centroamérica, Rep. Dominicana, Bolivia, Paraguay, Uruguay, Brasil, España, Canadá, Reino Unido…), cada una escrita como se usa allá.
- **Cambio de moneda** (Más): convertidor con tasas incluidas en la app que se actualizan solas con internet.

### 3.8
- Créditos: "Creada por Brandon Poveda · © Todos los derechos reservados" (se mantienen las atribuciones obligatorias de Wiktionary, Tatoeba y pdf.js).
- El código de las pantallas se comprime y revuelve al compilar (tools/protect.mjs); el código legible está en web-src/ (se arma con web-src/build.sh).

### 3.9
- Rotador: **Riesgo y meta del día**. Pones capital, % de riesgo y % de meta (ej. $1,200 · 6% = $72 · 9% = $108). La ventana flotante muestra una barra con el riesgo a la izquierda y la meta a la derecha, cuánto falta o cuánto margen queda, y vibra y se pinta de verde ("META · CIERRA") o rojo ("RIESGO · PARA") al llegar.

### 3.10
- Nueva subapp **Guardián**: ventana flotante independiente que lee tu saldo real del broker en la pantalla cada 2 segundos (sin internet), con riesgo % y meta %. Al tocar la meta o el riesgo, aviso a pantalla completa (verde/rojo, parpadeo, vibración fuerte y alarma). Espera configurable antes del aviso de riesgo para no confundir la apuesta abierta con una pérdida.

### 3.11
- Riesgo y meta salen del panel del Rotador y flotan **aparte**: botón "🛡 Flotar aparte" en el Rotador abre el Guardián con el mismo capital/riesgo/meta, leyendo el saldo real todo el tiempo. La ventanita del Guardián ahora se ve como el recuadro: −riesgo · +meta, barra, "Faltan $X", y el saldo abajo.

### 3.12
- Ventanita del Guardián más pequeña (~30% menos).
