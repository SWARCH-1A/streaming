# Vault tv mobile

Cliente Android/iOS de [SPEC-19](../../docs/spec-p2/spec_19_mobile.md), con UI Compose compartida
y adaptadores nativos. La referencia visual es `apps/web` en viewport móvil: colores Vault,
marca original, Inter y Plus Jakarta Sans. Las fuentes proceden de Google Fonts y conservan
sus licencias OFL en `shared/src/commonMain/composeResources/font`.

## Cobertura

Registro, login/logout, recuperación por solicitud/confirmación, perfil de consulta, exploración,
búsqueda de canales/live, filtros, paginación, canal, reproducción live, calidad y chat live consumen
APIs públicas. La recuperación acepta el token manualmente: la entrega SMTP y los enlaces
universales de la instalación aún requieren integración. No se transmite el token en una URL API.

La navegación incluye Siguiendo y Bandeja con indisponibilidad explícita. Los proveedores de
seguimiento y bandeja/preferencias llegaron desde develop; su consumo móvil aún requiere integración.
Core acepta Bearer para seguimiento y configuración VOD. VOD/replay, agregados Gateway,
registro push y destinos de notificación siguen sujetos a SPEC-03/05/14/16/20/22.
Estas funciones móviles no están aceptadas ni simuladas. No hay Firebase/APNs configurado.
Ver la matriz de dependencias en SPEC-19 antes de interpretar una compilación como entrega completa P2.

## Preparar

Mac arm64 para iOS 17.2 o posterior; JDK 21+, Android SDK 36 y Xcode con simulador iOS instalado. El wrapper fija
Gradle y su checksum. Abrir esta carpeta en Android Studio o ejecutar los comandos desde aquí.
Crear `local.properties` ignorado con `sdk.dir=/ruta/al/Android/sdk`, o configurar `ANDROID_HOME`.

Desde la raíz del repositorio, generar/verificar contratos:

```sh
python3 contracts/generate_mobile.py --write
python3 contracts/generate_mobile.py
```

Arrancar el backend actual con `python3 infra/local/dev.py build` y `up` desde la raíz. Su entrada
pública es `https://localhost:3445`. Mobile requiere TLS válido; nunca se desactiva su verificación.
El cliente usa las rutas públicas base mientras Gateway no entregue agregados ejecutables.

## Android

```sh
./gradlew :shared:desktopTest :androidApp:assembleDebug
adb reverse tcp:3445 tcp:3445
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n dev.swarch.vault/streaming.mobile.android.MainActivity
```

`adb reverse` permite que localhost en el emulador llegue al mismo origen del Mac, cuyo certificado
incluye localhost. Instalar **solo la CA pública** `infra/p1/.state-local/ca.crt` en el emulador como CA
de usuario desde Ajustes → Seguridad → Cifrado y credenciales → Instalar certificado CA; se puede
copiar con `adb push`. Debug confía en CA de sistema/usuario; release solo en sistema. No copiar claves
privadas ni añadir certificados al repositorio. `-PvaultOrigin=https://servidor.example` cambia el origen
al compilar; debe coincidir con el certificado y no contener rutas ni credenciales.

La sesión se cifra con Android Keystore y se excluye de backups. Rotación mantiene el host; segundo
plano detiene reproducción, polling, socket y lease. Los controles del player ofrecen pistas reales.

La vista de live ocupa la pantalla sin las barras generales de navegación. Video y chat permanecen
fijos; solo los mensajes tienen scroll. El campo de envío queda anclado abajo. Al consultar mensajes
anteriores se pausa el seguimiento automático y aparece «Últimos mensajes» para recuperarlo.
En horizontal se usan dos columnas; con el teclado abierto se oculta la franja de metadatos.

## iOS

```sh
python3 iosApp/build.py --origin https://localhost:3445
xcrun simctl boot <UDID>
xcrun simctl keychain <UDID> add-root-cert ../../infra/p1/.state-local/ca.crt
xcrun simctl install <UDID> build/ios/iphonesimulator/VaultTV.app
xcrun simctl launch <UDID> dev.swarch.vault
```

El script compila el framework Kotlin, enlaza el host UIKit con el SDK de Xcode, copia los recursos
Compose y firma ad hoc el bundle del simulador. No necesita CocoaPods ni XcodeGen. Keychain conserva
la sesión con protección WhenUnlockedThisDeviceOnly; AVPlayer ofrece controles y calidades reales.

`python3 iosApp/build.py --device --origin https://servidor.example` compila arm64 para iPhone físico.
Su instalación requiere identidad Apple, provisioning profile y firma apropiados; el script admite
`--sign-identity` y `--provisioning-profile`. Un dispositivo físico no llega al Mac mediante localhost.
La compilación de simulador no acredita instalación física ni APNs. No hay excepciones ATS.

Los enlaces `vault://watch/<streamId>` y `vault://channel/<handle>` pasan por el validador
compartido y consultan disponibilidad al servidor. No aceptan tokens, queries ni destinos externos.
El build debug permite probar el mismo destino en simulador sin el diálogo del sistema:

```sh
xcrun simctl launch --terminate-running-process <UDID> dev.swarch.vault --content-link 'vault://watch/ID_PUBLICO'
```

Esto valida navegación por enlace; la entrega push aún requiere su proveedor.

## Verificación

Cobertura ejecutada el 2026-10-27 con backend local HTTPS y una emisión de prueba:

| Plataforma | Compilación e instalación | Comprobado con backend real | Pendiente |
| --- | --- | --- | --- |
| Android, emulador arm64 API 31 | APK debug instalado; SDK de compilación 36 | Exploración, login, restauración de sesión, primer frame HLS, chat con ACK y mensaje persistido; segundo plano reduce el conteo de 2 a 1 manteniendo el espectador iOS | Multicalidad, recorrido completo de recuperación, revocación cruzada en UI, pérdida de red, accesibilidad y dispositivo físico |
| iOS 27.0, iPhone 18 Pro simulado arm64 | Framework Kotlin y host UIKit compilados, firmados ad hoc e instalados | Exploración, enlace validado a live y primer frame HLS con AVPlayer; lease reflejado en el conteo | Login/Keychain y chat desde UI, pausa/retorno, selección de calidad, accesibilidad y dispositivo físico |
| iOS arm64 físico | Compilación Kotlin del target | Sin instalación ni prueba física | Host firmado/provisioning, instalación y recorrido real |

Las 14 pruebas compartidas pasan. Core pasa sus pruebas unitarias y 202 pruebas de integración;
Chat pasa `go test -race ./...`. Los generadores y 37 pruebas de contratos también pasan.
Estos resultados no acreditan VOD/replay, follow, bandeja, push ni entrega completa de SPEC-19.

Las pruebas compartidas corren con `./gradlew :shared:desktopTest`; cubren generación de sesión,
401 antiguos, persistencia, serialización canónica, ausencia de Cookie/Origin, restricciones de URLs,
normalización Unicode, orden, huecos y supresiones del chat. Core y Chat tienen suites propias para
Bearer/revocación/CSRF/Upgrade. Ningún mock acredita HLS, push ni operación offline en dispositivo.

Para aceptación con backend real: registro/login → buscar canal → live → reproducir → calidad →
chat; pausar/reanudar, rotar, cambiar pantalla y perder red; cerrar sesión y comprobar que la misma
credencial ya no autoriza. Verificar primer frame y leases mediante Streaming, sin guardar tokens,
logs de cuerpos o claves de emisión. Follow/bandeja/VOD/push esperan sus proveedores antes de cerrar
CA-01/04/06/07. Las pruebas manuales de accesibilidad y escalado de texto siguen siendo necesarias.
