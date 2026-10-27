# Pruebas de integración

Estas pruebas comprueban interacciones entre módulos. Pueden cubrir una parte del recorrido,
sin llegar a todas las acciones del usuario.

Cada carpeta o archivo identifica los módulos y SPEC que prueba. Las suites
[p1-domains](p1-domains/README.md) y [p1-delivery](p1-delivery/README.md) describen sus
recorridos y condiciones de ejecución.

[Administración](administration/README.md) comprueba Web, Core, PostgreSQL y Caddy reales:
rol, CSRF, suspensión/reactivación, sesión revocada, idempotencia, conflicto concurrente,
foco y comprobaciones automáticas de accesibilidad. Usa recursos Docker desechables.

La [prueba de infraestructura RabbitMQ](../../infra/broker/README.md#contratos-y-evidencia)
comprueba arranque, TLS, ACL, entrega durable y operaciones de DLQ en un entorno desechable.
La verificación de sus consumidores de negocio se acredita por separado en SPEC-17.
