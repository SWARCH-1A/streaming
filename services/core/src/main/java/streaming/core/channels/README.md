# Core / Canales

Canales vive dentro del build y runtime de Core, bajo `streaming.core.channels`.

El módulo crea el canal inicial en la transacción de registro mediante `ChannelInitializer`, con FK y unicidad. También implementa el
bootstrap público por handle/owner (`ChannelQueries`), edición parcial serializada y portadas.
`JdbcChannels` escribe las tablas de Canales y consulta cuentas y perfiles mediante vistas públicas.
La sesión se valida con la interfaz local de Cuentas y la seguridad y CSRF compartidos de Core.

Rutas: GET `/api/channels/by-handle/{handle}`, `/by-owner/{userId}` y `/api/channels/{channelId}` (bootstrap compuesto),
PATCH `/api/channels/{channelId}`, POST `/api/channels/{channelId}/banner-uploads` (multipart file),
GET `/api/channels/banners/{key}` y `/api/channels/csrf`. Descripción hasta 500 puntos de código,
null limpia a cadena vacía. Portada JPEG/PNG/GIF real <=10 MB, 1200×480 recomendado; upload de un
uso ligado a owner/channel con caducidad de 15 min. Versión solo por cambio efectivo.

`ChannelBootstrapService` compone los datos locales con un batch autoritativo Streaming.
Si se confirma que el canal no tiene configuración, devuelve stream=null y OFFLINE con estado fresco.
Si Streaming falla, conserva el canal y el perfil con UNKNOWN. La creación del canal sigue dentro
de la transacción Core, sin provisión HTTP ni outbox propio.

## Seguimiento P2

`FollowApplicationService` implementa `p2.channels.*` y `p2.recording.preference-*` (contratos en
[p2.json](../../../../../../../../contracts/p2.json)). El seguidor sale siempre de la sesión, nunca del cuerpo.

- `channels.follows`: una fila por (seguidor, canal). PUT activa y DELETE desactiva sin borrar la fila;
  `follow_version` solo sube y se conserva tras unfollow. Repetir sin cambio no sube versión ni emite evento.
  No se sigue el propio canal (422).
- `Idempotency-Key` obligatoria en PUT/DELETE: se liga a usuario, operación y canal; repetir devuelve el mismo
  resultado durante 24 h y otro contenido con la misma clave da 409 (`channels.follow_operations`).
- `channels.follow_outbox`: el evento `channel.follow.changed.v1` se confirma en la misma transacción que la relación.
  `FollowOutboxDispatcher` lo publica en RabbitMQ (`p2.events`, cola `notifications.follow`) con confirmación del
  broker y reintenta con el mismo `eventId`. Con `CORE_EVENTS_TRANSPORT=http` los eventos esperan en la tabla.
- `GET /api/me/followed-channels`: `limit` 1..50 y cursor HMAC ligado al usuario, válido 1 h (410 al vencer).
- `POST /internal/core/notification-recipients` (solo `notifications` con `CORE_NOTIFICATIONS_SERVICE_TOKEN`):
  la primera página toma un corte por canal/sesión (`channels.recipient_snapshots`) que dura 1 h; las páginas
  siguientes leen ese mismo corte. Devuelve solo `userId`.
- Preferencia de autopublicación en `channels.channels` (`autopublish_enabled` true por defecto y
  `autopublish_version`). El dueño la cambia con `expectedVersion` (409 si cambió). Streaming la lee por
  `/internal/core/channels/{channelId}/vod-settings` con `CORE_RECORDING_SETTINGS_SERVICE_TOKEN`.
- Pendiente: CA-07 (canal suspendido) depende de Administración/P2-17. El agregado de pantalla lo compone el Gateway.

Pruebas: `FollowIT` (PostgreSQL real con Testcontainers).

Definición: [SPEC-03](../../../../../../../../docs/spec-p1/spec_03_channel.md),
[contratos](../../../../../../../../docs/contratos_modelo_datos.md),
[ADR-004](../../../../../../../../docs/adr/ADR-004-canales-en-core.md).
Configuración, comandos, almacenamiento y transición de datos: [Core](../../../../../../README.md).
