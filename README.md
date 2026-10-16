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
