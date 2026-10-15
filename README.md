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
