# Backend

El backend se organiza en tres servicios de lógica y un motor multimedia:

- [Core](core/README.md) reúne Cuentas, Canales, Catálogo y Discovery en un ejecutable Java/Spring. Comparten PostgreSQL y seguridad; cada módulo conserva sus repositorios e interfaces.
- [Streaming](streaming/README.md) controla las emisiones y ejecuta el adaptador Media en un proceso Rust. Su stack P1 tiene PostgreSQL, Streaming y MediaMTX, según ADR-011.
- [Chat](chat/README.md) maneja salas, mensajes, historial y tiempo real en Go con Redis, según ADR-010.
- [Media](../infra/media/README.md) documenta el motor MediaMTX y su configuración para recibir y entregar video.

El [mapa de responsabilidades](../docs/mapa_sdd_p1.md) relaciona estos servicios con sus SPEC.
