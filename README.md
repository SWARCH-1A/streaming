# STREAMING

Plataforma académica de transmisión en vivo. La primera iteración incluye cuentas, canales,
emisiones RTMP/HLS, chat, catálogo, descubrimiento y accesibilidad. Las capacidades futuras
conservan su alcance en el catálogo y el plan de evolución.

La definición vigente está en [docs/README.md](docs/README.md). Los requisitos, contratos,
SPEC y ADR del repositorio son la fuente normativa; Plane registra el trabajo del proyecto STREAMING.

## Arquitectura

| Unidad | Responsabilidad |
| --- | --- |
| Core | Java/Spring y PostgreSQL: Cuentas (autenticación y perfil), Canales, Catálogo y Discovery con proyección de emisiones. Un build, seguridad común y transacciones locales. |
| Streaming | Rust y PostgreSQL privado: configuración, claves, sesiones, cupos, clock, leases y outbox hacia Discovery/Chat. |
| Chat | Salas, mensajes, historial, cuota global, secuencia y distribución WebSocket; persistencia propia. |
| Media | Ingesta RTMP, reproducción HLS y procesamiento audiovisual; adaptador Rust al control de Streaming. |
| Web | Una aplicación y un build, con módulos internos de UI y accesibilidad. |
| Reverse proxy | Entrada HTTPS y encaminamiento hacia Core, Streaming, Chat, Media y Web. |

Las fronteras y sus consecuencias están en [ADR-005](docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md).
El registro confirma cuenta, perfil y canal en una transacción. Dentro de Core se usan interfaces
locales y lecturas SQL publicadas por los módulos; entre procesos, contratos de red explícitos.

Consulta el [mapa de responsabilidades](docs/mapa_sdd_p1.md), las
[SPEC P1](docs/spec-p1/README.md), los [contratos](docs/contratos_modelo_datos.md)
y las [fases futuras](docs/fases_futuras.md). [AGENTS.md](AGENTS.md) define las reglas de trabajo.

## Implementación y ejecución

[Core](services/core/README.md) implementa registro transaccional de cuenta/perfil/canal, sesiones,
perfil/avatares, edición/portadas de canal y bootstrap público compuesto. [Compose local](infra/local/README.md) inicia Core y
PostgreSQL con volumen persistente. Pruebas: `services/core/mvnw -f services/core/pom.xml test` y
`services/core/mvnw -f services/core/pom.xml verify -P integration` (Docker para PostgreSQL aislado).

[Streaming](services/streaming/README.md) contiene el servicio Rust de control, el adaptador Media y su stack MediaMTX/PostgreSQL. La integración con Core, Chat y Web y la aceptación del sistema se rigen por SPEC-09…SPEC-13. Catálogo y Consultas siguen pendientes.
[Web](apps/web/README.md) organiza su esqueleto en `src/modules`, `src/shell` y `src/accessibility`.
