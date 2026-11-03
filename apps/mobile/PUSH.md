# Provisión de push para el prototipo local

El equipo todavía no ha creado Firebase/APNs. Bandeja, preferencias y correo funcionan
independientemente. El código actual no registra tokens ficticios: el proveedor backend es
`unconfiguredPush` y produce un fallo permanente observable; aún faltan los adaptadores
FCM/APNs y su integración nativa. Proveer los archivos no activa push por sí solo.

## Preparar las cuentas y archivos

1. Crear un proyecto Firebase del equipo y registrar Android con applicationId
   `dev.swarch.vault`. Descargar `google-services.json` y guardarlo en un directorio privado
   fuera de Git. Para la prueba usar dispositivo/emulador con Google Play Services.
   [Guía Android](https://firebase.google.com/docs/cloud-messaging/android/get-started).
2. Registrar la aplicación Apple con bundle ID `dev.swarch.vault`. Preparar Apple Developer,
   App ID con Push Notifications, firma y provisioning del dispositivo. Descargar
   `GoogleService-Info.plist`; cargar la clave APNs en la configuración Firebase correspondiente.
   Conservar `.p8`, Key ID y Team ID en almacenamiento privado del equipo, nunca en el cliente
   o repositorio. [Guía Apple](https://firebase.google.com/docs/cloud-messaging/ios/get-started).
3. Preparar la identidad del servidor con permiso de envío FCM HTTP v1, conservando su archivo
   de credenciales solo en el servidor y fuera de Git. El cliente no necesita esa clave.
   [Envío HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

Comunicar únicamente las rutas locales de estos archivos y el project ID; no pegar claves,
tokens de dispositivo ni contenidos de archivos en chats o issues. Al compartir un backup,
recordar que el archivo privado contiene secretos de servidor.

## Integración que falta

El adaptador backend debe implementar `PushProvider`, distinguir envío temporal/permanente y
`INVALID_TOKEN`, e inyectarse por configuración sin cambiar las intenciones durables por medio.
Android/iOS deben inicializar su proveedor, pedir permiso cuando corresponda, registrar el token
real con una installationId estable y asociarlo al usuario autenticado mediante la API pública.
Rotación, logout y cambio de cuenta deben retirar la asociación anterior; no confundir el token
del proveedor con la sesión Core ni enviarlo en URLs/logs. El build iOS actual es UIKit/Kotlin
sin CocoaPods: incorporar el SDK exige ampliar y probar su enlace/firma, no solo copiar el plist.

## Aceptación real

Con servidor local por HTTPS y el dispositivo configurado: seguir un canal, activar sus avisos,
empezar una sesión reproducible y comprobar bandeja, SMTP y push como intentos independientes.
Comprobar permisos denegados, foreground/background, navegación al destino validado, rotación,
logout/cambio de cuenta, token inválido y contenido suspendido. Repetir reconexión de la misma
sesión sin un segundo aviso lógico. Registrar plataforma/dispositivo probado; compilar iOS o
inyectar un token de prueba no acredita entrega APNs.
