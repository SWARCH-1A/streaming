# Mapa de responsabilidades P1

El proyecto Plane es STREAMING. Las SPEC y módulos usan las responsabilidades de esta tabla;
los módulos Core son agrupaciones funcionales dentro de un único proceso, no servicios separados.
Una asignación personal no cambia la frontera arquitectónica.

| SPEC | Módulo Plane / responsabilidad | Unidad y ubicación | UI |
| --- | --- | --- | --- |
| SPEC-01 | Core / Cuentas: autenticación y perfil | services/core; cuentas | apps/web/src/modules/accounts |
| SPEC-03 | Core / Canales y seguimiento | services/core; canales | apps/web/src/modules/channels |
| SPEC-04 | Streaming / Emisiones | services/streaming; Rust, SQL privado, control, metadata, cupos, reloj y leases | apps/web/src/modules/streaming |
| SPEC-05 | Chat | services/chat; mensajes, cuota, secuencia, historial y tiempo real | apps/web/src/modules/chat |
| SPEC-06 | Core / Catálogo | services/core; categorías, etiquetas y tombstones | apps/web/src/modules/taxonomy |
| SPEC-07 | Core / Consultas y descubrimiento | services/core; lecturas SQL locales y proyección Streaming, filtros/ranking; GraphQL público | apps/web/src/modules/discovery |
| SPEC-08 | Web / Accesibilidad | apps/web/src/accessibility y todas las vistas | Criterios transversales |
| SPEC-09 | Integración | contracts, infra, shell y evidencia compartida | apps/web/src/shell |
| SPEC-10 | Integración: contratos y datos | contracts/generated y tests/contracts | Contratos para consumidores |
| SPEC-11 | Integración: flujos | tests/integration | Registro local, Core–Streaming, Streaming–Chat y Streaming–Media |
| SPEC-12 | Integración: Web y proxy | apps/web/src/shell, infra/reverse-proxy | Rutas, auth, HTTP/WS/HLS |
| SPEC-13 | Integración: despliegue y evidencia | infra/local, tests/e2e | Recorrido y perfil de carga |

## Capacidades futuras

| Módulo Plane | Dueño inicial y alcance |
| --- | --- |
| Core / Monetización | RF-038…RF-045: planes, pagos, suscripciones y acceso premium en un módulo cohesivo Core. Proveedor/adaptador por ADR de la fase. |
| Media / Procesamiento audiovisual | RTMP/HLS en P1; RF-027…RF-030, RF-056…RF-062 y RF-063…RF-065: procesamiento de calidades, grabación y pistas, con metadata de emisión/control Streaming y biblioteca futura/Core. |

Las otras capacidades y dependencias se definen en [fases futuras](fases_futuras.md) y el catálogo;
su planificación no crea procesos ni tablas por anticipación. Una SPEC futura se define al priorizar
su capacidad. Seguimiento, moderación, VOD, notificaciones y watch party tienen dueño inicial allí.

## Propiedad

Cuentas, Canales, Catálogo y Consultas comparten build, seguridad y PostgreSQL Core. Streaming Rust posee su PostgreSQL, inbox/outbox, claves y estado de emisiones.
Registro cuenta/perfil/canal usa una transacción. Cada módulo escribe por su repositorio; las lecturas
compuestas usan vistas publicadas, columnas explícitas y FK locales. Discovery posee proyección pública/inbox en Core y recibe snapshots Streaming; no consulta su SQL privado. Chat posee su almacén y Media el
flujo audiovisual. Ningún proceso externo consulta bases privadas. Integración no posee casos de uso
de negocio. RNF y evidencia se relacionan en la matriz de trazabilidad.
Catálogo mantiene el registro SQL interno de IDs/tipos disjuntos, incluidos tombstones. Seguridad
Core verifica los permisos por ruta de las credenciales Streaming; la credencial limitada a catálogo
no concede acceso al contexto de propietario de Canales.

## Definición de módulos de trabajo

Los nombres y descripciones de esta tabla definen las agrupaciones de Plane. Las agrupaciones Core
comparten proceso y stack; las de capacidades futuras organizan trabajo sin adelantarlas en P1.

| Módulo | Definición |
| --- | --- |
| Core / Cuentas | Autenticación y perfil público dentro de Core. RF-001…RF-003, RF-005…RF-007 en P1; recuperación de contraseña RF-004 futura. Registro cuenta/perfil/canal en una transacción PostgreSQL; sesión opaca, CSRF, cuotas, edición y avatar. Java/Spring y seguridad comunes de Core; interfaces locales, sin servicio Profile ni provisión remota. SPEC-01. |
| Core / Canales y seguimiento | Módulo interno Core: un canal por cuenta, descripción/portada, propiedad y página pública con datos locales/batch Streaming. RF-008…RF-012 y SPEC-03 en P1; seguimiento RF-014…RF-016 y listado VOD RF-013 futuros. Creación en la transacción de registro; FK y PostgreSQL Core. No proceso, base ni stack propios. |
| Streaming / Emisiones | Servicio Rust/SQLx/PostgreSQL privado: RF-017…RF-026, SPEC-04. Configuración, claves, cupos, estado, generaciones, clock y leases; contexto Core por comando protegido; MediaMTX RTMP/LL-HLS, outbox de sesión a Chat y snapshots públicos a Discovery con inbox/frescura/reconstrucción. |
| Core / Catálogo | Módulo interno Core: categorías, etiquetas, IDs estables, valores activos, tombstones y versión. RF-066…RF-069, SPEC-06. PostgreSQL Core y contexto tipado/activo para Streaming; labels/tombstones estables para consultas; GET /api/taxonomy para Web. Sin servicio/base propios. Accesibilidad pertenece a Web y sus vistas; subtítulos son capacidad futura Core/Media. |
| Core / Consultas y descubrimiento | Módulo de lectura Core: RF-070…RF-073, SPEC-07. Canales LIVE/OFFLINE y streams PLAYABLE, búsqueda parcial, filtros, ranking y frescura. GraphQL /api/discovery/graphql con SQL local de Cuentas/Canales/Catálogo y proyección pública Streaming. Inbox/versiones/frescura/reconstrucción con watermark; sin HTTP por fila ni runtime Discovery separado. Búsqueda/filtros VOD RF-074…RF-075 futuros; índice especializado solo con necesidad medida y reconstrucción definida. |
| Chat | Servicio independiente: salas por sessionId, mensajes, WebSocket, historial, dedupe, secuencia, cuota global por cuenta y entrega recuperable. RF-031…RF-035, SPEC-05 en P1; moderación RF-036…RF-037 y Replay futuros en el mismo dueño. Lectura anónima; un contexto Core por mensaje nuevo con estado/timeline actual Streaming, y lifecycle Rust; sin autorización cacheada. Go/MongoDB candidatos sujetos a ADR; persistencia antes de ACK, cuota/orden/fan-out consistentes entre réplicas. Caída Chat no corta HLS. |
| Integración | Trabajo transversal de contratos, propiedad de datos, flujos Core–Streaming, Streaming–Chat/Media, Web/proxy, despliegue, recuperación y evidencia E2E. SPEC-09 y sus hijos SPEC-10…SPEC-13. Mantiene coherencia y restricciones SQL/NoSQL, lenguajes y conectores. No es servicio ni orquestador de casos de uso; la lógica permanece en el dueño del dominio. |
| Core / Monetización | Capacidad futura RF-038…RF-045: planes, pagos, suscripciones y derechos premium como módulo cohesivo Core. Proveedor/adaptador, moneda, cancelación/devolución y reconciliación por SPEC/ADR de la fase. Intentos/webhooks firmados e idempotentes; estado local transaccional. No implementado en P1 ni stacks/servicios separados por pagos, suscripciones y permisos. Extraer conjuntamente solo con evidencia operativa/escala/release. |
| Media / Procesamiento audiovisual | Unidad multimedia: ingesta RTMP, HLS, señales y verificación de reproducción en P1, con control de negocio Streaming Rust según SPEC-04. Calidades RF-027…RF-030, captura/procesado VOD RF-056…RF-062 y pistas RF-063…RF-065 futuras. Metadata de emisión y acceso pertenecen a Streaming; catálogo/identidad a Core. MediaMTX/adaptador Rust por ADR-005; workers pesados se separan de ingest cuando lo justifiquen carga/fallo. Sin servicio por calidad o idioma. |
| Web / Accesibilidad | Criterios transversales de la única aplicación Web: teclado, semántica, foco, contraste y control de autodesplazamiento Chat. SPEC-08 y RNF-035…RNF-036; aplica a formularios, canal, player, chat y búsqueda. Comparte responsable de Catálogo por asignación del equipo, sin mezclar sus fronteras. No servicio backend ni microfrontend. Subtítulos RF-063…RF-065 son una capacidad futura de Core/Media. |
