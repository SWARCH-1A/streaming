# ADR-010: Chat en Go con Redis efímero por sesión

> Ámbito: perfil P1. [ADR-015](ADR-015-arquitectura-p2.md) define la arquitectura P2:
> SSR, Gateway, broker, VOD y chat durable. Este ADR conserva la decisión de la primera entrega;
> [ADR-021](ADR-021-chat-redis-postgresql-replay-moderacion.md) añade PostgreSQL para replay y
> moderación en P2 sin cambiar el vivo descrito aquí.


- Estado: aceptada
- Fecha: 2026-10-03
- Responsable: Chat
- SPEC/contratos afectados: SPEC-05, SPEC-11, SPEC-13; contratos Chat REST/WS, contexto Core y session-events.

## Contexto

Chat debe mantener conexiones largas, distribuir 20 mensajes/s agregados con p95 <1 s y aplicar
una cuota global de un mensaje por cuenta en cualquier ventana móvil de 1000 ms. También debe
deduplicar por `(sessionId,userId,clientMessageId)`, asignar sequence creciente por sesión y guardar
antes del ACK. Lo confirmado debe poder distribuirse después de una caída, incluso entre réplicas.
ADR-003 había dejado Go y MongoDB como candidatos.

Acordamos que el chat sea temporal: los mensajes existen durante la sesión y se eliminan poco
después del fin. Dentro de esa retención, los mensajes confirmados deben conservarse.
Chat Replay para VOD queda para otra fase, que deberá definir su almacenamiento.

## Decisión

1. Go 1.26 para el servicio Chat: `net/http`, `github.com/coder/websocket`, `github.com/redis/go-redis/v9` y `golang.org/x/text` para NFC. Un binario, listener público (historial y WS, puerto 8085) y listener interno (`/internal/chat/session-events`, puerto 8086, TLS opcional por certificado).
2. Redis 8 como único almacén de Chat (NoSQL clave-valor). Claves por sesión con etiqueta `{sessionId}`: estado de sala (hash), contador de sequence, dedupe (hash) y mensajes (Redis Stream con ID `0-<sequence>`). Claves globales: cuota `chat:quota:{userId}` y inbox de eventos `chat:inbox:{eventId}`.
3. Un script Lua por envío realiza de forma atómica: búsqueda de dedupe (devuelve el ACK previo sin consumir cuota), verificación de `writeAllowed` y de sala no terminada, `SET NX PX 1000` de cuota global, `INCR` de sequence, `XADD` del mensaje y registro de dedupe. El ACK se envía solo después de que el script responde.
4. Durabilidad: AOF con `appendfsync always` y volumen persistente; Redis responde después de escribir y sincronizar el AOF, así que un mensaje con ACK sobrevive a un reinicio de Redis o del contenedor. `maxmemory-policy noeviction`: Redis nunca descarta mensajes por presión de memoria.
5. El Redis Stream de la sala es a la vez historial reciente y outbox: cada réplica con clientes conectados lee el Stream con `XREAD BLOCK` desde su último sequence. Lo confirmado por cualquier réplica, incluso una que cayó antes de difundir, se entrega a todos los conectados. La entrega puede repetirse; el cliente deduplica por `(sessionId,sequence)`.
6. Retención: al recibir ENDED (evento o snapshot) la sala pasa a `READ_ONLY` y todas sus claves expiran a los 5 minutos (`CHAT_ENDED_RETENTION`). Durante ese lapso el historial sigue legible y un reintento recupera su ACK. Después el historial queda vacío. El Stream no se recorta mientras la sala existe: todo mensaje con ACK sigue disponible hasta la expiración de la sala. La memoria queda acotada por la cuota de 1 mensaje/s por cuenta, el límite de texto y la duración de la sesión. `CHAT_ROOM_IDLE_TTL` (12 h) vence únicamente la caché de estado, recuperable por snapshot. Mensajes, sequence y dedupe activos no tienen TTL de inactividad: un fin no observado se recupera por outbox/snapshot y es el único que inicia su retención. Esto evita perder ACKs de sesiones largas o silenciosas. Como el outbox puede agotar su ventana de reintentos con Chat caído, cada réplica consulta cada `CHAT_ROOM_SWEEP_INTERVAL` (1 min) el snapshot de las salas con mensajes sin retención iniciada: ENDED inicia la retención, una sesión inexistente también, y Core caído deja la sala igual hasta el siguiente barrido.
7. Autorización: un contexto Core por mensaje nuevo (Core compone usuario/autor y el estado/timeline de Streaming), sin caché y sin reintento automático. Timeout por llamada de 400 ms (conexión de 100 ms) y presupuesto de 500 ms entre pedir el contexto e intentar persistir, medidos con monotónico local. El contexto
limita también I/O Redis y no se reintentan comandos automáticamente. Un script ya recibido no
se cancela retroactivamente: timeout Redis significa resultado desconocido sin ACK y se recupera
por dedupe con el mismo clientMessageId. Los eventos de sesión de Streaming se deduplican en la inbox dentro del mismo script que actualiza la sala; nunca autorizan escrituras.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Go + Redis efímero | Elegida: atomicidad de cuota/dedupe/sequence/mensaje en un script, fan-out entre réplicas con Streams, TTL nativo para borrar al terminar y baja latencia. |
| Go + MongoDB | Historial permanente innecesario sin Replay; cuota global y fan-out exigirían transacciones, change streams o un segundo componente. |
| Redis Pub/Sub para difusión | No es durable: un crash entre commit y publish pierde la entrega. El Stream sí se puede releer. |
| Redis sin AOF o `appendfsync everysec` | Puede perder hasta 1 s de mensajes con ACK si Redis cae; incumple “no aceptar pérdida”. |
| Node.js/TypeScript o Java | Viables; Go aporta concurrencia simple para conexiones largas y suma el tercer lenguaje del sistema (Java Core, TypeScript Web). |

## Consecuencias

El chat no se puede consultar después de la retención: no hay Chat Replay en P1. Un Replay futuro requerirá un ADR que defina
dónde persistir y con qué retención; ese almacén pertenecerá a Chat.

`appendfsync always` limita el rendimiento de escritura de Redis a la latencia de fsync del disco;
20 mensajes/s quedan muy por debajo de ese límite, pero debe medirse en SPEC-13. Los scripts usan
claves de varias ranuras (cuota/inbox globales); P1 opera un Redis primario sin Cluster. Escalar a
Redis Cluster exige reubicar la cuota o separar el script. Redis con réplicas o Sentinel queda fuera
de P1; si Redis cae, Chat rechaza envíos con `CHAT_UNAVAILABLE` y HLS continúa.

Sin recorte, la memoria de Redis crece con los mensajes de cada sesión activa hasta su fin más 5
minutos; con la carga objetivo (20 mensajes/s durante 10 minutos) son unos 12 000 mensajes de hasta
500 caracteres, del orden de pocos MB, y debe medirse en SPEC-13. Con `noeviction`, agotar memoria
hace fallar envíos con `CHAT_UNAVAILABLE` en vez de perder mensajes confirmados. Las conexiones WS no se cierran al terminar la sesión;
reciben `chat.status READ_ONLY`.

Chat no guarda la credencial de sesión: la lee de la cookie en cada envío y la reenvía a Core en
`X-Session-Credential`. Los logs no incluyen credenciales ni texto de mensajes.

## Verificación

Pruebas automatizadas en `services/chat` (`go test ./...`, Redis simulado con miniredis): NFC y
límites 500/501, cuota 1000 ms entre salas sin ráfaga, dedupe con el mismo ACK sin consumir cuota,
`MESSAGE_ID_CONFLICT`, rechazo sin persistencia ante `CORE_UNAVAILABLE`/`STREAMING_UNAVAILABLE`/
presupuesto excedido, lectura anónima y envío autenticado por WS, Origin, historial ascendente
con `snapshotSequence`, eventos deduplicados y ENDED terminal, borrado tras 5 minutos, barrido que
inicia la retención de un ENDED no recibido y entrega de
mensajes confirmados por otra réplica o por un proceso que cayó antes de difundir.

Contra Redis 8 real (`go test -tags integration ./internal/store/`): scripts de envío y de estado,
cuota, dedupe, lectura bloqueante del Stream y TTL de retención. La recuperación debe conservar historial con ACK
tras reiniciar Redis. SPEC-13 verifica los endpoints Core/Streaming reales y la carga de
20 msg/s con p95 <1 s; sus resultados se registran en Plane.

## Revisión

Revisar si se aprueba Chat Replay, si la carga exige Redis Cluster o réplicas, si `appendfsync always`
no alcanza el p95 medido o si el producto pide un historial más largo. Revisa: responsable de Chat
con Integración.
