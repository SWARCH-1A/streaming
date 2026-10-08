# Registro de decisiones arquitectónicas

| ADR | Estado | Decisión |
| --- | --- | --- |
| [ADR-001](ADR-001-identity-plataforma-y-seguridad.md) | Aceptada | Plataforma Core, registro transaccional, Argon2id, sesión opaca, seguridad local y auth entre procesos. |
| [ADR-002](ADR-002-profile-persistencia-y-avatar.md) | Aceptada | Perfil público en Cuentas, ciclo de avatar y URI inmutable; el almacén se actualiza por ADR-009. |
| [ADR-003](ADR-003-servicios-cohesivos.md) | Sustituida por ADR-005 | Fronteras de la decisión de 2026-10-01. |
| [ADR-004](ADR-004-canales-en-core.md) | Aceptada | Edición/portadas de Canales en Core, sesión local y retiro de provisión/proyección remota. |
| [ADR-005](ADR-005-streaming-rust-y-proyeccion-discovery.md) | Aceptada | Streaming Rust/SQL privado, MediaMTX, Discovery en Core con proyección pública y contratos de contexto. |
| [ADR-006](ADR-006-taxonomia-en-core.md) | Propuesta | Catálogo SQL Core, versión/tombstones y contratos privados owner/catálogo con Streaming. |
| [ADR-010](ADR-010-chat-go-redis-efimero.md) | Aceptada | Chat en Go con Redis efímero: script atómico, AOF, Stream como outbox y retención de 5 min tras el fin. |
| [ADR-007](ADR-007-web-react-typescript.md) | Aceptada | Web React/TypeScript/SWC, pnpm, componentes atómicos, calidad y mocks locales. |
| [ADR-008](ADR-008-descubrimiento-en-core.md) | Aceptada | Descubrimiento en Core: graphql-java con controlador propio, recepción atómica de snapshots, reconstrucción con watermark, ranking paginado y límites públicos. |
| [ADR-009](ADR-009-s3-image-storage.md) | Aceptada | Bucket S3 privado para avatares/portadas y rutas públicas Core estables. |
| [ADR-011](ADR-011-streaming-tres-contenedores-p1.md) | Aceptada | Stack Streaming P1 de tres contenedores; adaptador en el runtime Rust, bases separadas y preparación PostgreSQL sin job. |
| [ADR-012](ADR-012-contratos-generados-p1.md) | Aceptada | Schemas JSON y SDL desde la fuente canónica; generación determinista, checks de drift y consumidores del checkout actual. |

Chat está seleccionado en ADR-010; las herramientas pendientes de operación y despliegue se registran conforme
a la [política ADR](../politica_ADR.md). Las alternativas candidatas requieren decisión del responsable.

La [decisión de entrega durable de callbacks Media](../../services/streaming/docs/adr/0001-media-callback-delivery.md) documenta la persistencia, reintentos, DLQ y capacidad del adaptador de SPEC-04.
