# Control Financiero

App Android (WebView) del control financiero semanal de Bran: ingresos, deudas,
gastos fijos y gastos hormiga, con proyección de las próximas semanas.

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
