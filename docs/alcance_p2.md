# Alcance del prototipo 2

**Estado:** definición para desarrollo. **Cierre de código:** 30 de octubre de 2026.
**Entrega:** 2 de noviembre. **Presentación:** 3 de noviembre. **Equipo:** seis integrantes.

## Funciones

P2 incluye las funciones de P1 y todos los RF del catálogo salvo RF-038…RF-045:
pagos, suscripciones de pago y acceso premium. Incluye recuperación de contraseña, seguimiento,
calidades, moderación, watch parties, notificaciones, VOD, subtítulos y administración.
Los requisitos nuevos cubren autopublicación, publicación manual y chat replay.

Watch parties y subtítulos conservan sus requisitos funcionales. Subtítulos se concreta como carga
de WebVTT por propietario para VOD (SPEC-22); no incluye transcripción ni directo. Las interfaces y
dependencias de integración se mantienen en [decisiones pendientes](decisiones_pendientes_p2.md).
Una pregunta pendiente no equivale a una función excluida de la entrega.

## Presentaciones

La web usa React Router en modo framework con SSR. El servidor obtiene del Gateway los datos
iniciales y entrega HTML con contenido. El navegador hidrata la interfaz y mantiene reproductor,
WebSocket y actualizaciones posteriores. Estado del canal, disponibilidad y conteos se actualizan
mediante consultas periódicas; bajo operación normal los cambios deben reflejarse en cinco segundos.

La aplicación móvil usa Kotlin Multiplatform y Compose Multiplatform. Está orientada al espectador:
descubrimiento, autenticación, live, VOD, chat, seguimiento y bandeja de notificaciones. Android es
la plataforma obligatoria de demostración; la cobertura de iOS requiere compilación y pruebas reales.
La emisión desde la cámara del teléfono queda fuera del alcance móvil de P2.

Web usa la cookie de sesión opaca de Core. Móvil reutiliza ese modelo mediante Bearer y
almacenamiento seguro del dispositivo. Expiración, revocación y suspensión se verifican en Core.

## Entrada y comunicación

El API Gateway es la entrada HTTP pública: HTTPS, rutas, agregación por pantalla y retransmisión
de WebSocket y HLS. Web SSR y móvil solicitan datos compuestos. El Gateway conserva errores por
sección; una dependencia secundaria caída no hace desaparecer la pantalla completa. Ausencia de
datos, indisponibilidad y autorización denegada tienen significados diferentes.

Cada servicio conserva sus reglas y datos. El Gateway no escribe bases ajenas ni convierte una
operación distribuida en una transacción. Autorización y consultas inmediatas usan HTTP; los
eventos y trabajos asíncronos usan un único broker interno. Su producto y configuración corresponden
al responsable. Core y Chat tienen suscripciones independientes a los eventos de Streaming.

## Emisión y calidades

Cada sesión admite hasta cuatro calidades producidas por el emisor. La plataforma ofrece las
que recibe, con selección manual y automática. Una fuente 720p no produce una opción 1080p.
Todas las variantes comparten `sessionId`, línea temporal y cupo de emisión.

El servidor recibe, empaqueta y entrega esas variantes sin generar calidades mediante transcoding.
Streaming/Media define y verifica la integración OBS, ingesta multitrack, segmentos alineados y
manifiesto maestro HLS. La versión MediaMTX fijada en el código no demuestra por sí sola ese flujo.

Se conservan una sesión activa por canal, cinco sesiones simultáneas, gracia de reconexión de
30 segundos y conteo por leases. Una reconexión dentro de la gracia conserva sesión, sala y VOD.

## VOD y almacenamiento

VOD tiene contenedor, API y persistencia privada propios. Es dueño de la biblioteca, metadatos,
estado de publicación, variantes, manifiestos y referencias a los objetos. Streaming controla
la emisión y Media produce los artefactos audiovisuales. Chat conserva los mensajes.

Los segmentos de todas las calidades recibidas se conservan en S3 externo. El desarrollo local
usa un servicio compatible con S3, con endpoint configurable. MinIO y RustFS son candidatos;
la selección corresponde al responsable. El almacenamiento local de desarrollo no define el
proveedor del despliegue. Los eventos transportan referencias a objetos confirmados, no video.

La grabación es automática. Cada canal tiene una preferencia de autopublicación, activada por
defecto. Su valor se copia al iniciar la sesión; los cambios afectan emisiones posteriores.
Una grabación validada se publica al finalizar si esa sesión tiene autopublicación. En otro caso
queda pendiente y el propietario puede editarla, publicarla o eliminarla.

Los VOD se conservan hasta eliminación manual. Los subtítulos asociados y el chat replay tienen
la misma vida útil. El borrado retira el acceso y genera limpieza recuperable de objetos y datos
asociados. Los segmentos faltantes impiden anunciar una grabación completa y su autopublicación.

## Chat, replay y moderación

Chat ofrece mensajes durante la emisión y consulta sincronizada durante VOD. Posee el historial
durable, el orden por sesión y las decisiones de moderación. Su almacenamiento y mecanismo de
archivo se eligen por el responsable; ningún proceso externo consulta sus tablas.

Los mensajes confirmados necesarios para replay se conservan mientras exista el VOD. El fin de
la emisión cierra la escritura; no inicia una eliminación automática de esos mensajes. VOD y Chat
comparten un contrato que relaciona tiempo del medio con sesión, generaciones y secuencia.

El streamer puede eliminar mensajes y, si lo desea, los mensajes de un usuario. El borrado es
una operación explícita y se aplica tanto al chat en vivo como al replay. Bloquear por sí solo
impide nuevas publicaciones; no elimina mensajes previos.

El bloqueo tiene ámbito de canal y puede ser permanente hasta retiro manual o temporal con
vencimiento. El streamer elige la modalidad y puede retirarlo. Chat comprueba su vigencia al
aceptar cada mensaje, también después de reiniciar; no depende únicamente de un temporizador.
El responsable de Chat concreta selección de mensajes, alcance del borrado por usuario y
formato de duración antes de cerrar sus contratos.

## Notificaciones

Notificaciones mantiene bandeja, leído, preferencias, dispositivos e intentos de entrega.
Consume eventos del broker y decide destinatarios y medios: bandeja, correo y push al teléfono.
Comparte el broker interno y usa colas independientes por consumidor y estrategia de entrega.

Los avisos habilitados se guardan en la bandeja. Correo y push son opcionales, inicialmente
desactivados; hay preferencias por canal seguido y un interruptor general por medio. El inicio
de una sesión genera un aviso lógico por destinatario; una reconexión de la misma sesión no
genera otro. Un fallo de push no provoca reenvío de un correo ya confirmado ni bloquea la bandeja.

SMTP está disponible. La configuración de proveedores y dispositivos de push está pendiente.
FCM/APNs son dependencias externas de entrega admitidas; los servicios de aplicación se alojan
en infraestructura propia. Recuperación de contraseña utiliza la entrega por correo.

## Administración y disponibilidad

Suspender una cuenta invalida sus sesiones, impide nuevas acciones autenticadas y publicaciones
de chat, y ordena detener su emisión activa. Su canal aparece como no disponible y sus VOD,
subtítulos y replay dejan de ser accesibles, también por URLs directas. Las consultas públicas
y los mecanismos de entrega comprueban esa restricción.

Los datos se conservan. La reactivación recupera su disponibilidad previa, respetando borradores,
eliminaciones y retiradas administrativas. El rol administrativo se provisiona explícitamente;
el registro público no permite asignárselo. La propagación y la autorización de reproducción
durante fallos deben concretarse en los contratos de Core, Gateway, Streaming y VOD.

## Restricciones y aceptación

El enunciado P2 exige dos presentaciones diferentes, web SSR, componentes de lógica al menos
iguales al número de integrantes, comunicación/orquestación, cuatro componentes de datos con
relacional y NoSQL, procesamiento asíncrono, conectores HTTP, cinco lenguajes y despliegue
orientado a contenedores. El artefacto incluye C&C, despliegue, capas y descomposición, con
descripción y comandos de ejecución, además de equipo, nombre, logo y descripción del proyecto;
fuente Markdown y entrega `p2_X.pdf`.

Java, Rust, Go, TypeScript y Kotlin cubren los cinco lenguajes cuando existan artefactos ejecutables.
El inventario de procesos y almacenes debe justificar el resto de mínimos. El código actual y
las pruebas P1 son la base ejecutable; este documento define P2 y no acredita su implementación.

Fuentes: enunciado **[SwArch_2026ii] - Project - Prototype 2.pdf**, páginas 1–2, en el material
de la asignatura; [catálogo](catalogo_requisitos.md), [arquitectura](arquitectura_c4_cnc_despliegue.md),
[SPEC P2](spec-p2/README.md) y [ADR-015](adr/ADR-015-arquitectura-p2.md).
