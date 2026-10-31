# Chat

Chat es el servicio Go que maneja salas, mensajes, historial y WebSocket. Redis guarda las
secuencias, la deduplicación y la cuota global por cuenta, según
[ADR-010](../../docs/adr/ADR-010-chat-go-redis-efimero.md). Con `CHAT_DATABASE_URL`, PostgreSQL
propio guarda la copia durable para replay, los bloqueos y los retiros
([ADR-021](../../docs/adr/ADR-021-chat-redis-postgresql-replay-moderacion.md),
[SPEC-05 P2](../../docs/spec-p2/spec_05_chat.md)).

Cada mensaje nuevo necesita un contexto Core con identidad, autor y estado de la emisión.
La sala se conserva durante la sesión y se elimina 5 minutos después del fin.
[SPEC-05](../../docs/spec-p1/spec_05_chat.md) y los
[contratos](../../docs/contratos_modelo_datos.md) describen esas reglas.

## Estructura

| Paquete | Responsabilidad |
| --- | --- |
| `cmd/chat` | Arranque, listeners y apagado ordenado. |
| `internal/chat` | Reglas de dominio: texto NFC 1–500, códigos estables, estado de sala, modelo de mensaje. |
| `internal/store` | Único repositorio Redis: scripts atómicos de envío (con bloqueo y entrada de archivo) y de estado de sala, historial, retiros y lectura de los Streams. |
| `internal/archive` | Único repositorio PostgreSQL: migraciones embebidas, archivo idempotente, retiros sin texto, bloqueos, operaciones, idempotencia, vínculos VOD y replay. |
| `internal/core` | Cliente del contrato privado Core: `message-context`, `moderation-context` y snapshot de sesión. |
| `internal/service` | Casos de uso: resolver sala, enviar, historial, eventos de sesión, moderación, replay, archivador y mantenimiento. |
| `internal/broker` | Consumidores RabbitMQ `chat.lifecycle` y `chat.vod`, con reintentos y dead-letter. |
| `internal/transport` | REST de historial, WebSocket, hub de distribución y `POST /internal/chat/session-events`. |

## Interfaces

Listener público (`CHAT_PUBLIC_ADDR`, 8085), encaminado por el proxy:

- `GET /api/chat/sessions/{sessionId}/messages?limit=1..50`
- `WS /realtime/chat/sessions/{sessionId}`. Origin exacto obligatorio; la cookie `stream_session` solo se usa para enviar.
- `GET /healthz` y `GET /readyz` (Redis y, con archivo, PostgreSQL).

Con archivo (P2), además:

- `GET /api/chat/vods/{vodId}/messages?fromMs&toMs[&limit&cursor]`: replay público de un VOD publicado.
- `DELETE /api/chat/sessions/{sessionId}/messages/{messageId}`: retirar un mensaje.
- `PUT|DELETE|GET /api/chat/channels/{channelId}/blocks/{userId}`: bloquear, desbloquear y consultar.
- `POST /api/chat/channels/{channelId}/message-removals` y `GET .../moderation-operations/{operationId}`:
  retirar los mensajes de una cuenta en una emisión.

Las mutaciones exigen cookie `stream_session`, Origin permitido, `XSRF-TOKEN` = `X-XSRF-TOKEN` e
`Idempotency-Key`. Core confirma la propiedad del canal en cada operación.

Listener interno (`CHAT_INTERNAL_ADDR`, 8086), nunca publicado por el proxy:

- `POST /internal/chat/session-events` con `X-Service-Name: streaming` y `X-Service-Token`.

## Configuración

| Variable | Default | Uso |
| --- | --- | --- |
| `CHAT_REDIS_URL` | obligatoria | `redis://host:6379/0` |
| `CHAT_ALLOWED_ORIGINS` | obligatoria | Orígenes web exactos separados por coma. |
| `CHAT_CORE_BASE_URL` | obligatoria | Base del listener privado de Core. |
| `CHAT_CORE_SERVICE_TOKEN` | obligatoria | Token de Chat hacia Core (`X-Service-Token`). |
| `CHAT_SESSION_EVENTS_TOKEN` | obligatoria | Token que debe presentar Streaming. |
| `CHAT_SESSION_EVENTS_PRODUCER` | `streaming` | `X-Service-Name` y `producer` aceptados. |
| `CHAT_CORE_DEVELOPMENT_HTTP` | `false` | Opt-in HTTP exclusivo de desarrollo aislado. HTTPS en despliegue. |
| `CHAT_CORE_CA_FILE` | vacío | CA privada para TLS hacia Core. |
| `CHAT_INTERNAL_TLS_CERT_FILE` / `_KEY_FILE` | vacío | TLS del listener interno. |
| `CHAT_CORE_CONNECT_TIMEOUT` / `CHAT_CORE_TIMEOUT` | `100ms` / `400ms` | Presupuesto por llamada a Core. |
| `CHAT_AUTH_BUDGET` | `500ms` | Máximo entre pedir contexto e intentar guardar. |
| `CHAT_ENDED_RETENTION` | `5m` | Tiempo legible tras ENDED antes de borrar la sala. |
| `CHAT_ROOM_IDLE_TTL` | `12h` | TTL de caché de estado. No vence mensajes, sequence ni dedupe de una sesión activa. |
| `CHAT_EVENT_INBOX_TTL` | `24h` | Retención de eventIds deduplicados. |
| `CHAT_ROOM_SWEEP_INTERVAL` | `1m` | Cada cuánto se consulta el snapshot de salas con mensajes cuyo fin no se observó; un ENDED perdido inicia ahí la retención. |
| `CHAT_READER_BLOCK` | `2s` | Espera máxima de cada lectura bloqueante del Stream de una sala. |
| `CHAT_SESSION_COOKIE` | `stream_session` | Cookie de sesión Core. |
| `CHAT_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error`. |
| `CHAT_DATABASE_URL` | vacío | PostgreSQL de Chat (`postgres://…`). Vacío conserva el perfil P1 sin archivo ni moderación. |
| `CHAT_UNLINKED_RETENTION` | `168h` | Historial durable de una sesión que nunca recibió un VOD, contado desde su último mensaje. |
| `CHAT_ARCHIVE_CLAIM_IDLE` | `30s` | Tiempo tras el cual otra réplica reclama entradas de archivo no confirmadas. |
| `CHAT_IDEMPOTENCY_RETENTION` | `24h` | Retención de los resultados de `Idempotency-Key`. |

Redis debe correr con `appendonly yes`, `appendfsync always` y `maxmemory-policy noeviction`, como en
[compose.chat.yaml](../../infra/local/compose.chat.yaml). Un Redis primario, sin Cluster.

## Pruebas y ejecución

```sh
cd services/chat
go test ./...            # Redis simulado con miniredis; no requiere Docker
go run ./cmd/chat        # requiere las variables obligatorias y un Redis accesible
```

Pruebas contra PostgreSQL real del archivo y de las rutas de moderación/replay (el esquema
`public` de esa base se borra al empezar; usar una base dedicada):

```sh
docker run -d --name chat-test-pg -e POSTGRES_USER=chat -e POSTGRES_PASSWORD=chat-test -e POSTGRES_DB=chat_test -p 127.0.0.1:55432:5432 postgres:18-alpine
CHAT_TEST_DATABASE_URL="postgres://chat:chat-test@127.0.0.1:55432/chat_test?sslmode=disable" go test -tags integration ./internal/archive/ ./internal/transport/
docker rm -f chat-test-pg
```

Pruebas contra Redis real (la base indicada se vacía al empezar; nunca usar una con datos):

```sh
docker run -d --name chat-test-redis -p 127.0.0.1:6390:6379 redis:8-alpine
CHAT_TEST_REDIS_URL=redis://localhost:6390/15 go test -tags integration ./internal/store/
docker rm -f chat-test-redis
```

Con Docker, ver [entorno local](../../infra/local/README.md). Core publica
`/internal/core/chat/*` exclusivamente en su listener privado. Configurar el mismo secreto Chat
en ambos extremos y la URL de ese listener; su puerto público devuelve 404. Un fallo de credencial
de servicio es CORE_UNAVAILABLE; una sesión de usuario revocada es AUTH_REQUIRED.
El cliente no sigue redirecciones, limita a 64 KiB y no cachea ni reintenta permisos. El presupuesto
monotónico de 500 ms abarca solicitar contexto e intentar el script; el I/O Redis respeta el
contexto y no hace retries automáticos. Timeout Redis puede dejar un commit desconocido sin ACK: el
cliente recupera con el mismo clientMessageId, sin prometer cancelación retroactiva del script.
La [suite integrada P1](../../tests/integration/p1-domains/README.md) usa los proveedores reales.

## Transporte P2 por RabbitMQ

CHAT_EVENTS_TRANSPORT=http (por defecto) conserva recepción HTTP P1. Con amqp, CHAT_BROKER_URL y CHAT_BROKER_CA_FILE son obligatorios: consume chat.lifecycle y deshabilita su endpoint HTTP de eventos. Inbox y barrera P2 se persisten sin TTL en Redis, atómicos con el efecto, junto con el canal de la sesión. Con archivo también consume chat.vod (`vod.availability.changed.v1`, inbox en PostgreSQL) y una sala cuyo canal no llegó por el ciclo rechaza envíos con CHAT_UNAVAILABLE, porque no puede verificar bloqueos.

Ver [perfil compartido](../../infra/broker/README.md) y [ADR-016](../../docs/adr/ADR-016-rabbitmq-eventos-p2.md).
