# Core / Canales

Canales vive dentro del build y runtime de Core, bajo `streaming.core.channels`.

Implementado: canal inicial en la transacción de registro mediante `ChannelInitializer`, FK/unicidad,
bootstrap público por handle/owner (`ChannelQueries`), edición parcial serializada y portadas.
`JdbcChannels` escribe las tablas del módulo y compone cuentas/perfiles mediante vistas públicas.
La sesión se valida con la interfaz local de Cuentas y la seguridad/CSRF comunes de Core.

Rutas: GET `/api/channels/by-handle/{handle}` y `/by-owner/{userId}` (bootstrap compuesto),
PATCH `/api/channels/{channelId}`, POST `/api/channels/{channelId}/banner-uploads` (multipart file),
GET `/api/channels/banners/{key}` y `/api/channels/csrf`. Descripción hasta 500 puntos de código,
null limpia a cadena vacía. Portada JPEG/PNG/GIF real <=10 MB, 1200×480 recomendado; upload de un
uso ligado a owner/channel con caducidad de 15 min. Versión solo por cambio efectivo.

Composición de estado/stream de Emisiones pendiente hasta implementar SPEC-04; por ahora stream=null
para canales sin configuración. No provisión HTTP, cerca, proyección de eventos ni outbox de Canales.

Definición: [SPEC-03](../../../../../../../../docs/spec-p1/spec_03_channel.md),
[contratos](../../../../../../../../docs/contratos_modelo_datos.md),
[ADR-004](../../../../../../../../docs/adr/ADR-004-canales-en-core.md).
Configuración, comandos, almacenamiento y transición de datos: [Core](../../../../../../README.md).
