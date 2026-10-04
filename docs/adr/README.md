# Registro de decisiones arquitectónicas

| ADR | Estado | Decisión |
| --- | --- | --- |
| [ADR-001](ADR-001-identity-plataforma-y-seguridad.md) | Aceptada | Plataforma Core, registro transaccional, Argon2id, sesión opaca, seguridad local y auth entre procesos. |
| [ADR-002](ADR-002-profile-persistencia-y-avatar.md) | Aceptada | Perfil público en Cuentas y avatares en volumen persistente con URI inmutables/reconciliación. |
| [ADR-003](ADR-003-servicios-cohesivos.md) | Sustituida por ADR-005 | Fronteras de la decisión de 2026-10-01. |
| [ADR-004](ADR-004-canales-en-core.md) | Aceptada | Edición/portadas de Canales en Core, sesión local y retiro de provisión/proyección remota. |
| [ADR-005](ADR-005-streaming-rust-y-proyeccion-discovery.md) | Aceptada | Streaming Rust/SQL privado, MediaMTX, Discovery en Core con proyección pública y contratos de contexto. |

La selección de Chat, Web y herramientas de operación y despliegue se registra conforme
a la [política ADR](../politica_ADR.md). Las alternativas candidatas requieren decisión del responsable.

La [decisión de entrega durable de callbacks Media](../../services/streaming/docs/adr/0001-media-callback-delivery.md) documenta la persistencia, reintentos, DLQ y capacidad del adaptador de SPEC-04.
