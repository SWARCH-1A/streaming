# SPEC-19 — Aplicación KMP y Compose Multiplatform

**Prioridad:** P2. **Responsable:** Móvil. **Estado:** cliente inicial implementado en `apps/mobile`; aceptación integral pendiente de proveedores P2 y pruebas multimedia.
**RF:** RF-001…006, 010…016, 023…025, 028…035, 052…055, 058…059, 062, 064…065, 070…075, 082. **RNF:** RNF-002, 007, 012…014, 019…020, 023, 025…032, 034, 038, 046…047.

## 1. Contexto y problema

La segunda presentación permite consumir la plataforma desde el teléfono con el mismo modelo
de identidad, contenido y comunicación que la web.

## 2. Definición del componente

Aplicación Kotlin Multiplatform con Compose Multiplatform. Comparte lógica de presentación,
clientes y modelos; integra reproductor HLS, almacenamiento seguro, WebSocket y push mediante
adaptadores de plataforma. Esta entrega incluye Android e iOS; cada plataforma requiere compilación, instalación y pruebas propias.

## 3. Historia de usuario

Como espectador, quiero descubrir canales, ver live/VOD, escribir en chat, seguir canales y
recibir avisos desde mi teléfono.

## 4. Alcance

Registro/login/logout/recuperación, descubrimiento, canal, live/VOD, selección de calidad,
chat/replay, seguimiento, bandeja, preferencias y registro push. Emisión desde cámara,
monetización y paneles completos de creador/admin no forman parte del cliente móvil obligatorio.
Watch parties/subtítulos se integran tras sus políticas y contratos.

## 5. Requisitos funcionales

- Consumir agregados del Gateway y APIs de comandos, sin endpoints internos ni base por servicio.
- Guardar sesión opaca en almacenamiento seguro nativo; Bearer solo por TLS.
- Verificar login/expiración/revocación comunes y WS con Authorization en Upgrade.
- Mostrar estados de conexión, errores parciales, permisos y disponibilidad del contenido.
- Manejar pausa/retorno, rotación y cambio de pantalla sin duplicar reproducción o chat.

## 6. Criterios de aceptación

- [ ] CA-01: instalación Android reproducible y recorrido registro/login → canal → live → follow → bandeja.
- [ ] CA-02: logout elimina credencial local y revoca Core; token expirado/suspendido no permite mensajes ni comandos.
- [ ] CA-03: WS nativo autentica sin Origin ficticio y sin debilitar validación de navegador.
- [ ] CA-04: HLS real muestra calidades recibidas, selección automática/manual y VOD con seek/replay.
- [ ] CA-05: pausa/retorno y destrucción de pantalla cierran o recuperan player, lease y WS según estado; no duplican conteos.
- [ ] CA-06: preferencias y registro/rotación/eliminación de token push se asocian al usuario correcto; denegar permiso no rompe bandeja.
- [ ] CA-07: una notificación abre un destino validado; contenido suspendido/retirado muestra indisponibilidad.
- [x] CA-08: se entrega matriz de dispositivo/emulador y plataforma compilada/probada; no se atribuye cobertura iOS por compartir Kotlin. Ver cobertura y pendientes en `apps/mobile/README.md`.

La UI toma `apps/web` responsive como referencia: marca Vault tv, fondo oscuro, Inter, Plus Jakarta
Sans, tarjetas y estados de disponibilidad. La navegación inferior adapta Explorar, Buscar,
Siguiendo y Bandeja al teléfono; login y canal conservan el shell, y live usa una vista dedicada. Los errores
no muestran excepciones técnicas ni secretos al usuario.

Al abrir un live se ocultan las barras generales: el video ocupa todo el ancho de la parte superior,
con volver y calidad superpuestos. Debajo, una franja compacta muestra canal, título, categoría y
espectadores. El chat ocupa el alto restante, con su compositor fijo abajo; solo se desplazan los
mensajes. Desplazarse hacia el historial pausa el seguimiento automático y permite volver a los
últimos mensajes. El teclado conserva el video y el compositor visibles y oculta los metadatos
secundarios. En horizontal, video y chat ocupan columnas fijas contiguas. Controles y campo de envío
usan alineación, márgenes y radios coherentes, sin tarjetas anidadas.

## 7. Diseño técnico y datos

[ADR-020](../adr/ADR-020-cliente-mobile-nativo.md) fija módulos, versiones y adaptadores.
Módulos compartidos: DTO/validación de contratos, cliente Gateway, estado de sesión, navegación,
modelos de pantalla y reconciliación de chat. Adaptadores: player/HLS, almacén seguro, ciclo de
vida, WebSocket si lo exige la librería, permisos y proveedor push.

`P2NativeLogin` conserva vida fija de 24 h y no introduce refresh token. Un 401 vigente elimina
la sesión; los errores de lectura pública y respuestas de intentos cancelados no deben revocar
un login posterior. Nunca persistir stream keys ni tokens en logs, preferencias ordinarias o
URLs. El token del proveedor push no es un token de usuario.

El contrato de instalación vincula installationId estable, plataforma y providerToken al usuario
actual. Logout desregistra esa asociación; reenviar login con otra cuenta no deja avisos de la
anterior. Offline muestra error/reintento, sin fingir reproducción disponible.

## 8. Dependencias y contratos de integración

SPEC-01 define login nativo; SPEC-16, agregados; SPEC-05, WS/replay; SPEC-20, dispositivo/push.
Firmas en `contracts/p2.json.operations` y `.websocket`. HLS y leases conservan semántica de
Streaming; subtítulos se integran con SPEC-22 cuando se cierre su formato.

El consumidor inicial usa exclusivamente las APIs públicas P1 del proxy HTTPS mientras se
implementa Gateway; todavía no acredita el criterio de agregados P2. No se llama a listeners
internos. Los DTO derivan de contratos canónicos con `contracts/generate_mobile.py`.

| Proveedor | Entrega requerida para cerrar el recorrido | Estado del consumidor móvil |
| --- | --- | --- |
| Cuentas / SPEC-01 | `P2NativeLogin`, logout y Bearer con revocación común | Implementado en Core y cliente; pruebas de transporte/revocación |
| Gateway / SPEC-16, STREAMING-26 | Agregados por pantalla, schemas, errores parciales y proxy WS/HLS | Adaptación a agregados pendiente; APIs públicas base operativas |
| Canales / SPEC-03, STREAMING-2 | Follow/unfollow, estado personal, listado paginado y acceso suspendido | Pantallas muestran dependencia pendiente; no hay follow simulado |
| VOD / SPEC-14, STREAMING-25 | Biblioteca/detalle/disponibilidad, manifiestos y mapping autorizados | Biblioteca y playback VOD pendientes |
| Chat / SPEC-05, STREAMING-5 | Replay durable, ventanas/cursores, supresiones versionadas | Chat live implementado; replay pendiente |
| Streaming / SPEC-04, STREAMING-4 | Maestro multicalidad, hasta cuatro rendiciones, continuidad y disponibilidad | Adaptadores Media3/AVPlayer y leases; aceptación multicalidad pendiente de fuente real |
| Notificaciones / SPEC-20, STREAMING-30 | Bandeja/preferencias, instalación/token, destino validado, FCM/APNs y SMTP | Sin proyecto Firebase ni certificados APNs; sin registro o entrega push |
| Subtítulos / SPEC-22, STREAMING-32 | VOD autorizado y pistas WebVTT ratificadas | Pendiente de VOD y reproducción integrada |

Los IDs STREAMING identifican las dependencias existentes en Plane; esta matriz no cambia
asignaciones ni declara terminadas esas SPEC. No se eligen tecnologías de los proveedores desde móvil.

## 9. Decisiones y preguntas abiertas

KMP + Compose, librerías, módulos y adaptadores quedan definidos en ADR-020. P2-13 requiere proveedor/certificados/dispositivos push; P2-14 exige
verificar Core y Chat con Bearer. iOS requiere Mac arm64/Xcode y pruebas separadas; compartir Kotlin no acredita esa plataforma.

## 10. Verificación

Build, instalación, certificados y pruebas se reproducen desde [apps/mobile/README.md](../../apps/mobile/README.md).
Las suites compartidas verifican sesión/serialización/URLs/chat; Core y Chat verifican Bearer, CSRF
y revocación. Esas suites no cierran los criterios de HLS/push/dispositivo.

Ejecutar recorrido en Android e iOS con backend real, revocación desde web, reconexión WS, cambio de
calidad y seek. Probar pérdida de red, app en segundo plano, cambio de cuenta y permiso push
rechazado. Documentar build/install y evidencia separada por plataforma.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L; KMP es nuevo para el equipo. Reproductor y push pueden requerir trabajo nativo.
Compartir UI no elimina esas integraciones ni los requisitos de compilación de iOS.
