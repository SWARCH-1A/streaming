# Integración entre dominios — P1

**Estado:** contratos funcionales transversales para P1. Los transports/brokers concretos son propuestas
hasta que cada owner registre su ADR. Este documento y los hijos `SPEC-10` a `SPEC-13` se mantienen junto a
los SDD funcionales; la implementación nunca accede a tablas privadas de otro dominio.

## Responsabilidad y fuente de verdad

| Dato/capacidad | Dueño autoritativo | Consumidores P1 |
| --- | --- | --- |
| Cuenta, email, handle canónico, credenciales, sesión/principal | Identity | Profile, Channels, Streaming, Chat |
| Nombre visible, bio, avatar | Profile | Channels, Chat snapshot, Discovery |
| Canal, ownerUserId, descripción, portada | Channels. Identity resuelve la ruta canónica por handle. | shell, Discovery, Streaming |
| Sesión, reproducción, estado, título, categoría/tags asociados, viewers | Streaming | Channels, Chat, Discovery, shell/player |
| Sala/mensaje/evento/orden/offset | Chat | shell/chat UI; futuro consumidor VOD |
| Definiciones de categoría y tag | Taxonomy | Streaming, Discovery, shell |
| Índice y ranking de consulta | Discovery | shell |
| Rutas, montaje frontend, reverse proxy y despliegue conjunto | Integración | todos los módulos |

El proveedor valida sus invariantes antes de confirmar una operación. Consumidores reciben solo
campos públicos/necesarios y enlazan por IDs opacos. Prohibido importar modelos ORM o consultar base
de datos de otro dominio.

## Flujo A — registro y canal inicial

```mermaid
sequenceDiagram
  actor U as Usuario
  participant W as Web shell
  participant I as Identity
  participant C as Channels
  U->>W: enviar email, handle y contraseña
  W->>I: POST registration + Idempotency-Key
    I->>I: validar unicidad, reservar handle y crear PENDING + pendingUntilUtc
  I->>C: POST /internal/channels/provision (registrationId, pendingUntilUtc, HTTPS/TLS, servicio autenticado)
  alt Channels confirma y PENDING→ACTIVE se confirma antes de pendingUntilUtc
    C-->>I: channelId
    I->>I: CAS PENDING→ACTIVE; emitir IdentityPublicChanged
    I-->>W: 201 ACTIVE + userId + channelId (sin sesión)
    W-->>U: confirmar registro y abrir login
  else vence el plazo antes de completar la activación
    I->>I: EXPIRED terminal; liberar email/handle
    I->>C: GET /internal/channels/provisions/{registrationId}
    C-->>I: PROVISIONED + channelId o ABSENT terminal
    opt PROVISIONED después de EXPIRED
      I->>C: DELETE /internal/channels/provisions/{registrationId}
      C-->>I: 204 (borrado o ya ausente)
    end
    I-->>W: 410 REGISTRATION_EXPIRED (sin sesión ni lookup público)
  else timeout/error de Channels antes del vencimiento
    I-->>W: 202 PENDING + registrationId (sin sesión)
    W->>I: GET/repetir misma operación con la misma clave
    I->>C: reintentar provisión idempotente
  end
```

Registro exitoso requiere un canal asociado. Solo se reporta ACTIVE si Channels confirma exactamente un canal por `ownerUserId` y la transición atómica PENDING→ACTIVE ocurre estrictamente antes de `pendingUntilUtc = pendingSinceUtc + 24 h`. En el instante `now >= pendingUntilUtc`, EXPIRED es terminal; GET y POST con la misma clave devuelven `410 REGISTRATION_EXPIRED`, se liberan email/handle y un registro nuevo necesita clave UUID nueva. El registro nunca inicia sesión: tras `201 ACTIVE`, o tras recuperar `200 ACTIVE` desde PENDING, el usuario hace login normal. Si falla Channels antes del deadline, Identity devuelve `202 PENDING` sin sesión ni exposición pública; la misma clave consulta/reintenta. Si se pierde la respuesta de Channels, Identity consulta por registrationId. Channels cerca atómicamente la creación contra `pendingUntilUtc`, guarda `registrationId` con el canal y hace visible el estado terminal a lookup después del deadline solo cuando ninguna creación en vuelo pueda confirmarse. Si la operación vence y el lookup devuelve PROVISIONED, Identity borra por registrationId hasta recibir confirmación idempotente; si devuelve ABSENT, cierra la compensación. Así no depende de que Identity haya recibido channelId. Identity nunca activa una cuenta EXPIRED ni emite `IdentityPublicChanged`; `ChannelProvisioned` durable confirma solo a Identity y nunca publica el canal. Discovery crea la proyección únicamente tras `IdentityPublicChanged` ACTIVE y lookups públicos. El mecanismo de transacción/outbox/saga queda en ADR, pero la cerca, lookup, estados terminales y compensación son invariantes.

## Flujo B — inicio de emisión y creación de sala

```mermaid
sequenceDiagram
  actor S as Streamer
  participant W as Web shell
  participant I as Identity
  participant T as Taxonomy
  participant ST as Streaming
  participant M as Media adapter
  participant C as Channels
  participant CH as Chat
  participant D as Discovery
  S->>W: crear o editar configuración de stream offline
  W->>I: validar principal/propiedad
  W->>T: consultar IDs activos del catálogo
  W->>ST: POST configuración por channelId + Idempotency-Key
  ST-->>W: streamId estable, rtmpUrl y clave mostrada una vez
  S->>M: enviar fuente RTMP con streamKey
  M->>ST: autorizar clave + ingestAttemptId; reservar slot; asignar streamGeneration/sourceGeneration/sessionId
  M->>ST: POST /internal/streaming/sessions/{sessionId}/source-connected
  ST->>ST: PREPARING mientras confirma playlist y segmento HLS reproducibles
  M->>ST: POST /internal/streaming/sessions/{sessionId}/playback-ready
  ST->>ST: LIVE/PLAYABLE; iniciar timeline
  ST-->>C: StreamSessionStarted(metadata, sessionId, streamGeneration, sessionVersion=1, PLAYABLE)
  ST-->>CH: StreamSessionStarted(sessionId, streamGeneration, sessionVersion=1, PLAYABLE)
  ST-->>D: StreamSessionStarted(metadata, sessionId, streamGeneration, sessionVersion=1, PLAYABLE)
  W-->>S: mostrar LIVE cuando playback está disponible
```

El `streamId` identifica la configuración única y persistente del canal; cada emisión tiene un `sessionId` nuevo. Una sala se asocia a `sessionId`, no solamente a `channelId` ni a `streamId`. Estado interno de sesión: PREPARING, LIVE, RECONNECT_GRACE o ENDED; disponibilidad pública: PLAYABLE, RECONNECTING u OFFLINE. PREPARING termina en ENDED/OFFLINE si no hay playback en 30 s; un slot se reserva desde PREPARING hasta ENDED y el límite de cinco incluye PREPARING/LIVE/gracia. El canal muestra LIVE · reconectando durante la gracia; Discovery no incluye el stream en consultas que prometen reproducción disponible. El canal y Discovery proyectan la sesión tras confirmar reproducibilidad. Falla de Chat o Discovery se
registra y reintenta sin detener playback.

## Flujo C — desconexión, reconexión y finalización

```mermaid
sequenceDiagram
  participant M as Media adapter
  participant ST as Streaming
  participant CH as Chat
  participant C as Channels
  participant D as Discovery
  M->>ST: POST /internal/streaming/sessions/{sessionId}/source-lost
  ST->>ST: iniciar ventana de gracia de 30 s
  Note over ST,CH: mismo sessionId; availability=RECONNECTING; Chat conserva lectura/escritura por 30 s
  alt fuente recuperada estrictamente antes del deadline de 30 s
    M->>ST: POST /internal/streaming/sessions/{sessionId}/playback-ready
    ST->>ST: volver a LIVE/PLAYABLE sin crear otro sessionId
  else ventana superada o fin voluntario
    ST->>ST: finalizar sesión una sola vez
    ST-->>C: StreamSessionEnded(sessionId, streamGeneration, sessionVersion final)
    ST-->>CH: StreamSessionEnded(sessionId, streamGeneration, sessionVersion final)
    ST-->>D: StreamSessionEnded(sessionId, streamGeneration, sessionVersion final)
    C->>C: mostrar OFFLINE
    CH->>CH: rechazar escrituras; conservar lectura
    D->>D: retirar sesión de búsquedas/listas LIVE
  end
```

Streaming fija una sola vez `graceDeadlineAtUtc` al detectar pérdida; ese UTC es informativo. El dueño
vigente mide los 30 s con reloj monotónico y serializa callback/timer bajo el estado, sessionId,
streamGeneration y fencing token actuales. La reconexión solo gana si `MediaPlaybackReady` se valida
con `elapsedSinceLoss < 30 s`; al cumplir `elapsedSinceLoss >= 30 s`, la transición atómica gana y
deja ENDED. Un dueño nuevo no reinicia ni extiende el tiempo: recibe el restante y cerca al anterior;
si no puede establecerlo termina la sesión. Callback tardío para ese sessionId/sourceGeneration se
ignora y requiere un intento nuevo que crea otro sessionId. Durante la
gracia Channels conserva la sesión como LIVE con availability=RECONNECTING, el player indica
reconexión, Discovery la excluye de resultados PLAYABLE y Chat permite lectura/envío. Al vencer o
recibir stop, Streaming publica ENDED/OFFLINE con streamGeneration y sessionVersion final y Chat queda
solo lectura. Los eventos Started/Ended son idempotentes; la proyección compara streamGeneration antes
de sessionVersion y no reabre una sesión nueva con un evento atrasado.

## Flujo D — cambios de metadata durante LIVE

1. Propietario autenticado envía nueva metadata a Streaming.
2. Streaming autoriza que principal sea owner del canal, consulta/valida IDs de categoría y tags activos,
   confirma una categoría y 0–5 tags, persiste con su autoridad local y responde nueva versión.
3. Streaming publica actualización idempotente con `aggregateId=stream:{streamId}`, `metadataVersion`
   monotónica por streamId, hora servidor y campos cambiados; si hay una sesión activa el evento incluye
   su sessionId como contexto, no como dueño de versión de metadata.
4. Channels, shell y Discovery actualizan su lectura/proyección; la siguiente consulta tras confirmación
   devuelve los campos nuevos. Un error de validación conserva la versión previa válida. Los eventos
   se ordenan por metadataVersion dentro del streamId, nunca contra sessionVersion.
5. El contrato especifica tolerancia de frescura si un consumidor está temporalmente caído y cómo
   reconstruye su proyección al volver.

## Proyección de audiencia hacia Discovery

Streaming emite snapshot inicial `ViewerCountChanged` al pasar a PLAYABLE, cada vez que cambia el
número efectivo de leases agrupando cambios por stream a un máximo de uno por segundo, y cada 5 s
aunque el número no cambie; al terminar emite un último cero. Cada snapshot incrementa `countVersion` monotónica por sessionId e incluye sessionId,
streamId, streamGeneration, count y hora observada por Streaming. Discovery valida que sessionId y
streamGeneration sigan vigentes y solo aplica countVersion mayor; `StreamSessionEnded` retira primero
la sesión del conjunto PLAYABLE y un count tardío no la resucita. Discovery refleja un cambio en ≤5 s
bajo operación normal. El resultado GraphQL conserva el último conteo conocido para ordenar, pero
expone `viewerCountFresh=false` si lleva más de 5 s sin snapshot; el estado de playback tiene su propio
`statusFresh`. Durante reconstrucción, Discovery consulta el snapshot de `GET
/api/streams/sessions/{sessionId}` y reanuda la aplicación desde la versión recibida.

## Flujo E — Chat y datos para Chat Replay futuro

```mermaid
sequenceDiagram
  actor V as Viewer (anónimo o autenticado)
  participant W as Player / shell
  participant I as Identity
  participant CH as Chat
  participant ST as Streaming clock/session
  V->>W: abrir sesión de chat
  W->>CH: WebSocket Upgrade /realtime/chat/sessions/{sessionId}
  CH-->>W: chat.ready (status, lastSequence)
  W->>CH: GET /api/chat/sessions/{sessionId}/messages?limit=50 (REST)
  CH-->>W: últimos 50 mensajes + snapshotSequence
  W->>W: fusionar historial y eventos por sequence
  opt enviar mensaje autenticado
    CH->>I: POST /internal/identity/sessions/introspect con cookie (HTTPS/TLS, servicio autenticado)
    I-->>CH: active, userId, handle, expiresAtUtc; nunca credencial
    CH->>CH: consultar Profile para snapshot público del autor
    Note over CH: si Profile falla, aceptar con handle canónico de Identity y avatarUri=null
    CH->>CH: validar cuota móvil 1000 ms global/cuenta, texto canónico, dedupe clientMessageId y sala
    ST-->>CH: muestra de timeline (sessionId, sampledAtUtc, timelinePositionMs, availability, sessionVersion)
    CH->>CH: usar muestra recibida en ≤3 s; persistir con sequence antes del ACK
    CH-->>W: message.accepted al emisor; message.created ordenado a suscriptores
  end
```

Campos P1 persistidos: messageId, sessionId, userId, authorDisplayName/avatar snapshot, texto seguro, serverCreatedAtUtc, streamOffsetMs, timelineSampleVersion y sequence. `timelineSampleVersion` equivale al `sessionVersion` de la muestra usada. Rechazar mensaje vacío, más de 500 puntos de código, más de uno en cualquier ventana móvil de 1000 ms por cuenta global, usuario anónimo o sala ENDED. `clientMessageId` deduplica reintentos por `(sessionId,userId,clientMessageId)` mientras se retenga el mensaje; sequence crece estrictamente por sesión. El principal confiable de Identity y el snapshot de Profile aportan la identidad visible; si Profile falla, aceptar con el handle canónico de Identity y `avatarUri=null`, persistiendo ese snapshot inmutable. El cliente no define userId, displayName, hora, sequence ni offset. Timeline inicia al primer LIVE reproducible, es monotónico durante LIVE/gracia y no se reinicia al reconectar; Streaming publica muestras por lo menos cada segundo también durante toda la gracia. Chat extrapola la última muestra recibida en ≤3 s usando reloj monotónico local y, si está stale, devuelve al mensaje un frame WebSocket `error` con code `TIMELINE_UNAVAILABLE`, sin persistir ni distribuir. P1 no reproduce VOD;
la reproducción de Chat Replay y la política de borrar moderados pertenecen a su futura fase.

## Flujo F — espectadores y Discovery

- Después del primer frame, el reproductor solicita a Streaming un lease por instancia. Streaming devuelve `leaseId` y token opaco; heartbeat cada 10 s renueva el lease y el último heartbeat válido expira a los 30 s. Cierre explícito o ENDED elimina el lease inmediatamente. Streaming cuenta leases, nunca un número indicado por cliente.
- `viewerCount` es diferente al número de autores/participantes conectados a Chat. En P1 aproxima leases de instancias de player y puede inflarse con clientes automatizados; solo muestra/ordena popularidad y nunca se usa para permisos, pagos o beneficios.
- Discovery consume sesiones PLAYABLE, metadata, viewerCount y valores Taxonomy; ordena por viewerCount DESC, startedAtUtc DESC y streamId ASC. Channel search puede devolver canal OFFLINE o LIVE/RECONNECTING y estado; búsqueda de títulos/filtros opera solo sobre PLAYABLE. Categoría + un tag se combinan por AND; IDs desconocidos/inactivos devuelven 422. P1 acepta como máximo un categoryId y un tagId por consulta; seleccionar varios tagIds queda fuera de P1.
- Discovery debe permitir reconstrucción desde fuentes, marcar frescura e ignorar evento viejo si una
  versión posterior ya fue aplicada. Evento de fin elimina LIVE aunque su proyección de conteo llegue tarde.

## Sobre de evento y semántica común

Cada evento interno declara, como mínimo: `eventId`, `eventType`, `schemaVersion`, `aggregateId`, `sequence`, `occurredAtUtc`, `producer` y payload. Los IDs canónicos coinciden exactamente con el contrato transversal: `identity:{userId}`, `profile:{userId}`, `channel:{channelId}`, `stream:{streamId}`, `session:{sessionId}`, `viewer-count:{sessionId}` y `chat-session:{sessionId}`. `sequence` equivale respectivamente a la versión pública Identity (1 para la activación P1), profileVersion, channelVersion, metadataVersion, sessionVersion, countVersion y sequence de mensajes; nunca se compara entre aggregateIds distintos. Cada evento de sesión incluye `streamGeneration`, monotónica por streamId y aumentada solo con un nuevo sessionId; reconnect/grace mantiene generación y sesión. Consumers eligen la generación mayor y solo comparan sessionVersion dentro de esa sesión; el conteo usa sesión/generación vigente y countVersion. Marca de tiempo de cliente nunca decide el orden autoritativo.

- **Entrega candidata:** at-least-once; el consumidor confirma solo luego de procesar/persistir y puede
  reintentar. Exactly-once no se promete.
- **Duplicados:** deduplicar por `eventId` o idempotency key; el mismo Started/Ended/heartbeat no duplica
  sala, canal, contador o proyección.
- **Desorden/atraso:** consumidor compara version/secuencia por agregado; una lectura de origen o backfill
  repara el estado si no puede resolver una brecha.
- **Timeout/fallo:** reintentos de evento acotados con backoff; no reintentar infinitamente. Excepción
  definida: callback Media→Streaming reintenta timeout/408/429/5xx durante un máximo de 15 min, alerta
  desde 30 s y luego pasa a dead-letter durable sin reintento automático. Errores permanentes también
  van a dead-letter; se redrivean manualmente con el mismo eventId tras recuperar el receptor, con una
  nueva ventana de 15 min. El registro persiste hasta ACK/410 o cierre operativo, sin TTL automático.
  Propagar correlation ID, métricas de atraso, cantidad y edad de cola, y alertas/logs sin secretos.
- **Seguridad:** stream key, password, cookie/token y email nunca van en evento público/log. Discovery
  recibe sólo atributos públicos.

## Matriz de fallo/degradación

| Dependencia caída | Conducta P1 | Recuperación/evidencia |
| --- | --- | --- |
| Identity | No completar registro ni acción protegida; rechazar sesión no válida | Reintento seguro; registro/cambio no duplicado |
| Channels durante registro | Identity devuelve PENDING sin login/sesión ni visibilidad pública; al expirar gana EXPIRED terminal | Repetir misma clave hasta el deadline; si Channel responde tarde, compensar idempotentemente; probar que no hay reactivación, huérfano visible ni documento Discovery |
| Taxonomy | No aceptar nuevos IDs no validados; conservar metadata previa válida | Volver a consultar catálogo y reprocesar comando |
| Media adapter / ingest | No declarar LIVE hasta medio reproducible; mantener/terminar según gracia de 30 s | Métrica de state transition y prueba recovery |
| Chat | Video continúa; UI señala chat no disponible; al fin sesión rechaza escritura | Reabrir sala para mismo sessionId durante grace, no para sesión terminada |
| Discovery/proyección stale | Navegación directa a canal/player sigue disponible | Marcar freshness=false/RECONNECTING o excluir del listado PLAYABLE; nunca afirmar LIVE reproducible ni OFFLINE sin fuente fresca |
| Profile | Canal no revela privacidad; usa handle/avatar fallback acordado. Si Chat no puede leer el perfil en un envío, captura handle canónico de Identity y avatar nulo y acepta el mensaje. | Reintentar proyección/consulta; una caída de Profile no bloquea playback ni Chat |
| Reverse proxy | Mostrar error/fallback por componente; no convertir error API a HTML exitoso | Health por upstream/ruta/requestId; preservar WS Upgrade y no loguear credenciales de viewer lease |

## Criterios integrados de P1

1. Registro válido acaba ACTIVE con cuenta y un canal; si Channels falla antes del deadline, devuelve PENDING sin login y el mismo Idempotency-Key recupera un único canal. A las 24 h desde `pendingSinceUtc`, EXPIRED es terminal; una respuesta tardía de Channels no reactiva ni publica y se compensa.
2. Un encoder autenticado inicia una fuente RTMP; el player cambia a LIVE solo al confirmar playback HLS.
3. Título/categoría/tags cumplen límites y reflejan actualización válida durante la emisión.
4. Canal, Chat y Discovery comparten sessionId y versión; en grace el canal informa RECONNECTING, Chat permanece abierto, Discovery excluye de PLAYABLE; reconexión a 29 s mantiene sesión y fin al llegar a 30 s la termina.
5. Player muestra canal/título/categoría; visitante reproduce y lee chat; anónimo no envía; mensaje autenticado se distribuye, aplica rate-limit global y queda guardado con campos para Chat Replay.
6. Conteo de viewers incluye sesiones anónimas y expira tras 30 s; no equivale a usuarios de chat.
7. Falla Chat o Discovery sin cortar playback activo; eventos/proyecciones se recuperan sin duplicados.
8. Los módulos web se integran detrás de rutas/proxy comunes y todo el prototipo inicia desde el runbook.
9. La carga y tiempos se miden según el perfil de catálogo; resultados inferiores o fallidos quedan visibles.
