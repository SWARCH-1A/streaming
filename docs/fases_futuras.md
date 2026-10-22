# Capacidades fuera de P2 y criterios de evolución

Pagos, suscripciones de pago y premium (RF-038…RF-045) quedan fuera de P2. Las demás
capacidades del catálogo forman parte de la entrega definida en [alcance P2](alcance_p2.md).
Las preguntas de watch parties, subtítulos y tecnologías nuevas están en
[decisiones pendientes](decisiones_pendientes_p2.md).

## Monetización

La futura capacidad debe definir planes/precios, moneda, proveedor de pago, cancelación,
devoluciones, reconciliación y acceso premium antes de implementar. Suscripciones y derechos
requieren estados explícitos, operaciones idempotentes y webhooks autenticados/deduplicados.
El proveedor de contenido consulta el permiso correspondiente, sin depender de sus tablas internas.

La ubicación inicial y una eventual separación de Monetización requieren SPEC y ADR. El diseño
P2 no incorpora cobros, contenido premium ni un servicio de pagos.

## Nuevas fronteras

Gateway, VOD y Notificaciones tienen la independencia acordada en ADR-015. Otras extracciones
necesitan justificar capacidad cohesiva, dueño de datos, invariantes, fallos, escala y operación.
El ADR debe cubrir contratos, latencia, compatibilidad, migración, recuperación y costo de
credenciales, backups y despliegue. Una proyección necesita reconstrucción consistente e
idempotencia; no concede permisos por estar disponible.

Separar un worker o réplica no crea por sí solo otro dominio. Tampoco se crea un servicio por
calidad, idioma o módulo interno de Core. El inventario académico debe corresponder a procesos
y componentes de datos reales, con el criterio de clasificación pendiente en P2-18.
