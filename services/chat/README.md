# Chat

Servicio propio de salas, mensajes, cuota global por cuenta, deduplicación, secuencia, historial y
WebSocket. Se define en [SPEC-05](../../docs/spec-p1/spec_05_chat.md) y
[contratos](../../docs/contratos_modelo_datos.md). Go y Redis según
[ADR-010](../../docs/adr/ADR-010-chat-go-redis-efimero.md). El chat es efímero: la sala se elimina
5 minutos después de que termina la sesión. Obtiene un contexto Core por mensaje nuevo y no consulta
servicios de identidad, perfil o emisión por separado.

## Estructura

| Paquete | Responsabilidad |
| --- | --- |
| `cmd/chat` | Arranque, listeners y apagado ordenado. |
| `internal/chat` | Reglas de dominio: texto NFC 1–500, códigos estables, estado de sala, modelo de mensaje. |
| `internal/store` | Único repositorio Redis: scripts atómicos de envío y de estado de sala, historial y lectura del Stream. |
| `internal/core` | Cliente del contrato privado Core: `message-context` y snapshot de sesión. |
| `internal/service` | Casos de uso: resolver sala, enviar, historial y eventos de sesión. |
| `internal/transport` | REST de historial, WebSocket, hub de distribución y `POST /internal/chat/session-events`. |

## Interfaces

Listener público (`CHAT_PUBLIC_ADDR`, 8085), encaminado por el proxy:

- `GET /api/chat/sessions/{sessionId}/messages?limit=1..50`
- `WS /realtime/chat/sessions/{sessionId}`. Origin exacto obligatorio; la cookie `stream_session` solo se usa para enviar.
- `GET /healthz` y `GET /readyz` (Redis).

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
| `CHAT_SESSION_COOKIE` | `stream_session` | Cookie de sesión Core. |
| `CHAT_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error`. |

Redis debe correr con `appendonly yes`, `appendfsync always` y `maxmemory-policy noeviction`, como en
[compose.chat.yaml](../../infra/local/compose.chat.yaml). Un Redis primario, sin Cluster.

## Pruebas y ejecución

```sh
cd services/chat
go test ./...            # Redis simulado con miniredis; no requiere Docker
go run ./cmd/chat        # requiere las variables obligatorias y un Redis accesible
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
