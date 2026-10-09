# Streaming y Media — SPEC-04

Servicio Rust de gestión de emisiones, PostgreSQL/SQLx con pooling y MediaMTX autogestionado para RTMP/LL-HLS. El stack P1 usa tres contenedores: PostgreSQL, MediaMTX y `streaming-service`. El proceso Rust incluye el adaptador técnico Media en el mismo runtime Tokio, según [ADR-011](../../docs/adr/ADR-011-streaming-tres-contenedores-p1.md). `media-adapter` se conserva como CLI de migración y operación; no inicia un servidor.

## Responsabilidades

Streaming posee configuración, claves, sesiones/generaciones, cupos, clock, leases de player y snapshots públicos. MediaMTX transporta audio/video; el adaptador autoriza publishers, cerca aliases HLS y entrega callbacks durables. Streaming y el adaptador tienen una base privada y una migración inicial cada uno.

Core posee identidad/canal/catálogo y Discovery. Cada mutación protegida pide un contexto Core nuevo; valida IDs/tipos/activos y confirma con presupuesto monotónico de un segundo. La lectura protegida valida sesión usando el perfil autenticado Core y verifica propiedad del canal. Los labels validados se conservan en Streaming, incluidos valores inactivos/tombstones, para lectura pública ante caída de Core. Una lectura de catálogo no sobrescribe una asociación cambiada por un PATCH concurrente. Los relays de Chat y Discovery tienen claims y ACK independientes.

Los contratos con Core y Chat se definen en [datos y APIs](../../docs/contratos_modelo_datos.md). El despliegue compartido, proxy/TLS, Web/player y aceptación del sistema pertenecen a [integración](../../docs/spec-p1/spec_09_int.md).

## Estructura

```text
src/
  domain/                    IDs y máquina de estados pura
  application/ports/         Contratos de dependencias/persistencia
  application/use_cases/     Reglas y coordinación
  adapters/inbound/          HTTP público/privado y CLI
  adapters/outbound/         PostgreSQL, Core/Chat, Media, clock y probes
  media_adapter/             Config, handlers, repositorio técnico, workers y operador
  bootstrap.rs               Composición y supervisión Streaming
  main.rs                    Ejecutable Streaming
  media_adapter_main.rs      Ejecutable Media
migrations/0001_streaming.sql
media-adapter/
  migrations/0001_media.sql
  mediamtx.yml
  init-database.sh
```

## Arranque del stack propio

Rust/Cargo 1.98.1, Docker/Compose y FFmpeg para el probe. Desde este directorio:

```sh
docker compose up --build -d
docker compose ps
curl --fail http://localhost:8080/health/ready
```

Los valores de Compose son fixtures de desarrollo. Solo inicia los tres componentes propios de SPEC-04; las URLs Core/Chat se configuran para conectar esos extremos en integración. Una emisión ya autorizada no depende del ACK de Discovery/Chat.

El arranque de PostgreSQL ejecuta `media-adapter/init-database.sh`: inicia el servidor estándar, espera su listener TCP final y crea el rol/base Media si faltan. Actualiza la contraseña del rol con `MEDIA_DATABASE_PASSWORD` en cada arranque, también con volumen existente. La base solo queda healthy después de esa preparación; no hay contenedor temporal de inicialización.

Para actualizar un stack anterior desde este mismo directorio, ejecutar `docker compose down` sin `-v`, seguido de `docker compose up --build -d --remove-orphans`. Se conserva el volumen y se retiran los contenedores anteriores del adaptador/job. `docker compose ps --all` debe mostrar únicamente `db`, `mediamtx` y `streaming`.

`streaming-service` inicia sus listeners públicos/privados y los del adaptador. Un fallo fatal del adaptador o de un worker de control termina el proceso con error; SIGTERM detiene ambos módulos de forma coordinada. Readiness del contenedor exige `/health/ready` en 8080 y en 8090; el segundo comprueba SQL Media, capacidad DLQ y Control API. MediaMTX depende de `service_started`, para evitar un ciclo de readiness. Reiniciar Streaming interrumpe también HLS; Core y Chat siguen siendo procesos independientes.

| Listener | Exposición del Compose | Uso |
| --- | --- | --- |
| Streaming 8080 | `127.0.0.1:8080` | API pública y health |
| Streaming 8091 | Solo red privada; adaptador usa loopback | Auth ingest, callbacks y contextos Core |
| Streaming/adaptador 8888 | `127.0.0.1:8888` | HLS público por sessionId |
| Streaming/adaptador 8090 | Solo red privada | Auth MediaMTX y health |
| MediaMTX 1935 | `127.0.0.1:1935` | RTMP |
| MediaMTX 8888/9997 | Solo red privada | HLS del motor/Control API |
| PostgreSQL 5432 | `127.0.0.1:5438` | Bases `streaming` y `streaming_media`, roles separados |

MediaMTX [1.21.1](https://github.com/bluenviron/mediamtx/releases/tag/v1.21.1) está fijado por digest. Solo RTMP/LL-HLS; sin grabación, transcoding ni reemplazo de publisher activo. El broadcaster usa su `rtmpUrl` y la clave privada como password RTMP, por ejemplo mediante los parámetros `user`/`pass` del cliente. La credencial de emisión se obtiene una sola vez y no se incluye en URLs públicas/eventos. El motor HLS usa un secreto CDN interno para evitar cookies o tokens de engine en el player anónimo.

El adaptador verifica publisher y estado vigente antes de servir `/hls/{sessionId}/...`; la pérdida/fin de sesión invalida el alias. Streaming confirma manifiesto, segmento y frame decodificado antes de LIVE. Reconexión preserva sesión/generación dentro de gracia y asigna sourceGeneration nueva.

## Configuración

`STREAMING_HTTP_PORT`, `STREAMING_HLS_PORT`, `STREAMING_RTMP_PORT` y `STREAMING_DB_PORT` permiten cambiar los puertos del host (8080, 8888, 1935 y 5438 por defecto), sin cambiar listeners internos. Las variables `MEDIA_*` se entregan ahora al contenedor Streaming; `MEDIA_STREAMING_URL=http://127.0.0.1:8091`, `MEDIA_STREAMING_PUBLIC_URL=http://127.0.0.1:8080` y `STREAMING_MEDIAMTX_HLS_INTERNAL_BASE_URL=http://127.0.0.1:8888`. MediaMTX autoriza contra `http://streaming:8090/internal/media/auth` en desarrollo.

| Variables | Uso |
| --- | --- |
| `STREAMING_ENV`, `STREAMING_BIND_ADDR`, `STREAMING_PRIVATE_BIND_ADDR` | Perfil y listeners |
| `STREAMING_DATABASE_URL`, `STREAMING_DATABASE_READ_URL` | Base privada; réplica opcional con fallback al primario |
| `STREAMING_DB_MIN_CONNECTIONS`, `STREAMING_DB_MAX_CONNECTIONS`, `STREAMING_DB_READ_MAX_CONNECTIONS` | Pools |
| `STREAMING_DB_ACQUIRE_TIMEOUT_SECONDS`, `STREAMING_DB_IDLE_TIMEOUT_SECONDS`, `STREAMING_DB_MAX_LIFETIME_SECONDS` | Límites del pool |
| `STREAMING_CORE_BASE_URL`, `STREAMING_CORE_SERVICE_TOKEN` | Contextos owner/catálogo y entrega Discovery |
| `STREAMING_CORE_CONSUMER_TOKEN` | Bearer de Core para contextos/snapshots privados |
| `STREAMING_CHAT_BASE_URL`, `STREAMING_CHAT_SERVICE_TOKEN` | Entrega de lifecycle a Chat |
| `STREAMING_SESSION_COOKIE_NAME`, `STREAMING_WEB_ORIGIN` | Cookie opaca y control de origen en mutaciones |
| `STREAMING_RTMP_INGEST_BASE_URL`, `STREAMING_PUBLIC_HLS_BASE_URL` | Endpoints de broadcaster/player |
| `STREAMING_MEDIAMTX_NODE_ID`, `STREAMING_MEDIAMTX_CONTROL_API_URL`, `STREAMING_MEDIAMTX_CONTROL_API_USERNAME`, `STREAMING_MEDIAMTX_CONTROL_API_PASSWORD` | Nodo y control del engine |
| `STREAMING_MEDIAMTX_HLS_INTERNAL_BASE_URL`, `STREAMING_MEDIA_ADAPTER_SERVICE_TOKEN` | Probe privado del adaptador y auth de ingest/callbacks |
| `STREAMING_VIEWER_LEASE_HMAC_KEY` | Tokens opacos de leases |
| `STREAMING_RUN_MIGRATIONS`, `STREAMING_MIGRATIONS_DATABASE_URL` | Migraciones de desarrollo / comando dedicado |
| `MEDIA_DATABASE_URL`, `MEDIA_DB_MAX_CONNECTIONS`, `MEDIA_RUN_MIGRATIONS` | Base/pool/migración técnica |
| `MEDIA_STREAMING_URL`, `MEDIA_STREAMING_PUBLIC_URL`, `MEDIA_STREAMING_TOKEN` | APIs privada/pública de Streaming |
| `MEDIA_CONTROL_URL`, `MEDIA_CONTROL_USER`, `MEDIA_CONTROL_PASSWORD`, `MEDIA_AUTH_TOKEN` | Control y callback de auth del engine |
| `MEDIA_HLS_URL`, `MEDIA_HLS_SECRET`, `MEDIA_BIND_ADDR`, `MEDIA_HLS_BIND_ADDR` | HLS interno y listeners del adaptador |
| `MEDIA_MAX_OPEN_DEAD_LETTERS` | Capacidad DLQ técnica, 10000 en P1 |

En producción se requieren secretos propios, TLS nativo privado/público y PostgreSQL con TLS requerido; las migraciones automáticas se deshabilitan. `STREAMING_TLS_CERT_FILE` y `STREAMING_TLS_KEY_FILE` configuran los certificados de los listeners. `MEDIA_STREAMING_URL` y `MEDIA_STREAMING_PUBLIC_URL` deben usar HTTPS también para llamadas al propio proceso: por ejemplo `https://live:8091` y `https://live:8080` en el Compose raíz. El hostname debe figurar en el SAN del certificado; `STREAMING_HTTP_CA_FILE` configura la CA adicional de confianza, sin desactivar la verificación. HTTP loopback solo se admite en desarrollo sin certificados TLS; habilitarlos exige HTTPS para ambas URLs también en desarrollo. Las demás URLs privadas siguen exigiendo HTTPS en producción. MediaMTX y PostgreSQL no se publican como APIs generales. La réplica de control P1 es única y retiene un lock exclusivo en una conexión PostgreSQL supervisada. Perder esa conexión invalida el clock y detiene el proceso. El arranque finaliza transaccionalmente las sesiones cuyo owner anterior perdió sus anchors. Varios owners requieren diseño y evidencia adicional de fencing/enrutamiento/clock.

## APIs

Públicas: crear/leer configuración de canal, leer stream/sesión, PATCH metadata, rotar clave offline, stop de sesión y creación/heartbeat/cierre de leases. Contratos canónicos en [datos y APIs](../../docs/contratos_modelo_datos.md).

Privadas:

- `POST /internal/streaming/ingest/authorize`.
- `POST /internal/streaming/sessions/{sessionId}/source-connected`, `/playback-ready`, `/source-lost`.
- `GET /internal/streaming/sessions/{sessionId}/context`.
- `POST /internal/streaming/channels/snapshots`.
- `POST /internal/streaming/discovery/snapshots`.

Core recibe snapshots completos mediante `POST /internal/core/discovery/stream-events`; Chat recibe lifecycle mediante `POST /internal/chat/session-events`. El corte Discovery pagina 1–50 items, conserva el mismo watermark, dura cinco minutos y devuelve `410 SNAPSHOT_EXPIRED` al vencer. Estado, metadata y conteo llevan versiones independientes. Timeline se calcula al consultar, sin eventos periódicos.

## Entrega y recuperación

Media: timeout 2 s; retries 100/250/500/1000/2000 ms y luego 2 s; ventana 15 min desde primer intento. Mismo eventId/payload en cada intento. 2xx ACK, 410 obsoleto; 4xx permanente o agotamiento pasa a DLQ durable. Alerta única por publisher/source a los 30 s. Cola, edad e intentos se registran. Al alcanzar capacidad DLQ, nueva ingesta falla cerrado. [Decisión operativa de entrega y capacidad](docs/adr/0001-media-callback-delivery.md).

Chat/Discovery: timeout 1 s; retries 1/2/5/10 s y luego 10 s; `Retry-After` admite segundos/fecha HTTP. ACK independiente por consumidor, alerta por atraso >5 s y DLQ a los 15 min/permanente. Cierre manual no fabrica ACK. Redrive conserva ID/payload y abre una nueva ventana. Las DLQ no expiran automáticamente.

```sh
# Con las variables del proceso configuradas:
streaming-service migrate
media-adapter migrate
streaming-service dead-letters list
streaming-service dead-letters redrive EVENT_ID --operator OPERATOR --reason NOTE
streaming-service dead-letters close EVENT_ID --operator OPERATOR --reason NOTE --confirm-irrecoverable
streaming-service delivery-dead-letters list
streaming-service delivery-dead-letters redrive EVENT_ID OPERATOR NOTE
streaming-service delivery-dead-letters close EVENT_ID OPERATOR NOTE
media-adapter dead-letter list
media-adapter dead-letter redrive EVENT_ID OPERATOR NOTE
media-adapter dead-letter close EVENT_ID OPERATOR NOTE
```

`dead-letters` opera fallos de procesamiento del inbox Streaming; `delivery-dead-letters` opera entregas a Chat/Discovery; `media-adapter dead-letter` opera callbacks técnicos. Redrive/cierre registran operador y motivo. Los comandos de Streaming migran con `STREAMING_MIGRATIONS_DATABASE_URL`; operaciones con `STREAMING_DATABASE_URL`.

## Verificación del módulo

```sh
make quality
make test
make release-check
make image
# Base desechable, pruebas SQL explícitas:
STREAMING_TEST_DATABASE_URL=postgres://... cargo test --all-targets --all-features --locked -- --include-ignored
```

La prueba de stack `python3 tests/smoke_stack.py` requiere Docker y Python 3. Usa un proyecto y puertos desechables; construye la imagen, verifica tres contenedores, video RTMP→HLS real, cierre coordinado, fallos de arranque, reinicios, migraciones, callbacks persistentes y cambio de contraseña Media sobre el mismo volumen. Elimina únicamente su proyecto/volumen al terminar. No sustituye la aceptación integrada Core/Chat/Web ni la carga de SPEC-13.

Las pruebas del módulo usan fixtures propios y bases/esquemas desechables. La aceptación del primer frame visible en Web, los receptores Core/Chat y la carga del sistema se verifica conforme a [SPEC-13](../../docs/spec-p1/spec_13_int.md).
