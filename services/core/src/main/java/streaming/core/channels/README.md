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

Definición: [SPEC-03](../../../../../../../../docs/spec-p1/spec_03_channel.md),
[contratos](../../../../../../../../docs/contratos_modelo_datos.md),
[ADR-004](../../../../../../../../docs/adr/ADR-004-canales-en-core.md).
Configuración, comandos, almacenamiento y transición de datos: [Core](../../../../../../README.md).
