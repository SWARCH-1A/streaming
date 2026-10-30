# Streaming y Media — SPEC-04

Streaming controla las emisiones en Rust y guarda su estado en PostgreSQL mediante SQLx
con pools de conexiones. MediaMTX recibe RTMP y entrega LL-HLS.

El stack P1 tiene tres contenedores: PostgreSQL, MediaMTX y `streaming-service`.
El proceso Rust ejecuta el control y el adaptador Media en el mismo runtime Tokio,
según [ADR-011](../../docs/adr/ADR-011-streaming-tres-contenedores-p1.md).
`media-adapter` se conserva como CLI para migraciones y operación de callbacks, sin iniciar servidor.

## Responsabilidades

Streaming administra la configuración, las claves, las sesiones y generaciones, los cupos,
el reloj, los leases de reproducción y los snapshots públicos. MediaMTX transporta audio y video.
El adaptador autoriza las fuentes, bloquea aliases HLS antiguos y entrega callbacks durables.

Streaming y el adaptador mantienen una base privada y una migración inicial cada uno.

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

MediaMTX [1.21.1](https://github.com/bluenviron/mediamtx/releases/tag/v1.21.1) está fijado por digest en el perfil P1, sin grabación/transcoding ni reemplazo de publisher activo. La instalación raíz P2 compila el mismo commit con la grabación nativa de ADR-022. En OBS, usar Servicio: Personalizado y desactivar «Usar autenticación»: copiar `rtmpUrl` como servidor base (por ejemplo `rtmps://localhost:11938/live`) y únicamente `streamKey` (`sk_...`) como clave de retransmisión. Streaming resuelve la emisión por el hash de la clave. El adaptador no persiste la ruta privada `live/{streamKey}`; la obtiene transitoriamente de la Control API y la vincula al publisher autorizado. HLS público usa `sessionId`, nunca la clave. Los logs de MediaMTX están desactivados para evitar paths secretos; el adaptador conserva readiness y callbacks. La credencial se obtiene una sola vez y no se incluye en URLs públicas, eventos ni storage Web. El motor HLS usa un secreto CDN interno para evitar cookies o tokens de engine en el player anónimo.

El adaptador verifica publisher y estado vigente antes de servir `/hls/{sessionId}/...`; la pérdida/fin de sesión invalida el alias. Streaming confirma manifiesto, segmento y frame decodificado antes de LIVE. Reconexión preserva sesión/generación dentro de gracia y asigna sourceGeneration nueva.

## Consulta de calidades (inicio de P2)

`GET /api/streams/sessions/{sessionId}/renditions` devuelve `P2Renditions`: de una a cuatro
variantes del maestro HLS actual, con IDs estables, resolución, bitrate y códecs declarados.
Usa el endpoint interno HLS y token del adaptador ya configurados; no requiere migración SQL
ni otra configuración. Verifica playlists con segmentos anunciados y revalida la sesión antes
de responder. No consulta ni cambia cupos, publishers o leases. Respuestas sin caché; 404 para
sesión inexistente, 503 para sesión no reproducible/medio inválido y 504 tras dos segundos.

La ingesta del perfil actual sigue ofreciendo una sola pista de vídeo HLS. Esta consulta y el
selector Web preparan RF-028…030; no acreditan recepción de cuatro calidades ni sincronización
audiovisual. La solución de empaquetado queda en P2-08 de [SPEC-04](../../docs/spec-p2/spec_04_stream.md).

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

Los callbacks Media tienen timeout de 2 s. Se reintentan tras 100, 250, 500, 1000 y 2000 ms,
y después cada 2 s, durante 15 min desde el primer intento. Conservan eventId y payload.
Un 2xx confirma el ACK y un 410 indica que el callback quedó obsoleto. Un 4xx permanente o el
agotamiento del plazo envía el evento a la DLQ durable.

A los 30 s se emite una alerta única por publisher/source. Se registran cola, edad e intentos.
Al alcanzar la capacidad DLQ se rechazan nuevas ingestas. La
[decisión operativa](docs/adr/0001-media-callback-delivery.md) fija la entrega y su capacidad.

Las entregas a Chat y Discovery tienen timeout de 1 s y reintentos tras 1, 2, 5 y 10 s,
y luego cada 10 s. `Retry-After` admite segundos o fecha HTTP. Cada consumidor confirma su
propio ACK. Un atraso mayor de 5 s genera alerta; un fallo permanente o 15 min sin entrega
lleva el evento a DLQ.

El redrive conserva ID y payload y abre una ventana nueva. Un cierre manual registra la
resolución sin generar ACK. Las DLQ no expiran automáticamente.

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

## Transporte P2 por RabbitMQ

STREAMING_EVENTS_TRANSPORT=http (por defecto) conserva publicadores HTTP P1. Con amqp, STREAMING_BROKER_URL (AMQPS) y STREAMING_BROKER_CA_FILE son obligatorios. Los dos relays publican envelopes P2 persistidos en el outbox, con mandatory/confirm antes de marcar publicados. Reintentan caídas del broker sin agotar la ventana P1 de quince minutos. Las consultas Core/Streaming siguen por HTTP.

Ver [perfil compartido](../../infra/broker/README.md) y [ADR-016](../../docs/adr/ADR-016-rabbitmq-eventos-p2.md).

## Integración VOD

En el arranque raíz, `STREAMING_RECORDING_ENABLED=true` activa la copia de preferencia Core al
reservar una sesión nueva. `STREAMING_RECORDING_CORE_URL` apunta al listener privado Core y
`STREAMING_RECORDING_SETTINGS_TOKEN` es la credencial limitada a vod-settings. La copia y su versión
se conservan en `recording_sessions`; reconectar no consulta ni reemplaza la preferencia. Si Core
no puede confirmarla, el inicio falla sin asumir autopublicación.

Media consulta snapshots/ACK por el listener privado con su Bearer técnico. La cola de captura y
entrega está en el worker `media-recorder`, descrito en [VOD](../vod/README.md). El header privado
`X-Source-Generation` cerca lecturas HLS frente a reconexiones. No se comparte ninguna tabla con VOD.
`MEDIA_RECORDING_ENABLED=true` emite las cabeceras privadas `X-Media-Recording-Session` y
`X-Media-Recording-Source-Generation` al autorizar al publisher. `P2MediaRecordingBinding` amplía
la respuesta204 de `media.auth`; el motor consume ese binding por ID de publisher, una vez,
sin cambios de configuración durante la autorización ni nombres de archivo con la clave.
La imagen MediaMTX fijada incluye inventario durable y cierre sincrónico por generación; su
volumen usa sesión/generación, sin claves de ingesta. La captura H264/AAC de una calidad conserva
el último archivo; SIGKILL sin sello no publica por ENDED. Las cuatro calidades reales y su
aceptación audiovisual siguen en P2-08. Ver [ADR-022](../../docs/adr/ADR-022-vod-go-postgresql-minio.md).
