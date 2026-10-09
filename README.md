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
| Media | MediaMTX separado para RTMP/HLS; adaptador técnico Rust dentro del proceso Streaming en P1, según ADR-011. |
| Web | Una aplicación y un build, con módulos internos de UI y accesibilidad. |
| Reverse proxy | Entrada HTTPS y encaminamiento hacia Core, Streaming, Chat, Media y Web. |

Las fronteras y sus consecuencias están en [ADR-005](docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md); [ADR-011](docs/adr/ADR-011-streaming-tres-contenedores-p1.md) fija los tres contenedores del stack Streaming/Media en P1.
El registro confirma cuenta, perfil y canal en una transacción. Dentro de Core se usan interfaces
locales y lecturas SQL publicadas por los módulos; entre procesos, contratos de red explícitos.

Consulta el [mapa de responsabilidades](docs/mapa_sdd_p1.md), las
[SPEC P1](docs/spec-p1/README.md), los [contratos](docs/contratos_modelo_datos.md)
y las [fases futuras](docs/fases_futuras.md). [AGENTS.md](AGENTS.md) define las reglas de trabajo.

## Implementación y ejecución

[Core](services/core/README.md) implementa cuentas/perfiles/canales, catálogo SQL público,
contextos privados de propietario y valores de catálogo, y el backend de Discovery (GraphQL público,
inbox/proyección de snapshots de Streaming y reconstrucción desde su corte). [Compose](infra/local/README.md)
arranca Core/PostgreSQL; `./infra/local/init-env.ps1` genera configuración local ignorada y
`./infra/local/test-core.ps1` ejecuta unitarias e integración con Java 25 en Docker.

[Chat](services/chat/README.md) (Go y Redis) implementa salas efímeras, historial, WebSocket, cuota,
deduplicación y eventos de sesión. Pruebas: `go test ./...` en `services/chat`.
[Streaming](services/streaming/README.md) contiene el servicio Rust de control, el adaptador Media y su stack MediaMTX/PostgreSQL. La integración con Core, Chat y Web y la aceptación del sistema se rigen por SPEC-09…SPEC-13. El [runner de contratos](tests/contracts/README.md) comprueba su cliente real contra Core y reinicios con volúmenes persistentes.

[Web](apps/web/README.md) conecta Accounts/Channels/Taxonomy/Discovery, Streaming y Chat mediante
HTTP/GraphQL/WS/HLS reales, cookies/CSRF, uploads y leases. Caddy sirve el build con rutas explícitas;
los ejemplos visuales se limitan a la biblioteca de componentes. Calidad: desde apps/web, `pnpm check`.
Recorrido real de dominios y Web: `python tests/integration/p1-domains/run.py --web`, con las
[dependencias y condiciones del fixture](tests/integration/p1-domains/README.md). El perfil HTTP de
desarrollo no acredita TLS, S3 o carga P1 de SPEC-13; la revisión manual de SPEC-08 permanece separada.

El [perfil persistente TLS P1](infra/p1/README.md) ensambla los ocho contenedores del sistema,
conserva tres en Streaming y documenta CA, secretos, volúmenes y réplicas Core/Chat. Sus pruebas
de reinicio y la carga completa tienen resultados separados; consultar las puertas pendientes en Plane.
