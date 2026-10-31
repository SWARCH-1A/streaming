# ADR-024: Chat con Redis para el vivo y PostgreSQL propio para replay y moderación

- Estado: aceptada para Chat
- Fecha: 2026-10-28
- Responsable: Chat
- SPEC/contratos afectados: SPEC-05 P2, SPEC-03 P2 (`p2.chat.moderation-context`), SPEC-17;
  `p2.chat.*`; consume `vod.availability.changed.v1` y `p2.vod.replay-context` de VOD. Resuelve
  la parte de Chat de P2-10 (el mapping lo fija [ADR-022](ADR-022-vod-go-postgresql-minio.md)) y P2-11.

## Contexto

[ADR-010](ADR-010-chat-go-redis-efimero.md) dejó el chat P1 en Redis, borrado 5 minutos
después del fin. En P2, RF-082 pide reproducir el chat junto al VOD mientras exista, y RF-036,
RF-037 y RF-083 piden retirar mensajes y bloquear cuentas por canal. Los bloqueos temporales
deben vencer según el reloj del servidor y sobrevivir a reinicios (CA-03). Un mensaje retirado
no puede volver a aparecer ni en el vivo ni en el replay (CA-04). El envío conserva la latencia
de P1: el ACK sigue dependiendo solo de Redis.

## Decisión

1. **Dos almacenes, un solo dueño.** Redis 8 sigue siendo el almacén del vivo: cuota, dedupe,
   sequence, historial reciente y fan-out por WebSocket, como en ADR-010. PostgreSQL 18 propio de
   Chat (`chat-pg`, un componente de datos más) guarda la copia durable. Ningún otro servicio lo
   lee y Chat no lee la base de Core.
2. **Archivo asíncrono sin pérdida.** El mismo script Lua que confirma un mensaje agrega una
   entrada al stream `chat:archive`. El ACK sale después del script, igual que en P1. Cada réplica
   consume ese stream con el grupo `archiver`, inserta en PostgreSQL de forma idempotente y solo
   entonces confirma y borra la entrada. Las entradas que deja una réplica caída se reclaman tras
   `CHAT_ARCHIVE_CLAIM_IDLE` (30 s). El archivo no expira con la sala, así que borrar la sala
   5 minutos después del fin no adelanta a la confirmación durable (CA-06).
3. **Qué guarda PostgreSQL.** Sesión, canal, sequence, messageId, autor público (userId, handle,
   nombre, avatar), texto, hora UTC del servidor, `streamOffsetMs` y generación. Un mensaje
   retirado conserva su fila para mostrar el aviso "mensaje eliminado por el moderador", pero
   **sin texto** (`text = NULL`). Si el retiro llega antes que el archivador, la fila se inserta
   ya retirada y el archivador no la repone. El replay no entrega los retirados.
4. **Sequence.** Lo sigue asignando Redis (`INCR` dentro del script). PostgreSQL usa
   `(sessionId, sequence)` como clave primaria.
5. **Retención.** La sala viva de Redis se conserva 5 minutos tras el fin (`CHAT_ENDED_RETENTION`).
   En PostgreSQL el historial dura mientras exista un VOD vinculado a la sesión. Un evento
   `DELETED` borra el historial cuando la sesión no tiene otro VOD vigente. Una sesión que nunca
   recibió vínculo VOD se borra después de `CHAT_UNLINKED_RETENTION` (7 días) desde su último
   mensaje.
6. **Mapping temporal (P2-10).** `streamOffsetMs` es la posición del timeline de la sesión que
   Core entrega al autorizar cada mensaje. El mapping al video lo define VOD (ADR-022): compacta
   los huecos conocidos y publica `P2Timeline` con tramos `sessionStartMs`/`mediaStartMs` y
   `mappingVersion`. Chat aplica la misma regla que VOD: dentro de un tramo, `mediaStartMs +
   streamOffsetMs - sessionStartMs`; un mensaje en un hueco va a la unión siguiente y uno
   posterior al último tramo, al último milisegundo. Como el mapping es no decreciente, una
   ventana del video corresponde a un único tramo de la sesión y basta un rango por
   `stream_offset_ms`.
7. **Comunicación con VOD.** Para cada página de replay, Chat llama a `p2.vod.replay-context`
   con su token de servicio y la credencial del espectador. VOD decide si el VOD es legible y
   entrega el timeline vigente. Chat no copia el timeline, así que un retiro, una eliminación o un
   cambio de mapping rigen en la consulta siguiente. Además Chat consume `chat.vod` (RabbitMQ,
   ADR-016) con inbox en PostgreSQL: registra el vínculo VOD–sesión y su versión, ignora versiones
   viejas y trata `DELETED` como terminal. VOD le delega la limpieza (`replayCleanup=DELEGATED_TO_CHAT`).
8. **Replay.** `GET /api/chat/vods/{vodId}/messages?fromMs&toMs` acepta ventanas de hasta
   300 000 ms del video y devuelve 20 mensajes por defecto, 50 como máximo, en orden de
   `sequence` original (`P2ReplayPage`). El cursor se liga a vodId, ventana y `mappingVersion`
   y avanza por `sequence`, así que un retiro posterior no desplaza la paginación. Si cambió el
   mapping responde 410. `moderationVersion` es el número de retiros de la sesión más uno.
   Acepta la cookie o un Bearer nativo; ambos a la vez es 401.
9. **Moderación (P2-11).** Solo modera la cuenta propietaria del canal. Core lo confirma por
   operación en `POST /internal/core/chat/moderation-context`, sin caché. Reglas:
   - **Bloqueo por canal:** `TEMPORARY` con duración fija (`PT10M`, `PT1H`, `PT24H`, `P7D`) o
     `PERMANENT` hasta su retiro manual. Ante cambios concurrentes gana la última escritura y
     `version` crece en cada cambio.
   - **Aplicación del bloqueo:** el registro vive en PostgreSQL y Redis guarda una copia que el
     script de envío consulta, con `PXAT` en el mismo instante absoluto. El mantenimiento
     periódico repone esa copia desde PostgreSQL.
   - **Lo que ve la cuenta bloqueada:** el error `CHAT_BLOCKED`, con `blockMode` y
     `blockedUntilUtc`.
   - **Borrado por usuario:** abarca solo la emisión indicada, hasta el sequence observado al
     aceptar la orden. Bloquear no borra mensajes.
   - **Difusión del retiro:** cada retiro incrementa `moderationVersion` de la sesión y se
     difunde con el frame `message.removed`.
10. **Seguridad de las mutaciones.** Se exigen Origin permitido, el doble envío CSRF emitido por
    Core (`XSRF-TOKEN` / `X-XSRF-TOKEN`) e `Idempotency-Key`, con resultado retenido 24 h en
    PostgreSQL.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Redis + PostgreSQL con outbox en Redis Stream | Elegida. Mantiene el ACK y la latencia de P1, persiste sin pérdida, permite consultas por ventana de tiempo e índices, y PostgreSQL ya se opera en el proyecto. |
| Escribir en PostgreSQL antes del ACK | Duraría más cada envío y haría depender el vivo de PostgreSQL. Además la cuota, el dedupe y el sequence atómicos tendrían que repartirse entre dos almacenes. |
| Volcar todo al terminar la emisión | Simple, pero el replay quedaría expuesto a la pérdida de Redis durante la emisión y un ENDED no observado retrasaría el archivo. |
| Redis con persistencia larga | Redis no ofrece consultas temporales eficientes, la memoria crecería sin límite y la retención ligada a VOD sería frágil. |
| MongoDB | Era candidato en ADR-003. Agrega otra tecnología de datos sin ventaja sobre PostgreSQL para estas consultas. |

## Consecuencias

- Un mensaje confirmado llega a PostgreSQL con un pequeño retraso. Si PostgreSQL cae, Chat sigue
  aceptando mensajes en Redis y el archivo se acumula en `chat:archive` hasta recuperarse.
  Moderación y replay responden `CHAT_UNAVAILABLE` (503) mientras tanto. Si VOD no responde, el
  replay devuelve `REPLAY_UNAVAILABLE` (503) y el video sigue.
- Con el ciclo por broker, una sala sin canal conocido rechaza envíos con `CHAT_UNAVAILABLE`,
  porque sin canal no se puede verificar un bloqueo.
- El perfil P1 sin `CHAT_DATABASE_URL` conserva el comportamiento de ADR-010.
- El despliegue suma `chat-pg` con TLS, una contraseña generada por `dev.py init` y su volumen.
- Cada página de replay cuesta una llamada a VOD, que a su vez consulta Core. Es el precio de no
  tener una copia del acceso que pueda quedar desfasada; si la carga lo exige, se revisará una
  caché corta ligada a `mappingVersion`.
- Chat necesita `CHAT_VOD_BASE_URL` y `CHAT_VOD_SERVICE_TOKEN`; sin ellos el replay responde 503.

## Verificación

- `go test ./...`: dominio, Redis (bloqueo, archivo, retiros), broker (`decodeVod` con el
  envelope de VOD) y `internal/vod` (mapping, ventana inversa y cliente `replay-context`).
- `go test -tags integration` con PostgreSQL real: archivo idempotente, retiros sin texto,
  bloqueos, idempotencia, vínculos VOD y replay. También las rutas HTTP/WS completas.
- `CoreChatIT` para `moderation-context`, `contracts/check_p2.py` y las pruebas Web del módulo
  chat.

Falta medir carga, reinicio real de Redis y PostgreSQL en el stack, y el replay contra un VOD
real. Esas mediciones pertenecen a la aceptación de SPEC-05.

## Revisión

Revisar si VOD cambia `P2Timeline`, si la carga medida exige escribir por lotes o separar el
archivador, o si se adopta Redis Cluster, porque el script usa claves de varias ranuras. Revisa el
responsable de Chat junto con VOD.
