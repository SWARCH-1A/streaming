# Backend

[Core](core/README.md) contiene Cuentas, Canales, Catálogo y Consultas en un build Java/Spring
con PostgreSQL y seguridad comunes. Cada módulo conserva repositorios e interfaces publicados.
[Chat](chat/README.md) es la unidad independiente de mensajes y tiempo real, pendiente de implementación.
[Streaming](streaming/README.md) ejecuta el control de emisiones y el adaptador Media en un proceso Rust P1; su stack usa PostgreSQL, Streaming y MediaMTX según ADR-011. [Media](../infra/media/README.md) documenta el motor y su configuración. El mapa vigente está en [docs](../docs/mapa_sdd_p1.md).
