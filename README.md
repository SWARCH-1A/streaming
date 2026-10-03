# STREAMING

Plataforma académica de transmisión en vivo. La primera iteración incluye cuentas, canales,
emisiones RTMP/HLS, chat, catálogo, descubrimiento y accesibilidad. Las capacidades futuras
conservan su alcance en el catálogo y el plan de evolución.

La definición vigente está en [docs/README.md](docs/README.md). Los requisitos, contratos,
SPEC y ADR del repositorio son la fuente normativa; Plane registra el trabajo del proyecto STREAMING.

## Arquitectura

| Unidad | Responsabilidad |
| --- | --- |
| Core | Java/Spring y PostgreSQL: Cuentas (autenticación y perfil), Canales, Catálogo, Emisiones y Consultas. Un build, seguridad común y transacciones locales. |
| Chat | Salas, mensajes, historial, cuota global, secuencia y distribución WebSocket; persistencia propia. |
| Media | Ingesta RTMP, reproducción HLS y procesamiento audiovisual; adaptador al control de emisiones Core. |
| Web | Una aplicación y un build, con módulos internos de UI y accesibilidad. |
| Reverse proxy | Entrada HTTPS y encaminamiento hacia Core, Chat, Media y Web. |

Las fronteras y sus consecuencias están en [ADR-003](docs/adr/ADR-003-servicios-cohesivos.md).
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

Emisiones y su composición en el canal, Catálogo, Consultas, Chat y Media siguen pendientes.
[Web](apps/web/README.md) tiene una base React/TypeScript/SWC ejecutable con pnpm, diseño de Stitch,
componentes atómicos y tests; organiza su esqueleto en `src/modules`, `src/shell` y
`src/accessibility`. Sus vistas usan datos de demostración; las integraciones HTTP/WS/HLS y
autorización real siguen pendientes. Ejecutar desde `apps/web`: `pnpm install --frozen-lockfile` y
`pnpm dev` (puerto 3000). Calidad: `pnpm check`; navegador: `pnpm test:e2e`.
