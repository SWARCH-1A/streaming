# Channels backend

Responsabilidad: lógica del dominio channels. Mantén aquí su implementación y sus pruebas locales.

Especificación canónica: docs/spec-p1/spec_03_channel.md (SPEC-03). Datos, errores e interacciones con otros dominios siguen docs/contratos_modelo_datos.md y SPEC-10/SPEC-11. No accedas directamente al almacenamiento de otro módulo.

Channels es un proceso independiente y dueño de `channelId`, `ownerUserId`, `description`, portada (`bannerUri`) y `channelVersion`. `ownerUserId` es una referencia externa a Identity; el handle, nombre visible, avatar y estado de la emisión pertenecen a Identity, Profile y Streaming. Decisiones en [ADR-003](../../docs/adr/ADR-003-channels-persistencia-provision-y-proyeccion.md).

## Arquitectura del módulo

- `api/` adapta REST público, REST interno y multipart; `application/` aplica provisión, lectura, edición y proyección; `domain/` contiene reglas sin dependencias; `infrastructure/` implementa PostgreSQL, el cliente HTTP de Identity y el almacenamiento de portadas; `security/` valida tokens de servicio, CORS/CSRF y registra cada petición.
- Provisión: un canal por cuenta, idempotente por `registrationId`, con cerca `pendingUntilUtc` y lookup terminal para que Identity compense sin dejar canales huérfanos.
- Lectura pública: consulta Identity para admitir solo cuentas ACTIVE; PENDING, EXPIRED o desconocida dan el mismo 404. Identity caído produce 503.
- Estado LIVE/OFFLINE: proyección de lectura alimentada por eventos de Streaming, deduplicados por `eventId` y ordenados por `streamGeneration`/`sessionVersion`, `metadataVersion` y `countVersion`.
- Operaciones protegidas: introspección de la cookie de sesión en Identity por cada petición; solo el owner edita.
- PostgreSQL 18 guarda canales, cercas, uploads, proyección, eventos procesados y outbox (`ChannelProvisioned`, `ChannelChanged`); Flyway crea el esquema `channels`.

## Contrato P1 implementado

Público (a través del reverse proxy):

- `GET /api/channels/by-owner/{userId}`: canal público con `status` (`LIVE`/`OFFLINE`) y `activeStream` (o `null`).
- `PATCH /api/channels/{channelId}`: owner edita `description` (≤500, `null` limpia) y `bannerUploadId` (`null` retira).
- `POST /api/channels/{channelId}/banner-uploads`: multipart `file`, JPEG/PNG/GIF ≤10 MB; devuelve `uploadId` válido 15 minutos.
- `GET /api/channels/banners/{key}`: sirve portadas publicadas.
- `GET /api/channels/csrf`: token CSRF previo a `PATCH` y uploads.

Interno (solo red privada, nunca por el proxy público):

- `POST /internal/channels/provision`: solo Identity. `201` al crear, `200` al repetir y `410 REGISTRATION_EXPIRED` si venció.
- `GET /internal/channels/provisions/{registrationId}`: solo Identity. Devuelve `PROVISIONED` con `channelId`, o `ABSENT` terminal.
- `DELETE /internal/channels/provisions/{registrationId}`: solo Identity. Idempotente, `204`.
- `POST /internal/channels/stream-events`: solo Streaming. Recibe el sobre común de eventos `StreamSession*`, `StreamMetadataUpdated` y `ViewerCountChanged`.

## Ejecución local

Puerto `8083`; health en `/actuator/health`. Requiere Java 25, PostgreSQL 18 y un directorio persistente montado en `CHANNELS_BANNER_STORAGE`. Ejecuta `./mvnw spring-boot:run`; `./mvnw -B package` genera el JAR y `docker build -t streaming-channels services/channels` la imagen.

Variables:

- `CHANNELS_DB_URL`, `CHANNELS_DB_USER`, `CHANNELS_DB_PASSWORD`
- `IDENTITY_INTERNAL_URL`, `CHANNELS_IDENTITY_TOKEN`
- `CHANNELS_INTERNAL_SERVICE_TOKENS` (formato `identity=<token>,streaming=<token>`)
- `CHANNELS_BANNER_STORAGE`, `CHANNELS_BANNER_PUBLIC_BASE`
- `CHANNELS_SECURE_COOKIE`, `WEB_ORIGIN`

El token `identity` debe coincidir con `IDENTITY_CHANNELS_TOKEN` de Identity, y `CHANNELS_IDENTITY_TOKEN` con la credencial `channels` de `IDENTITY_INTERNAL_SERVICE_TOKENS`. Los valores por defecto son solo para desarrollo local. En red, usa HTTPS/TLS y cookies Secure.

## Pendiente de Integración

- Transporte que entrega los eventos de Streaming a `/internal/channels/stream-events` (o un broker equivalente).
- Despachador del outbox.
- Rutas del reverse proxy y volumen/servicio en el despliegue.
- Pantalla del canal en `apps/web/modules/channels/`, que depende del shell web, aún sin framework definido.
