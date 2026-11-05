# ADR-021 — Cliente móvil compartido y adaptadores nativos

**Fecha:** 2026-10-27. **Estado:** Aceptada para la implementación inicial de SPEC-19.
**Responsabilidad:** Integración / Móvil, con adaptación autorizada de Cuentas y Chat.

## Contexto

ADR-015 selecciona Kotlin Multiplatform y Compose Multiplatform. Se necesita una aplicación
Android e iOS que conserve el lenguaje visual de `apps/web` y la autoridad de sesión Core.
Gateway, VOD, seguimiento, replay y Notificaciones publican contratos ejecutables propios. No se deduce su implementación a partir de una pantalla móvil.

## Decisión

`apps/mobile/shared` comparte UI, navegación, DTO generados, Ktor, sesión, reconciliación de chat
y leases. `androidApp` contiene el host Android y `iosApp` el host UIKit/Keychain. El target JVM
sirve para pruebas compartidas; no constituye una tercera aplicación de escritorio.

Se fijan Kotlin 2.3.0, Compose 1.10.0, Ktor 3.3.3, Gradle 8.14.3 y AGP 8.13.2. Android requiere API
26 o posterior y SDK de compilación 36; iOS requiere 17.2 o posterior y Mac arm64 con Xcode.
Media3 1.9.0 reproduce HLS Android; AVPlayer/AVPlayerViewController reproduce iOS. Ambos conservan
controles nativos y solo ofrecen calidades presentes en las pistas/manifiesto recibido.
iOS configura `AVAudioSession` con categoría `Playback` y modo `MoviePlayback` antes de reproducir,
para mantener sonido multimedia también en modo silencio. Activa la sesión al abrir el player y
la desactiva después de pausarlo al salir o pasar a segundo plano, notificando a otras apps.
Si iOS rechaza la activación, el player muestra un error recuperable. El volumen y la ruta de
salida siguen bajo control del usuario; no se habilita audio en segundo plano.
El selector de calidad se sitúa debajo del video. La vista UIKit recibe los eventos directamente
(`NonCooperative`); un reconocedor sobre un contenedor estable limita el cierre por arrastre a la
imagen central en reproducción integrada, conserva los toques nativos y se desactiva en pantalla completa. Una zona de
arrastre inferior ofrece también cierre accesible sin superponer botones a AVKit.
El manifiesto maestro es la opción automática; iOS puede sustituir el item por una variante del
mismo origen. Compartir una librería de reproducción adicional no sustituiría estas integraciones.

Ktor usa OkHttp en Android y Darwin en iOS, confianza TLS de plataforma y ningún logger de cuerpos,
CookieJar o interceptor que reintente comandos. Solo se configura un origen HTTPS público.
El cliente consume los agregados públicos Gateway de exploración, canal, live y VOD; los comandos mantienen las APIs de dominio. No llama listeners privados ni transfiere autorización al Gateway. Los estados parciales se validan antes de usar cada sección.

Los DTO Kotlin y consultas GraphQL derivan de `contracts/p1.json` y `contracts/p2.json` mediante
`contracts/generate_mobile.py`. No se mantiene una definición paralela de sus campos.

La sesión conserva los 256 bits, duración fija de 24 horas, cuotas y revocación de Core.
POST mobile-sessions devuelve `P2NativeLogin` con no-store, sin Set-Cookie; DELETE current revoca
idempotentemente. No hay JWT ni refresh token. Core rechaza Bearer malformado o combinado con
cookie de sesión. La excepción CSRF nativa exige ausencia de **ambos** headers Cookie y Origin;
solo cubre comandos con Bearer válido y las rutas anónimas explícitas de registro/recuperación/login
nativo. Navegador conserva CSRF. Chat valida Bearer antes de Upgrade y contexto vigente por envío;
el navegador conserva su lista de orígenes. Lectura nativa anónima no otorga permiso de escritura.

Android cifra la credencial con AES-GCM y clave no exportable de Android Keystore, en noBackupFilesDir;
iOS usa Keychain WhenUnlockedThisDeviceOnly. No se guardan contraseñas ni credenciales privadas
de servicios. Cada respuesta protegida usa la generación/credencial de su solicitud: un 401 antiguo
no puede cerrar un login posterior. Lecturas públicas no invalidan una sesión.
Un rechazo `INVALID_CREDENTIALS` de login tiene mensaje propio; no se presenta como sesión
caducada. Un 429 conserva en memoria la espera `Retry-After` por identificador y bloquea su
reenvío hasta vencerla, sin sustituir las cuotas de Core. El formulario desactiva autocorrección
y capitalización automática, permite revelar la contraseña y exige confirmación al registrar.
Las contraseñas se transmiten exactamente como se introducen, sin trim ni normalización.

Player, polling, chat y leases pertenecen a la pantalla y al estado foreground. El lease solo se
adquiere cuando hay reproducción efectiva, se renueva y libera al detenerse; un cierre fallido
queda limitado por su TTL de servidor. ACK, secuencia e ID de mensaje conservan deduplicación
tras reconexión. No se anuncian como disponibles contenidos cuya disponibilidad no se verifica.

## Alternativas y consecuencias

Dos UI nativas duplicarían navegación y reglas del consumidor. WebView perdería el objetivo KMP
aceptado y no resolvería de forma equivalente player/credenciales. Se conservan adaptadores
nativos pequeños y pruebas por plataforma; compilar Kotlin compartido no demuestra funcionamiento iOS.

El diseño replica colores, tipografías y marca Vault tv de la web responsive, con navegación inferior
adaptada al teléfono. Seguimiento, bandeja/preferencias, biblioteca/detalle VOD, replay y subtítulos consumen sus
proveedores públicos; el estado de sección se valida antes de leer datos. El player VOD recibe
un permiso corto, sin introducir la sesión de usuario en URLs multimedia. No se generan
tokens push ficticios ni se instala un SDK sin proyecto Firebase/APNs y contrato de instalaciones.

La configuración de certificados se hace en el emulador/simulador o dispositivo físico; no se permiten trust-all ni
excepciones ATS. El host UIKit incluye un proyecto Xcode con firma automática de desarrollo
compatible con Personal Team gratuito y origen HTTPS en xcconfig local ignorado. El modo LAN del
despliegue comparte un origen canónico entre navegador/móvil/WS/medios, conserva la CA y añade
nombre/IP al certificado de entrada. iOS declara el uso de Red local y requiere permiso y confianza
total en esa CA. Esto no habilita APNs ni acredita una prueba física.
El build Android debug admite CA de usuario para el entorno académico. Release
usa confianza de sistema. Las decisiones y provisión pendientes siguen en P2-06/08…13/16/17.

## Referencias

- [Compatibilidad KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html).
- [Compatibilidad Compose](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html).
- [Ktor WebSockets](https://ktor.io/docs/client-websockets.html).
- [Media3 HLS](https://developer.android.com/media/media3/exoplayer/hls).
- [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer).
