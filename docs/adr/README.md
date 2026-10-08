# Registro de decisiones arquitectónicas

| ADR | Estado | Decisión |
| --- | --- | --- |
| [ADR-001](ADR-001-identity-plataforma-y-seguridad.md) | Aceptada | Plataforma Core, registro transaccional, Argon2id, sesión opaca, seguridad local y auth entre procesos. |
| [ADR-002](ADR-002-profile-persistencia-y-avatar.md) | Aceptada | Perfil público en Cuentas, ciclo de avatar y URI inmutable; el almacén se actualiza por ADR-008. |
| [ADR-003](ADR-003-servicios-cohesivos.md) | Sustituida por ADR-005 | Fronteras de la decisión de 2026-10-01. |
| [ADR-004](ADR-004-canales-en-core.md) | Aceptada | Edición/portadas de Canales en Core, sesión local y retiro de provisión/proyección remota. |
| [ADR-005](ADR-005-streaming-rust-y-proyeccion-discovery.md) | Aceptada | Streaming Rust/SQL privado, MediaMTX, Discovery en Core con proyección pública y contratos de contexto. |
| [ADR-006](ADR-006-taxonomia-en-core.md) | Propuesta | Catálogo SQL Core, versión/tombstones y contratos privados owner/catálogo con Streaming. |
| [ADR-007](ADR-007-web-react-typescript.md) | Aceptada | Web React/TypeScript/SWC, pnpm, componentes atómicos, calidad y mocks locales. |
| [ADR-008](ADR-008-s3-image-storage.md) | Aceptada | Bucket S3 privado para avatares/portadas y rutas públicas Core estables. |

La selección de Chat y herramientas de operación y despliegue se registra conforme
a la [política ADR](../politica_ADR.md). Las alternativas candidatas requieren decisión del responsable.

La [decisión de entrega durable de callbacks Media](../../services/streaming/docs/adr/0001-media-callback-delivery.md) documenta la persistencia, reintentos, DLQ y capacidad del adaptador de SPEC-04.
