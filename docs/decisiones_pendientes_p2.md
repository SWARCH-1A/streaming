# Decisiones y aceptación pendiente de P2

Las políticas vigentes están en [alcance P2](alcance_p2.md). Una decisión aceptada fija el diseño;
las SPEC distinguen implementación, evidencia ejecutada y comprobaciones pendientes. La revisión
de integración del 2026-11-03 conserva todos los RF incluidos y el despliegue local del prototipo.

| ID | Responsable | Definición vigente y aceptación restante |
| --- | --- | --- |
| P2-01 | Watch parties | Resuelta y ratificada por integración: sala privada por código, cuenta activa, crea cualquier usuario, modifica solo propietario. [ADR-025](adr/ADR-025-watch-parties-en-core.md). |
| P2-02 | Watch parties | Resuelta: hasta cuatro fuentes sin duplicados; rejilla cliente, estado real de fuente terminada/suspendida hasta retirada; la sala no depende de la conexión del propietario. |
| P2-03 | Watch parties | Resuelta: `OPEN` → `CLOSED` terminal, sin sincronía, chat propio, invitación nominal ni mezcla. SPEC-21 registra API, cuatro emisiones reales, navegador, leases y reinicio. Móvil no forma parte de esa entrega. |
| P2-04 | Subtítulos / accesibilidad | Resuelta: solo VOD, pistas preparadas por propietario; proveedor y reproducción Web/Android reales verificados. SPEC-22 conserva pendientes lectura asistiva, iOS ampliado y prueba combinada de pista/cuatro calidades. |
| P2-05 | Subtítulos / accesibilidad | Perfil ratificado `p2-webvtt-text-v1`: carga UTF-8, límites, idioma y mapping en [ADR-018](adr/ADR-018-subtitulos-vod-webvtt.md)/SPEC-22; validación Go/Python y consumidores Web/Android. |
| P2-06 | Gateway | Resuelta: Go 1.26, Caddy TLS/HTTP/WS/HLS, presupuesto JSON 3 s, fallos parciales y agregados canal/watch/VOD/exploración. SSR y móvil ya los consumen. [ADR-023](adr/ADR-023-gateway-go-caddy.md); carga sostenida pendiente. |
| P2-07 | Mensajería / integración | RabbitMQ/AMQP seleccionado en [ADR-016](adr/ADR-016-rabbitmq-eventos-p2.md), nueve familias y dieciséis suscripciones con efecto (ADR-027). VOD, replay, seguimiento/bandeja y SMTP ejecutados con proveedores reales. Infraestructura: TLS, ACL, reinicio, DLQ/redrive/close y auditoría verificados. SPEC-17 mantiene las ventanas exactas de fallo de todos los consumidores y carga pendientes. |
| P2-08 | Streaming / Media | Implementada por [ADR-026](adr/ADR-026-multicalidad-enhanced-rtmp.md): una conexión Enhanced RTMPS, hasta cuatro H.264 y AAC, HLS/captura independientes sin transcoding. Prueba real live → selección de cuatro calidades → VOD completo pasó. Se entrega JSON/configuración OBS ≥31; la operación manual de OBS y la reconexión combinada con cuatro calidades quedan identificadas en SPEC-04. |
| P2-09 | VOD | Resuelta en [ADR-022](adr/ADR-022-vod-go-postgresql-minio.md): Go, PostgreSQL privado, MinIO local/S3 configurable y escritor VOD. Captura nativa, cierre/tail, cuatro rendiciones, publicación, permisos y recuperación de objetos se ejecutaron en local; S3 externo no se ha probado. |
| P2-10 | Chat / VOD | Resuelta en ADR-022/[ADR-024](adr/ADR-024-chat-redis-postgresql-replay-moderacion.md): compactar huecos conocidos, mapping/version, PostgreSQL Chat propio, ventanas/cursores y moderación. Captura con reconexión, replay, seek, Chat caído y retirada verificados con proveedores reales. |
| P2-11 | Chat | Resuelta: PT10M/PT1H/PT24H/P7D o PERMANENT, última escritura versionada; borrado por usuario hasta secuencia observada de la emisión. ADR-024. |
| P2-12 | Notificaciones | Resuelta en [ADR-020](adr/ADR-020-notificaciones-typescript-postgresql-smtp.md): TypeScript/Node, PostgreSQL propio, SMTP STARTTLS/Mailpit, intentos independientes y preferencias versionadas. Bandeja/leído, destinos y correo verificados; caducidad de avisos atrasados conserva su política pendiente. |
| P2-13 | Notificaciones / móvil | **Pendiente externo y de implementación:** Firebase/APNs todavía no existen; el equipo prevé provisionarlos. [Guía de provisión](../apps/mobile/PUSH.md): archivos privados, adaptador backend, SDK/registro/rotación/logout y pruebas reales por plataforma. Proveer archivos no activa push automáticamente. |
| P2-14 | Cuentas / móvil / Chat | Bearer Core/Chat y cookie/CSRF Web conservan revocación común. Android real ejecutó login/Keystore, HLS, WS con ACK, seguimiento, bandeja y ciclo de vida; VOD/replay/subtítulos también reproducidos. SPEC-19 separa pruebas Android de compilación iOS y de recorridos aún pendientes. |
| P2-15 | Cuentas | Resuelta en [ADR-017](adr/ADR-017-recuperacion-de-credenciales.md): token de un uso/30 min, cuotas, nueva solicitud invalida anteriores, AES-GCM, outbox y claim/result. Solicitud Web → broker → SMTP → enlace canónico → formulario ejecutado; confirmación UI es manual. Uso único, vencimiento/concurrencia y revocación tienen pruebas Core. |
| P2-16 | Canales / Notificaciones | Contratos `p2.channels.*`, snapshot/paginación y versiones integrados con Notificaciones. Follow/unfollow, preferencias, primer LIVE y deduplicación se ejercitan en el recorrido de seguimiento; SPEC-03/20 conservan su aceptación particular. |
| P2-17 | Administración / proveedores | [ADR-019](adr/ADR-019-administracion-core.md)/022: suspensión transaccional, outbox y autoridad vigente. Streaming cerca por accountVersion y corta HLS/preview en <5 s; VOD/replay/pistas/segmentos tienen prueba directa, evento viejo, reinicio y Core caído. El permiso VOD dura como máximo 60 s con eventos retrasados. Avatar/banner revalidan ACTIVE/referencia con no-store. SPEC-23 conserva pendiente la comprobación WS combinada durante suspensión live y la cobertura humana. |
| P2-18 | Integración | Inventario ejecutado: dos presentaciones, Core/Streaming/Chat/VOD/Notificaciones/Gateway con agregación, cinco lenguajes (Java/Rust/Go/TypeScript/Kotlin), cinco PostgreSQL privados más Redis/MinIO, HTTP/WS/HLS y workers. No se cuenta RabbitMQ como BD ni módulos Core como procesos. La aceptación docente del Gateway como sexta lógica sigue siendo externa. |

## Condición de cierre

Las capacidades principales se han integrado y probado en la instalación local. Esto no acredita
push, una plataforma/dispositivo no ejecutados, accesibilidad humana ni carga sostenida. Las SPEC
conservan esos criterios abiertos: no se retira un requisito ni se marca terminado por tener una PR.
Las pruebas reproducibles y su alcance están junto a los proveedores y en `tests/integration`;
secretos, resultados temporales y cuentas ficticias permanecen fuera de Git.
