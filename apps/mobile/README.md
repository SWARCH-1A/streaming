# Vault tv mobile

Cliente Android/iOS de [SPEC-19](../../docs/spec-p2/spec_19_mobile.md), con UI Compose compartida
y adaptadores nativos. La referencia visual es `apps/web` en viewport móvil: colores Vault,
marca original, Inter y Plus Jakarta Sans. Las fuentes proceden de Google Fonts y conservan
sus licencias OFL en `shared/src/commonMain/composeResources/font`.

## Cobertura

Registro, login/logout, recuperación por solicitud/confirmación, perfil de consulta, exploración,
búsqueda de canales/live, filtros, paginación, canal, reproducción live, calidad y chat live consumen
APIs públicas. La recuperación acepta el token manualmente. SMTP local ya entrega el enlace Web;
los enlaces universales de recuperación en móvil aún requieren integración. No se transmite el token en una URL API.
El registro confirma la contraseña y permite mostrarla; los teclados de credenciales desactivan
autocorrección y capitalización. Login distingue credenciales rechazadas de una sesión caducada;
un 429 muestra cuenta atrás y respeta `Retry-After` antes de reenviar ese identificador. Volver
a la pantalla conserva esa espera en memoria; las cuotas y el bloqueo efectivo siguen en Core.

La navegación incluye Siguiendo, Bandeja, preferencias generales/por canal y biblioteca VOD.
La biblioteca muestra tarjetas con miniaturas reales y duración, búsqueda y filtros horizontales.
Las miniaturas se solicitan al entrar en pantalla y conservan un estado alternativo si fallan;
ese fallo no impide abrir el video. La cabecera mantiene la marca sin desplazarla con «Volver».
Live/VOD se cierran deslizando el video hacia abajo; la franja inferior también permite ese gesto
y ofrece una acción accesible de cierre. iOS usa audio multimedia (`Playback`/`MoviePlayback`),
también con el modo silencio activo, y libera la sesión al salir o pasar a segundo plano.
No cambia el volumen ni fuerza la ruta de salida. Calidad queda debajo, sin tapar los controles nativos
de volumen, transporte y pantalla completa de iOS. En VOD el título queda debajo del video
y no se vuelve a aplicar el margen de la barra de estado. iOS usa las variantes del maestro VOD
ya autorizado y admite sus rutas relativas al origen dentro de la misma grabación.
Exploración, biblioteca y detalle VOD consumen los agregados públicos del Gateway. El reproductor
nativo usa permisos multimedia cortos, replay sobre el timeline y pistas WebVTT autorizadas.
Los destinos de notificaciones se validan antes de navegar. No hay Firebase/APNs configurado;
el registro y la entrega push reales siguen pendientes de provisión e implementación de adaptadores.
La [guía de provisión push](PUSH.md) especifica los archivos privados y la aceptación necesaria.

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
Las lecturas compuestas usan Gateway; los comandos conservan las APIs públicas de su dominio.

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

Para instalar en un **iPhone físico con cuenta gratuita de Apple** y usar este Mac como host
de contenedores, seguir la [guía de dispositivo, firma automática y red local](iosApp/DEVICE.md).
Abrir `iosApp/VaultTV.xcodeproj`, seleccionar Personal Team e iPhone, configurar el origen HTTPS
en el archivo ignorado `iosApp/Config.local.xcconfig` y ejecutar. El host incluye la explicación
del permiso Red local; se instala y confía en la CA pública en el teléfono.

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
Para instalar, se recomienda el proyecto Xcode y su firma/provisioning automáticos; la compilación
directa sin firma no se instala en el teléfono. Un dispositivo físico no llega al Mac mediante localhost.
La compilación de simulador no acredita instalación física ni APNs. No hay excepciones ATS.

El proyecto Xcode se verificó el 2026-11-03 para simulador y arm64 físico, con firma de desarrollo
e instalación mediante Personal Team en un iPhone 15 Pro con iOS 26.7.2.
En iOS 27 simulado se instaló la app, se confió en la CA y se cargó Exploración contra el origen
HTTPS LAN del Mac, con catálogo real. Los recorridos audiovisuales del dispositivo físico
requieren verificación propia; la compilación/instalación y la prueba de simulador no los acreditan.

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
| iOS 27.0, iPhone 18 Pro simulado arm64 | Framework Kotlin y host UIKit compilados, firmados ad hoc e instalados | Exploración, enlace validado a live y primer frame HLS con AVPlayer; lease reflejado en el conteo. Ver ampliación VOD del 2026-11-03 debajo | Login/Keychain y chat desde UI, pausa/retorno, accesibilidad y dispositivo físico |
| iPhone 15 Pro, iOS 26.7.2 | Framework y host UIKit compilados, firmados con Personal Team e instalados el 2026-11-03 | Instalación de desarrollo comprobada; recorridos audiovisuales pendientes de verificación propia | Recorrido audiovisual, accesibilidad y push |

Verificación adicional del 2026-11-03: Android arm64 API 37 con backend HTTPS real acredita
login y restauración tras reiniciar, búsqueda/canal, seguir/dejar de seguir, lista Siguiendo,
bandeja y destino validado, HLS con frame decodificado, chat con ACK, lease 1→0 al pasar a segundo
plano y 0→1 al volver, salida y logout persistente. VOD real reproduce video, seis mensajes de
replay con retirada moderada aplicada y pista «Español móvil» seleccionada/desactivada sin
reiniciar video. `tests/integration/p2-mobile/run.py` reproduce el recorrido live con fixtures
explícitamente ficticios y un emulador dedicado.

En iOS 27.0 simulado, el 2026-11-03 se verificaron biblioteca VOD con miniaturas JPEG derivadas de
los videos reales del servidor LAN, entrada al player sin cabecera/margen superior duplicado,
menú 720/540/360/180p, reproducción al elegir 360p y regreso a Automática, y distribución de video
y replay en horizontal. Se verificaron además entrada/salida de pantalla completa nativa,
visibilidad de volumen sin solapamientos, cierre por arrastre de la imagen y de la franja inferior,
y permanencia en reproducción ante un arrastre corto. La salida de audio y el volumen físico del
iPhone requieren comprobación en el dispositivo. Las pruebas VOD aisladas comprueban JPEG decodificable, integridad del
segmento, caché acotada y denegación de miniaturas de borradores/suspendidos, incluso con caché
caliente. Esta evidencia de simulador no acredita el recorrido audiovisual del iPhone físico.

Las 23 pruebas compartidas y el APK Android pasan después del ajuste de paginación de replay.
La compilación del host iOS acredita compilación; los nuevos recorridos comunitarios/VOD no se
han ejecutado en iOS. La matriz de octubre conserva su fecha y no acredita push, dispositivo
físico ni accesibilidad humana. Core y contratos tienen sus suites separadas.

Las pruebas compartidas corren con `./gradlew :shared:desktopTest`; cubren generación de sesión,
401 antiguos, persistencia, serialización canónica, ausencia de Cookie/Origin, restricciones de URLs,
normalización Unicode, orden, huecos y supresiones del chat. Core y Chat tienen suites propias para
Bearer/revocación/CSRF/Upgrade. También se prueban la recuperación del lease expirado sin perder
el login, la reconexión tras timeout de `chat.ready` y la cancelación sin reintentos.
Ningún mock acredita HLS, push ni operación offline en dispositivo.

Para aceptación con backend real: registro/login → buscar canal → live → reproducir → calidad →
chat; pausar/reanudar, rotar, cambiar pantalla y perder red; cerrar sesión y comprobar que la misma
credencial ya no autoriza. Verificar primer frame y leases mediante Streaming, sin guardar tokens,
logs de cuerpos o claves de emisión. Follow/bandeja/VOD ya tienen consumidor; CA-01/04/06/07 requieren la aceptación integrada
en dispositivo y el registro/entrega push con proveedores provisionados. Las pruebas manuales de accesibilidad y escalado de texto siguen siendo necesarias.

En API 37 también se verificó el VOD real de cuatro calidades (720/540/360/180p), automática y
seek de 4 a 56 s con reproducción a 58 s. Si el emulador 36.5.10/API 37 muestra rayas horizontales
con el decoder Goldfish, arrancar el AVD dedicado con `-feature -HardwareDecoder`: usa el decoder
software del sistema, sin cambiar la app ni los archivos. Se verificaron los cuatro frames en
ese modo. Esta prueba no sustituye la matriz de dispositivos físicos/iOS.
