# Core / Consultas y descubrimiento

Módulo del runtime Core Java/Spring que implementa [SPEC-07](../../../../../../../../docs/spec-p1/spec_07_disc.md)
según [ADR-005](../../../../../../../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md) y
[ADR-008](../../../../../../../../docs/adr/ADR-008-descubrimiento-en-core.md): GraphQL público, consultas SQL locales e
inbox/proyección de snapshots públicos de Streaming, con los [contratos](../../../../../../../../docs/contratos_modelo_datos.md).
La operación, variables y rutas están en el [README de Core](../../../../../../README.md).

Discovery combina las vistas publicadas de Cuentas, Canales y Catálogo con la proyección de emisiones.
Las consultas se resuelven en SQL Core. Las versiones y la deduplicación evitan aplicar datos antiguos;
la frescura debe ser de hasta 5 s. La reconstrucción usa un corte consistente con watermark,
sin consultar el SQL privado Streaming ni hacer HTTP por resultado.

| Capa | Contenido |
| --- | --- |
| `api` | `POST /api/discovery/graphql` (público) y `POST /internal/core/discovery/stream-events` (solo conector privado). |
| `application` | Casos de uso: recepción de eventos, reconciliación con el corte, consultas, mantenimiento; puertos y validación del contrato. |
| `domain` | Reglas puras: normalización de búsqueda, frescura, cursores, límite de solicitudes, proxies confiables. |
| `infrastructure` | JDBC, cliente HTTP del corte de Streaming, motor GraphQL y su guardia de forma/costo. |

Las tablas del schema `discovery` solo las escribe este módulo; ningún otro módulo lee la proyección.
