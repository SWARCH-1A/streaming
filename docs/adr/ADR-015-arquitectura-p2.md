# ADR-015: Presentaciones, Gateway, eventos y biblioteca VOD

- Estado: aceptada para el diseño P2; implementación pendiente
- Fecha: 2026-10-22
- Responsables: Core, Streaming, Chat, VOD, Notificaciones, Web, móvil e Integración
- SPEC: [SPEC P2](../spec-p2/README.md)
- Referencia vigente: [alcance P2](../alcance_p2.md)
- Ámbito P1 de referencia: ADR-005, ADR-007, ADR-010, ADR-011 y ADR-013…014

## Contexto

La plataforma debe ofrecer web SSR, una segunda presentación, comunicación/orquestación y
procesamiento asíncrono. La grabación, el replay y las notificaciones requieren almacenamiento
durable, consumidores independientes y reglas de disponibilidad compartidas.

## Decisión

1. Web usa React Router SSR; móvil, Kotlin Multiplatform y Compose Multiplatform. SSR entrega
   contenido inicial. Reproductor y WebSocket continúan en el cliente; las consultas periódicas
   actualizan estado y conteos dentro del límite nominal de cinco segundos.
2. Un API Gateway asume entrada HTTPS, rutas, agregación por pantalla, errores parciales y
   retransmisión de HLS/WS. El servidor SSR renderiza; el Gateway compone datos. Las reglas y
   escrituras de negocio permanecen en cada servicio.
3. La comunicación es híbrida: HTTP para consultas/autorización inmediata y un único broker
   para eventos/trabajos. Cada consumidor lógico tiene una suscripción independiente. Outbox,
   inbox, versiones, confirmación durable y deduplicación cubren reentregas y recuperación.
4. VOD posee biblioteca y persistencia privada en un contenedor independiente. Todas las
   calidades recibidas se almacenan en S3 externo; local usa un proveedor compatible configurable.
   El aviso de segmento disponible referencia un objeto cuya carga ya se confirmó.
5. El emisor codifica hasta cuatro calidades de una misma sesión. Media las empaqueta para
   selección HLS manual/automática, sin transcoding para generar variantes en el servidor.
6. Chat conserva la autoridad del historial, moderación y replay. Los mensajes necesarios para
   VOD no caducan cinco minutos después del fin. Replay usa un mapping explícito hacia el medio
   grabado y aplica las mismas supresiones que el chat en vivo.
7. Notificaciones posee bandeja, preferencias e intentos por destinatario/medio. El broker
   compartido distribuye trabajos independientes para bandeja, correo y push. SMTP y los
   proveedores de push son adaptadores de entrega; un fallo no revierte LIVE.
8. Core conserva las sesiones opacas. Web usa cookie/CSRF y móvil Bearer; ambos verifican
   expiración, revocación y suspensión. Suspender oculta canal y VOD y restringe sus recursos
   asociados. La reactivación conserva las decisiones previas de publicación/moderación.

## Opciones y límites

- React Router SSR permite conservar React y el enrutamiento de la aplicación. Next.js permite
  alojamiento propio, pero no es la selección de P2.
- Codificación en origen evita generar cuatro rendiciones por emisión en el servidor. Requiere
  capacidad de codificación y subida del emisor y empaquetado multicalidad comprobado.
- La mensajería desacopla consumidores y trabajos. Las operaciones que necesitan una respuesta
  actual siguen usando HTTP; los eventos de proyección no conceden permisos.
- VOD agrupa metadatos, publicación y referencias audiovisuales. La base privada puede compartir
  un servidor físico con otra base si conserva credenciales y propiedad; su despliegue se decide
  junto al inventario de componentes exigido por la asignatura.
- MinIO y RustFS son candidatos locales. Los runtimes de Gateway/VOD
  y sus motores nuevos corresponden a sus responsables. Notificaciones selecciona Node.js 24/TypeScript,
  PostgreSQL y SMTP en [ADR-020](ADR-020-notificaciones-typescript-postgresql-smtp.md). [ADR-016](ADR-016-rabbitmq-eventos-p2.md)
  selecciona posteriormente RabbitMQ para el broker.

## Consecuencias

La entrega requiere un runtime SSR, aplicación móvil, agregación HTTP, broker y consumidores
durables. La pérdida de una sección secundaria se presenta sin eliminar el contenido disponible.
Las autorizaciones fallan cerradas si no pueden comprobarse. Cada productor/consumidor necesita
reintentos e idempotencia; el ACK del broker no demuestra que VOD o Notificaciones hayan terminado.

Las credenciales S3 permanecen en servicios. Las rutas de reproducción deben respetar suspensión
y publicación, también al acceder directamente a manifiestos, segmentos, pistas y replay.
FCM/APNs son dependencias externas admitidas; su configuración sigue pendiente.

## Contratos y especificaciones

Las responsabilidades existentes conservan su ID de SPEC; los componentes nuevos tienen SPEC
particulares en [índice P2](../spec-p2/README.md). SPEC-15 verifica integración, SPEC-16 define
Gateway, SPEC-17 broker y entrega durable, SPEC-18 SSR, SPEC-19 móvil y SPEC-20 Notificaciones.

La fuente [contracts/p2.json](../../contracts/p2.json) amplía progresivamente schemas P1. La
cobertura actual incluye el registro de eventos y los payloads de ciclo/Discovery; el resto
de operaciones y payloads P2 sigue pendiente.
[HTTP/WS](../contratos_p2_http.md), [eventos](../contratos_p2_eventos.md) y
[S3/timeline](../contratos_p2_media.md) fijan semántica, permisos, fallos y recuperación.
Las firmas con políticas delegadas siguen identificadas en revisión o bloqueadas.

## Verificación

Comprobar HTML SSR con datos reales, hidratación sin duplicación de conexiones, autenticación
web/móvil y revocación, fallos parciales del Gateway, HLS/WS por su entrada, cuatro rendiciones
de una sesión, selección automática/manual y conservación en S3. Probar reentrega de eventos,
consumidor detenido, replay tras reinicio, eliminación/moderación y suspensión por URL directa.

El MediaMTX 1.21.1 fijado en el repositorio selecciona una pista de video para HLS; la prueba
multicalidad debe resolver esa limitación. Fuentes: [OBS multitrack en MediaMTX](https://mediamtx.org/docs/publish/obs-studio)
y [conversor HLS de v1.21.1](https://github.com/bluenviron/mediamtx/blob/v1.21.1/internal/protocols/hls/from_stream.go).

## Revisión

Revisar con consumidores al cerrar las [decisiones pendientes](../decisiones_pendientes_p2.md),
publicar los schemas P2 o cambiar propietario de datos, autenticación, almacenamiento o protocolo.
