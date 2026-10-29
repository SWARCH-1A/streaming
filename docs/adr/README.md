# Registro de decisiones arquitectónicas

Los ADR explican las elecciones técnicas del proyecto: el problema, las alternativas que evaluamos,
la solución y sus consecuencias. La tabla indica cuáles están aceptados, cuáles siguen en propuesta
y cuáles fueron sustituidos. Las fechas conservan el momento de cada decisión.

| ADR | Estado | Decisión |
| --- | --- | --- |
| [ADR-001](ADR-001-identity-plataforma-y-seguridad.md) | Aceptada | Plataforma Core, registro transaccional, Argon2id, sesión opaca, seguridad local y auth entre procesos. |
| [ADR-002](ADR-002-profile-persistencia-y-avatar.md) | Aceptada | Perfil público en Cuentas, ciclo de avatar y URI inmutable; el almacén se actualiza por ADR-009. |
| [ADR-003](ADR-003-servicios-cohesivos.md) | Sustituida por ADR-005 | Fronteras de la decisión de 2026-10-01. |
| [ADR-004](ADR-004-canales-en-core.md) | Aceptada | Edición/portadas de Canales en Core, sesión local y retiro de provisión/proyección remota. |
| [ADR-005](ADR-005-streaming-rust-y-proyeccion-discovery.md) | Aceptada en P1 | Streaming Rust/SQL privado, MediaMTX, Discovery en Core con proyección pública y contratos de contexto. |
| [ADR-006](ADR-006-taxonomia-en-core.md) | Propuesta | Catálogo SQL Core, versión/tombstones y contratos privados owner/catálogo con Streaming. |
| [ADR-010](ADR-010-chat-go-redis-efimero.md) | Aceptada en P1 | Chat en Go con Redis efímero: script atómico, AOF, Stream como outbox y retención de 5 min tras el fin. |
| [ADR-007](ADR-007-web-react-typescript.md) | Aceptada en P1 | Web React/TypeScript/SWC, pnpm, componentes atómicos, calidad y muestras visuales (integración en ADR-013). |
| [ADR-008](ADR-008-descubrimiento-en-core.md) | Aceptada en P1 | Descubrimiento en Core: graphql-java con controlador propio, recepción atómica de snapshots, reconstrucción con watermark, ranking paginado y límites públicos. |
| [ADR-009](ADR-009-s3-image-storage.md) | Aceptada | Bucket S3 privado para avatares/portadas y rutas públicas Core estables. |
| [ADR-011](ADR-011-streaming-tres-contenedores-p1.md) | Aceptada en P1 | Stack Streaming P1 de tres contenedores; adaptador en el runtime Rust, bases separadas y preparación PostgreSQL sin job. |
| [ADR-012](ADR-012-contratos-generados-p1.md) | Aceptada | Schemas JSON y SDL desde la fuente canónica; generación determinista, checks de drift y consumidores del checkout actual. |
| [ADR-013](ADR-013-web-integrada-caddy-hls.md) | Aceptada en P1 | Web con APIs reales/CSRF, Caddy same-origin, HLS nativo/hls.js, leases y chat recuperable; tipos/validación desde contratos. |
| [ADR-014](ADR-014-perfil-integrado-tls-p1.md) | Aceptada en P1 | Perfil académico persistente, TLS nativo por runtime, RTMPS, CA explícita y almacenamiento compartido. |
| [ADR-015](ADR-015-arquitectura-p2.md) | Aceptada para diseño P2 | React Router SSR, KMP + Compose, Gateway, broker único, VOD independiente, replay durable y Notificaciones. Tecnologías nuevas por responsable. |
| [ADR-016](ADR-016-rabbitmq-eventos-p2.md) | Aceptada para integración inicial | RabbitMQ/AMQP, contratos de ciclo/Discovery, outbox/inbox, DLQ y operación; recuperación ampliada por ADR-017. |
| [ADR-017](ADR-017-recuperacion-de-credenciales.md) | Aceptada para Cuentas | Recuperación de un uso, cuotas SQL, AES-GCM, revocación transaccional, publicación RabbitMQ y claim/result; consumidor y SMTP pendientes de Notificaciones. |

| [ADR-018](ADR-018-subtitulos-vod-webvtt.md) | Propuesta; contratos comprobables | WebVTT aportado por propietario, dentro de VOD; perfil limitado y reproducción integrada pendiente. |
| [ADR-019](ADR-019-administracion-core.md) | Propuesta implementada | Administración Core, revocación transaccional y outbox de estado; aceptación transversal pendiente. |

| [ADR-020](ADR-020-cliente-mobile-nativo.md) | Aceptada para implementación inicial | Cliente KMP/Compose Android/iOS, Ktor, reproductores y credenciales nativas; integración por APIs públicas. |

Chat conserva Go; ADR-010 describe su perfil efímero P1. La retención durable P2 rige por ADR-015; las herramientas pendientes de operación y despliegue se registran conforme
a la [política ADR](../politica_ADR.md). Las alternativas candidatas requieren decisión del responsable.

La [decisión de entrega durable de callbacks Media](../../services/streaming/docs/adr/0001-media-callback-delivery.md) documenta la persistencia, reintentos, DLQ y capacidad del adaptador de SPEC-04.
