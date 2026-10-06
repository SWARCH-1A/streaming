# Core / Consultas y descubrimiento

Implementación pendiente dentro del runtime Core Java/Spring. [SPEC-07](../../../../../../../../docs/spec-p1/spec_07_disc.md) y [ADR-005](../../../../../../../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md) definen GraphQL público, consultas SQL locales e inbox/proyección de snapshots públicos Streaming.

Discovery combina Cuentas/Canales/Catálogo locales con la proyección de emisión. No lee SQL privado Streaming ni hace HTTP por fila. Requiere versiones/dedupe, frescura <=5 s y reconstrucción consistente con watermark conforme a los [contratos](../../../../../../../../docs/contratos_modelo_datos.md).
