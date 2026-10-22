# Flujos de integración — P2

Las políticas de [alcance P2](alcance_p2.md), [contratos](contratos_modelo_datos.md) y
[ADR-015](adr/ADR-015-arquitectura-p2.md) rigen estos flujos. Las APIs y tecnologías aún abiertas figuran en
[decisiones pendientes](decisiones_pendientes_p2.md).

## Registro, sesión y seguimiento

Core confirma cuenta, perfil y canal en una transacción local. El registro no depende de una
emisión ni del broker. La sesión opaca usa cookie/CSRF en web y Bearer en móvil, con autoridad
común de expiración, revocación y suspensión. La recuperación entrega un token de un uso por SMTP;
su formato, vigencia y cuotas deben cerrarse por Cuentas.

Canales posee follow/unfollow. Notificaciones obtiene destinatarios mediante un contrato o
proyección alimentada por eventos; la relación autoritativa sigue en Core. Las preferencias
por canal/medio son explícitas y la entrega no bloquea la transacción de seguimiento.

## Inicio, reconexión y proyección

Streaming valida síncronamente identidad, propiedad y catálogo para comandos protegidos.
Reserva un solo cupo para la emisión y sus hasta cuatro rendiciones. Media confirma fuente
reproducible antes de LIVE. Streaming confirma estado e intención de evento en su transacción;
el broker entrega a suscripciones independientes de Core, Chat y los demás consumidores.

Core actualiza su proyección pública; Chat actualiza el ciclo de sala. Ninguno autoriza nuevos
mensajes a partir de un evento viejo. Discovery no consulta Streaming por cada fila de búsqueda.
El Gateway compone estado y contenido por pantalla, y el cliente refresca dentro del presupuesto
nominal total de cinco segundos.

La pérdida de fuente conserva sessionId y chat durante 30 segundos de gracia. Volver dentro de
la ventana continúa esa emisión; volver después inicia otra. Fin voluntario o vencimiento cierra
la sesión y publica su estado. Una reconexión de la misma sesión no repite la notificación inicial.

## Segmentos y VOD

El proceso de captura carga cada segmento en S3 y verifica el resultado antes de publicar su
referencia. El broker transporta identidad opaca, sesión, rendición, orden y metadatos suficientes
para dedupe y validación; no transporta video ni claves de ingesta. Los schemas están en los
[contratos de eventos](contratos_p2_eventos.md) y [de media](contratos_p2_media.md).
El escritor concreto y su integración se resuelven entre Streaming/Media y VOD en P2-09.

VOD consume con su propia suscripción, persiste referencias idempotentes y detecta huecos,
versiones y discontinuidades. Finalizar la emisión no acredita que todos los segmentos estén
procesados. Solo publica tras comprobar el conjunto y el medio reproducible. Conserva todas
las calidades recibidas, sin crear calidades superiores ni usar disco local como archivo final.

La preferencia de autopublicación del canal se copia al iniciar la sesión. Activada por defecto,
publica al completar la validación; desactivada conserva borrador privado. El propietario puede
editar/publicar el borrador y eliminar manualmente el VOD. La retirada de acceso precede a la
limpieza reintentable de objetos, pistas y replay; no hay transacción global entre almacenes.

## Chat, moderación y replay

Cada mensaje nuevo verifica sesión vigente y contexto actual de la emisión mediante HTTP
síncrono, además del bloqueo de canal administrado por Chat. Se preservan orden, cuota global,
dedupe y persistencia antes del ACK. WebSocket distribuye mensajes al navegador y móvil sin
pasar por composición de respuestas.

El streamer puede bloquear temporalmente con vencimiento o permanentemente hasta retirar
el bloqueo. Puede eliminar mensajes concretos o solicitar explícitamente el borrado de mensajes
de un usuario; bloquear por sí solo no elimina su historial. La supresión se aplica a live y replay.
El contrato concreto de selección y alcance del borrado lo cierra Chat.

Chat conserva los mensajes necesarios mientras exista el VOD. El player consulta ventanas por
posición y cursor, y repite la consulta al hacer seek. Chat y VOD definen el mapping entre reloj
de sesión y tiempo de video, incluidos huecos de reconexión; streamOffsetMs no demuestra por sí
solo sincronización con el archivo. Las pistas de subtítulos heredan la disponibilidad del contenido.

## Notificaciones por estrategia de envío

Una nueva sesión LIVE genera un aviso lógico por seguidor destinatario. Notificaciones conserva
bandeja, preferencias, dispositivos e intentos y distribuye trabajos por medio en el mismo broker.
Bandeja, correo y push tienen reintentos independientes: el fallo de push no reenvía el correo
ya entregado. Dedupe cubre reentregas del evento y de cada intento.

Bandeja es el medio inicial. Correo y push requieren activación; las preferencias por canal se
combinan con el interruptor general de cada medio. SMTP está disponible; proveedor, credenciales
y dispositivos de prueba push deben provisionarse. Un fallo de entrega no revierte LIVE.

## Suspensión y acceso

Core revoca sesiones e impide nuevos accesos de una cuenta suspendida. Streaming detiene su
emisión y rechaza otra; Chat impide escritura. Canal y VOD, incluidas pistas y replay, quedan
indisponibles. Gateway y proveedores de contenido deben impedir que un enlace directo a S3
o HLS eluda esa política. El protocolo de propagación y el comportamiento ante fallos deben
cerrarse antes de declarar cumplido RF-079.

Reactivar conserva datos y recupera la disponibilidad previa, respetando borradores, borrados
y supresiones. El rol administrativo se provisiona explícitamente, fuera del registro público.

## Fallos y recuperación

Cada consumidor tiene confirmación durable, dedupe, versiones, reintentos y DLQ/redrive.
Una cola de VOD detenida no bloquea la suscripción de Chat. Un ACK del broker no demuestra
publicación VOD ni entrega al dispositivo. Las consultas y verificaciones inmediatas conservan
HTTP y tiempos acotados; mensajería no obliga a volver asíncrona cada interacción.

El Gateway presenta fallos por sección sin convertirlos en vacíos. Fallos de autorización se
rechazan; los snapshots públicos no sustituyen identidad ni permisos. La prueba integrada
incluye duplicados, desorden, caída de consumidores, reinicios y pérdida de una respuesta
con commit confirmado, según [SPEC-15](spec-p2/spec_15_int.md).
