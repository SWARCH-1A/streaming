# Contratos HTTP y WebSocket de P2

La fuente estructural [contracts/p2.json](../contracts/p2.json) hereda schemas de
[p1.json](../contracts/p1.json) y concreta Recuperación (RF-004), Subtítulos VOD y consulta de
calidades live (RF-028). Los tres contratos usan el generador P2 compartido.
Las demás familias del inventario conservan su diseño documental hasta incorporar sus schemas.
[SPEC-10](spec-p2/spec_10_contracts.md) define mantenimiento y validación. El inventario de rutas
describe interfaces; la implementación y las pruebas de cada proveedor se acreditan por separado.
Cada operación tiene auth, timeout, idempotencia, consumidor, errores y SPEC en la fuente.

La consulta `p2.streaming.renditions` tiene una implementación inicial documentada en
[SPEC-04](spec-p2/spec_04_stream.md): 200 con las variantes anunciadas por el medio y sus
playlists con segmentos; 404 para sesión inexistente; 503 si no es reproducible o el medio no
permite obtener metadatos completos; 504 al agotar dos segundos. Éxito y error usan `no-store`.
Esto no acredita la ingesta ni sincronización real de cuatro calidades (P2-08).

## Reglas comunes

IDs opacos; timestamps UTC; enteros interoperables hasta 9007199254740991. JSON ordinario limitado
a 16 KiB. Listas aceptan limit de 1 a 50 y cursor opaco ligado a usuario/filtros. Cursor vencido
se rechaza; no se interpreta como lista vacía. Los recursos binarios tienen sus propios límites.
Los query params se validan contra el schema indicado y se rechazan claves ajenas.

La identidad procede de la sesión Core: cookie/CSRF web o Bearer móvil; las credenciales de servicio
no son credenciales de usuario. En rutas privadas se valida el servicio y su permiso antes del
payload. X-Session-Credential solo existe entre servicios autenticados y nunca se acepta del público.
Si una ruta requiere sesión, una credencial inválida produce 401; una autoridad caída, 503.

Mutaciones requieren Idempotency-Key salvo la semántica base explícita. La clave se liga al
principal, operación y fingerprint: repetir recupera resultado; cambiar payload da conflicto.
expectedVersion o If-Match también protege concurrencia, no sustituye idempotencia. La ventana de
retención debe cubrir los reintentos del consumidor y se documenta por dueño antes de integrar.
Los POST de contexto/destinatarios son lecturas, no comandos. Login no se reintenta automáticamente.

Errores usan `Error` base: code, message, requestId y fieldErrors opcional. 400/422 validación,
401 identidad, 403 permiso, 404 inexistente/no visible, 409 versión/idempotencia, 410 cursor o
contenido expirado/retirado cuando corresponda, 429 cuota, 503 dependencia y 504 plazo agotado.
La respuesta no contiene stack, SQL, secretos ni información privada de otra cuenta.

## Inventario de operaciones nuevas o ampliadas

| ID | Método y ruta | Proveedor | Entrada | Éxito | Autenticación |
| --- | --- | --- | --- | --- | --- |
| `p2.identity.mobile-login` | POST `/api/identity/mobile-sessions` | Core | LoginRequest | 200 P2NativeLogin | public |
| `p2.identity.mobile-logout` | DELETE `/api/identity/mobile-sessions/current` | Core | — | 204 sin cuerpo | opaque-session-if-present |
| `p2.identity.recovery-request` | POST `/api/identity/recovery-requests` | Core | P2RecoveryRequest | 202 P2RecoveryAccepted | public |
| `p2.identity.recovery-confirm` | POST `/api/identity/recoveries/confirm` | Core | P2RecoveryConfirm | 204 sin cuerpo | public |
| `p2.channels.follow` | PUT `/api/channels/{channelId}/followers/me` | Core | — | 200 P2Follow | session |
| `p2.channels.unfollow` | DELETE `/api/channels/{channelId}/followers/me` | Core | — | 200 P2Follow | session |
| `p2.channels.follow-state` | GET `/api/channels/{channelId}/followers/me` | Core | — | 200 P2Follow | session |
| `p2.channels.followed` | GET `/api/me/followed-channels` | Core | —; query P2PageQuery | 200 P2FollowPage | session |
| `p2.channels.recipients` | POST `/internal/core/notification-recipients` | Core | P2RecipientsRequest | 200 P2Recipients | service:notifications |
| `p2.access.context` | POST `/internal/core/access-context` | Core | P2AccessRequest | 200 P2AccessContext | service:limited-operation |
| `p2.recording.preference-get` | GET `/api/channels/{channelId}/vod-settings` | Core | — | 200 P2Autopublish | session |
| `p2.recording.preference-put` | PUT `/api/channels/{channelId}/vod-settings` | Core | P2AutopublishUpdate | 200 P2Autopublish | session |
| `p2.streaming.renditions` | GET `/api/streams/sessions/{sessionId}/renditions` | Streaming | — | 200 P2Renditions | public |
| `p2.vod.list` | GET `/api/vods` | VOD | —; query P2VodQuery | 200 P2VodPage | public |
| `p2.vod.mine` | GET `/api/me/vods` | VOD | —; query P2VodQuery | 200 P2VodPage | session |
| `p2.vod.get` | GET `/api/vods/{vodId}` | VOD | — | 200 P2Vod | public |
| `p2.vod.owner-get` | GET `/api/me/vods/{vodId}` | VOD | — | 200 P2Vod | session |
| `p2.vod.patch` | PATCH `/api/vods/{vodId}` | VOD | P2VodPatch | 200 P2Vod | session |
| `p2.vod.publish` | POST `/api/vods/{vodId}/publication` | VOD | P2VersionCommand | 200 P2Vod | session |
| `p2.vod.delete` | DELETE `/api/vods/{vodId}` | VOD | — | 202 P2Accepted | session |
| `p2.vod.timeline` | GET `/api/vods/{vodId}/timeline` | VOD | — | 200 P2Timeline | public |
| `p2.vod.media` | GET `/vod-media/{vodId}/{assetPath}` | VOD | — | 200 Binary, 206 Binary | public |
| `p2.chat.replay` | GET `/api/chat/vods/{vodId}/messages` | Chat | —; query P2ReplayQuery | 200 P2ReplayPage | public |
| `p2.chat.delete-message` | DELETE `/api/chat/sessions/{sessionId}/messages/{messageId}` | Chat | — | 200 P2ModerationResult | session |
| `p2.chat.block` | PUT `/api/chat/channels/{channelId}/blocks/{userId}` | Chat | P2BlockCommand | 200 P2Block | session |
| `p2.chat.unblock` | DELETE `/api/chat/channels/{channelId}/blocks/{userId}` | Chat | — | 200 P2Block | session |
| `p2.chat.block-get` | GET `/api/chat/channels/{channelId}/blocks/{userId}` | Chat | — | 200 P2Block | session |
| `p2.chat.delete-user-messages` | POST `/api/chat/channels/{channelId}/message-removals` | Chat | P2DeleteMessages | 202 P2ModerationResult | session |
| `p2.notifications.list` | GET `/api/notifications` | Notificaciones | —; query P2PageQuery | 200 P2NotificationPage | session |
| `p2.notifications.read` | PUT `/api/notifications/{notificationId}/read` | Notificaciones | — | 200 P2Notification | session |
| `p2.notifications.preferences-get` | GET `/api/notifications/preferences` | Notificaciones | — | 200 P2Preferences | session |
| `p2.notifications.preferences-put` | PUT `/api/notifications/preferences` | Notificaciones | P2MediumPreferences | 200 P2Preferences | session |
| `p2.notifications.channel-get` | GET `/api/notifications/channels/{channelId}/preference` | Notificaciones | — | 200 P2ChannelPreference | session |
| `p2.notifications.channel-put` | PUT `/api/notifications/channels/{channelId}/preference` | Notificaciones | P2ChannelPreferences | 200 P2ChannelPreference | session |
| `p2.notifications.device-put` | PUT `/api/notifications/devices/{installationId}` | Notificaciones | P2DeviceRegister | 200 P2Device | session |
| `p2.notifications.device-delete` | DELETE `/api/notifications/devices/{installationId}` | Notificaciones | — | 204 sin cuerpo | session |
| `p2.recovery.delivery-claim` | POST `/internal/core/recovery-deliveries/{recoveryId}/claim` | Core | P2RecoveryClaim | 200 P2RecoveryDelivery | service:recovery-email |
| `p2.recovery.delivery-result` | PUT `/internal/core/recovery-deliveries/{recoveryId}/result` | Core | P2DeliveryResult | 204 sin cuerpo | service:recovery-email |
| `p2.gateway.explore` | GET `/api/screens/explore` | Gateway | —; query P2VodQuery | 200 P2ExploreScreen | public |
| `p2.gateway.channel` | GET `/api/screens/channels/{handle}` | Gateway | — | 200 P2ChannelScreen | public |
| `p2.gateway.watch` | GET `/api/screens/watch/{streamId}` | Gateway | — | 200 P2WatchScreen | public |
| `p2.gateway.vod` | GET `/api/screens/vods/{vodId}` | Gateway | — | 200 P2VodScreen | public |
| `p2.admin.users` | GET `/api/admin/users` | Core | —; query P2PageQuery | 200 P2AccountAdminPage | administrator |
| `p2.admin.channels` | GET `/api/admin/channels` | Core | —; query P2PageQuery | 200 P2ChannelAdminPage | administrator |
| `p2.admin.account-status` | PUT `/api/admin/users/{userId}/status` | Core | P2AccountStatusCommand | 200 P2AccountAdmin | administrator |
| `p2.admin.content-removal` | POST `/api/admin/content-removals` | Gateway | P2RemovalCommand | 202 P2Accepted | administrator |
| `p2.chat.history` | GET `/api/chat/sessions/{sessionId}/messages` | Chat | — | 200 P2ChatHistory | public |
| `p2.vod.operation-status` | GET `/api/vods/operations/{operationId}` | VOD | — | 200 P2OperationStatus | session |
| `p2.chat.operation-status` | GET `/api/chat/channels/{channelId}/moderation-operations/{operationId}` | Chat | — | 200 P2OperationStatus | session |
| `p2.streaming.operation-status` | GET `/api/streams/operations/{operationId}` | Streaming | — | 200 P2OperationStatus | session |
| `p2.recording.preference-internal` | GET `/internal/core/channels/{channelId}/vod-settings` | Core | — | 200 P2Autopublish | service:streaming-recording-settings |

Las rutas base no reemplazadas conservan contratos P1 detrás del Gateway. La operación de historial
live se amplía con moderationVersion. La entrega HTTP de eventos a Chat/Discovery se retira; sus
consultas de contexto y reconstrucción continúan síncronas.

## Agregados de pantalla

`P2ChannelScreen`: canal esencial, live, vods y follow. `P2ExploreScreen`: Discovery y VOD.
`P2WatchScreen`: emisión esencial, canal y rendiciones. `P2VodScreen`: VOD esencial, canal y mapping.
Los agregados no contienen una conexión WS, historial completo ni bytes de video.

Una sección contiene una sola alternativa:

```json
{"status":"AVAILABLE","data":{"items":[],"nextCursor":null}}
```

```json
{"status":"UNAVAILABLE","error":{"code":"VOD_UNAVAILABLE","requestId":"req_fixture","retryable":true}}
```

`EMPTY` representa ausencia confirmada; `DENIED`, falta de permiso o identidad para esa sección;
`UNAVAILABLE`, dependencia fallida. AVAILABLE puede incluir una página vacía conforme al tipo;
el cliente no transforma UNAVAILABLE en ausencia. No hay `data` junto al error. `meta.partial`
es true si hay alguna sección UNAVAILABLE. Un seguimiento anónimo DENIED no implica caída.

La inexistencia del recurso esencial da 404; no poder comprobarlo da 503. Un canal suspendido usa
su estado de indisponibilidad y no entrega URLs de live/VOD. Un recurso VOD restringido no se
presenta como reproducible. Los agregados dependen del solicitante y usan Cache-Control: no-store.

## Sesión nativa y recuperación

Login móvil recibe LoginRequest y devuelve accessToken opaco, tokenType=Bearer, userId y expiresAtUtc.
La credencial solo aparece en esa respuesta TLS/no-store y en su transporte autenticado posterior.
Mantiene la misma vida fija de 24 h, expiración y revocación de Core; no hay refresh token nuevo.

La solicitud recibe `{email}` y devuelve 202 con `P2RecoveryAccepted.message` uniforme.
Confirmar recibe `{recoveryToken,newPassword}` y devuelve 204 sin cuerpo. El token opaco de
256 bits es de un uso y vence a los 30 minutos; una solicitud nueva invalida los anteriores.
Core consume token, cambia hash y revoca sesiones en una transacción. Token usado, vencido o
manipulado devuelve 422 RECOVERY_INVALID, sin distinguir la causa. Payload inválido devuelve
400 VALIDATION_ERROR. Ambos endpoints conservan CSRF para navegador y Cache-Control: no-store.

La política [ADR-017](adr/ADR-017-recuperacion-de-credenciales.md) fija tres solicitudes nuevas
por dirección normalizada/hora y diez operaciones por IP/15 minutos, incluyendo confirmaciones.
Direcciones inexistentes consumen las mismas cuotas. Un exceso devuelve 429 RATE_LIMITED con
Retry-After conservador de 3600 segundos para solicitud y 900 para confirmación. Idempotency-Key
UUID conserva el resultado 24 horas; repetir la misma clave con otro contenido da 409.

El job de correo contiene recoveryId/deliveryId. El consumidor de Notificaciones, con la
identidad lógica `recovery-email`, credencial exclusiva y acceso al listener privado, pide
claim con `{deliveryId,attempt}`; el intento va de
1 a 2147483647, monotónico incluso tras redrive; el presupuesto de transporte sigue siendo
cuatro intentos por ciclo operativo (ADR-016). Core valida la pareja autorizada antes de devolver `P2RecoveryDelivery`: identificadores,
intento, recipient, resetUrl y expiresAtUtc. Una pareja desconocida da 404; material vencido,
invalidado o eliminado da 410. La URL se deriva del origen configurado, nunca del Host recibido.

El resultado recibe `{deliveryId,attempt,status}` con DELIVERED, RETRYABLE_FAILURE,
PERMANENT_FAILURE o UNKNOWN. Un intento anterior no sobrescribe uno posterior; el estado terminal
no se reemplaza por un fallo tardío. El siguiente claim requiere el resultado reintentable del
intento anterior. Repetir claim no genera otra recuperación ni extiende el vencimiento. Claim y
result son idempotentes, usan no-store y rechazan campos ajenos. DELIVERED, PERMANENT_FAILURE y
UNKNOWN eliminan el material reversible; el hash de confirmación conserva su vigencia original.
El material temporal se protege con AES-256-GCM según ADR-017 y no aparece en broker ni logs.
El nombre de servicio es un permiso del contrato y no exige un contenedor o runtime específico.
Cuentas implementa estas cuatro operaciones y publica el trabajo; el consumo de la cola,
almacenamiento de intentos, configuración SMTP y envío están implementados en Notificaciones
según ADR-020. La aceptación integrada de RF-004 se acredita por separado: la respuesta 202
confirma la solicitud, sin acreditar entrega de correo.

## Seguimiento y destinatarios

PUT/DELETE operan sobre el usuario de la sesión. No-op conserva versión y no publica otro cambio.
El snapshot privado de destinatarios conserva snapshotId/capturedAtUtc en todas sus páginas y
solo devuelve userId/followVersion, no emails. Cursor ligado a otro canal/snapshot se rechaza.
La preferencia de autopublicación y su propuesta de persistencia Core están en revisión P2-09;
no existen dos autoridades para el mismo valor. Streaming consulta la preferencia por el
endpoint privado limitado al reservar una nueva sesión, sin depender de una cookie del
encoder, y conserva esa copia también tras reconexión. Fallar la consulta no equivale a true.

## VOD, operaciones asíncronas y acceso

Listado público solo contiene grabaciones READY/PUBLISHED/disponibles; el listado propio incluye
borradores y procesamiento. Lectura pública de borrador o eliminado no entrega su objeto. PATCH
requiere campo editable y expectedVersion. Publicar exige medio completo validado y propietario.
DELETE retira visibilidad antes de limpiar: 202 significa limpieza pendiente, no acceso vigente.

`P2Accepted` devuelve operationId, status y statusUri del dueño del trabajo. VOD, Chat y Streaming
ofrecen consulta privada `P2OperationStatus`; Gateway retransmite el statusUri del dueño y no
mantiene una base global de jobs. FAILED no republica contenido ni habilita un recurso retirado.
Errores de consulta de estado no se convierten en COMPLETED.

El medio se sirve por `/vod-media/{vodId}/{assetPath}` con S3 privado y control de disponibilidad.
La autorización actual y el contrato de suspensión de P2-17 cubren manifiestos, segmentos, pistas
y replay. Range/206, MIME y cancelación se preservan; el timeout JSON no corta una reproducción
larga. `assetPath` se resuelve contra el inventario del VOD, sin traversal ni URLs arbitrarias.

## Chat, bloqueos y replay

Bloqueo por canal: TEMPORARY exige expiresAtUtc futuro según servidor; PERMANENT no acepta
vencimiento. El retiro es explícito y no borra mensajes. Límites de duración y selección del
borrado por usuario siguen en P2-11. `P2DeleteMessages` propone SESSION/CHANNEL y corte de secuencia;
está marcado review-required, no es una política de alcance aceptada.

Replay exige fromMs < toMs y limit 1..50. El cursor se liga a vodId, ventana, mappingVersion y
moderación; si el mapping cambió se invalida y se pide una ventana nueva. Los mensajes suprimidos
no vuelven a aparecer. La semántica de huecos del video debe ratificarse en P2-10.

WebSocket mantiene `/realtime/chat/sessions/{sessionId}`. Navegador: cookie y Origin permitido;
nativo: Authorization Bearer en Upgrade, sin token en query ni Origin inventado. Identidades
contradictorias en cookie/Bearer se rechazan. La autenticación del Upgrade no sustituye la
verificación de cada mensaje nuevo mediante contexto Core.

Se conservan ready/status/send/accepted/error de P1. `P2MessageCreated` añade moderationVersion;
`P2ChatHistory` también. `message.deleted` identifica mensaje/secuencia/versión sin texto;
`messages.invalidated` identifica operación y versión para volver a consultar la ventana.
El cliente descarta historia/respuestas anteriores a una invalidación y no restaura tombstones
al recibir eventos atrasados. La recuperación de una ventana se prueba junto con live/replay.

## Notificaciones y administración

Preferencias, leído y dispositivos se obtienen del principal, nunca de un userId aportado por el
cliente. installationId del path y body deben coincidir. Una instalación se vincula a una cuenta;
logout/cambio de usuario retira la asociación anterior. Tokens de proveedor no aparecen en lectura
pública ni logs. La provisión push real permanece en P2-13.

Los tres endpoints Core administrativos están implementados; retirada por Gateway y contexto
privado de acceso continúan pendientes. GET users/channels acepta limit 1…50 (20 por defecto)
y cursor firmado de cinco minutos ligado a actor/listado; claves query ajenas dan 400 y cursor
inválido/vencido da 410 CURSOR_INVALID. La paginación por ID no ofrece snapshot entre páginas.
Todas las respuestas exitosas usan no-store. Visitante: 401 SESSION_REQUIRED; usuario ordinario:
403 ADMIN_REQUIRED. No se exponen email, hashes, credenciales ni motivos en los listados.

PUT account-status exige cookie/CSRF e Idempotency-Key UUID, status ACTIVE/SUSPENDED,
expectedVersion entero 0…9007199254740990 y motivo no blanco de hasta 500 caracteres.
No admite campos adicionales. Responde 404 ACCOUNT_NOT_FOUND, 409 ACCOUNT_VERSION_CONFLICT
o 409 IDEMPOTENCY_KEY_REUSED. La clave se liga al actor; se compara objetivo/estado/versión/motivo
recortado. La repetición devuelve el resultado original y no vuelve a auditar. Una orden sin
cambio real se audita sin incrementar versión ni emitir evento. Auditoría y resultados se
conservan sin limpieza automática. Reactivar no restaura sesiones revocadas.

Las operaciones administrativas requieren rol provisionado y motivo; accountVersion/expectedVersion
resuelven concurrencia. La retirada se enruta al dominio según resourceType. El Gateway no confirma
aceptación si ese dominio no la persistió. Suspensión no borra datos ni republica al reactivar.

## Interfaces aún bloqueadas

Watch parties requiere cerrar P2-01…03. `contracts/p2.json.pendingInterfaces` conserva dependencias reales.
Subtítulos tiene los contratos siguientes, marcados contract-only: no hay proveedor ejecutable.
P2-09/11/17 identifican otras firmas que necesitan ratificación; P2-15 se resolvió en ADR-017; las tablas de diseño por sí solas
no demuestran schemas completos ni endpoints disponibles.

## Subtitulos VOD

SPEC-22 y ADR-018 definen carga por propietario, exclusivamente sobre VOD. Fuente neutral:
`contracts/p2.json.operations` y schemas P2Subtitle*. Cada operación declara proveedor VOD,
consumidores Web/móvil y status=contract-only. El tiempo máximo propuesto es 10 s; el cliente
no transforma un timeout en éxito ni reintenta una mutación con una clave nueva.

| ID | Método y ruta | Entrada | Éxito | Permiso |
| --- | --- | --- | --- | --- |
| p2.subtitles.upload | POST `/api/vods/{vodId}/subtitles` | multipart: metadata y file | 201 P2SubtitleTrack | Propietario activo |
| p2.subtitles.list | GET `/api/vods/{vodId}/subtitles` | P2SubtitleQuery | 200 P2SubtitlePage | Lectura vigente del VOD o propietario activo |
| p2.subtitles.read | GET `/vod-media/{vodId}/subtitles/{trackId}.vtt` | — | 200 bytes UTF-8, text/vtt | Igual comprobación de lectura |
| p2.subtitles.delete | DELETE `/api/vods/{vodId}/subtitles/{trackId}` | If-Match e Idempotency-Key | 204 sin cuerpo | Propietario activo |

IDs de path opacos de 1–128 caracteres `[A-Za-z0-9_-]`; query ajeno se rechaza. En listado limit
1…50 (20 por defecto), cursor firmado/opaco ligado a principal o público, vodId, filtro de
disponibilidad, mapping y revisión del listado. Orden por trackId. Vencido/cambiado: 410;
el cliente vuelve a la primera página. Cursores duran 5 min y no eluden revocación de permisos.
No se mezclan VOD ni IDs repetidos en una página. Se devuelven únicamente pistas READY vigentes.

Multipart contiene exactamente una parte metadata (application/json, ≤16 KiB) y una parte file
(text/vtt, ≤1 MiB). Cuerpo completo ≤1 114 112 bytes para acotar también delimitadores/cabeceras.
Nombre de archivo no determina identidad, idioma, ruta ni MIME de respuesta. Metadata estricta:
language, label y expectedMappingVersion; no acepta propietario, URL, duración ni ruta S3.
VOD obtiene duración/mapping/propiedad autoritativos; valida el perfil y confirma el objeto
antes de asociarlo, revalidando permiso y mapping al confirmar. Una carrera con retirada o
suspensión no publica una pista nueva. Un objeto huérfano queda para limpieza recuperable.

Cada carga crea trackId inmutable y trackVersion=1; el contentPath coincide exactamente con
vodId/trackId y se resuelve contra el inventario. No se almacenan links arbitrarios del cliente.
Retirar requiere `If-Match: "1"` (versión vigente) y conserva tombstone con versión incrementada.
Precondición ausente: 428; versión diferente: 412. La retirada deshabilita lectura/listado antes
de devolver 204. La limpieza de objetos es posterior, durable e idempotente; el proveedor debe
exponer su estado operativo al integrar. Una respuesta 204 no afirma que S3 ya borró los bytes.

Mutaciones requieren Idempotency-Key UUID y `X-XSRF-TOKEN` para cookie. El resultado se liga al actor,
operación, VOD, metadata y hash SHA-256 de los bytes originales; DELETE incluye trackId/If-Match.
Repetir el mismo comando devuelve su resultado original sin duplicar pistas ni retiradas;
cambiarlo con la misma clave da 409 IDEMPOTENCY_KEY_REUSED. Se conserva al menos 24 h, plazo
que debe ratificarse con consumidores. Revalidar identidad/acceso antes de devolver resultados
guardados. Retención de idempotencia no concede acceso a bytes retirados. Tras timeout de carga,
el cliente conserva clave y archivo para reintentar; pasado el plazo, consulta antes de otra carga.

Errores Error base: 400 estructura, 401 sesión inválida, 403 no propietario, 404 no visible o
inexistente, 409 mapping cambiado/idempotencia, 410 cursor inválido o pista retirada accesible
al propietario, 413 tamaño, 415 MIME, 422 perfil/timing (fieldErrors.file), 429 cuota y 503/504
dependencia/plazo. El reader devuelve errores JSON, no un WebVTT vacío que parezca éxito.
No se filtra contenido privado: para visitantes, borrador/restringido/retirado es 404.

Todas estas respuestas usan Cache-Control: no-store; reader añade Content-Type: text/vtt y
X-Content-Type-Options: nosniff. No necesita Range: devuelve la pista completa acotada. Cada
petición revalida acceso y mapping; P2-17 fijará cómo se obtiene autorización vigente y su plazo.
Las URLs no van directamente a S3. Esto no revoca texto previamente descargado por el usuario.
