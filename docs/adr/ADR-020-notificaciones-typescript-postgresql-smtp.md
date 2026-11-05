# ADR-020: Notificaciones con TypeScript, PostgreSQL y SMTP

- Estado: aceptada para la implementación de Notificaciones en esta rama
- Fecha: 2026-10-27
- Responsable: Notificaciones / integración con Canales y Cuentas
- SPEC/contratos afectados: SPEC-03, SPEC-20, P2-12, `p2.notifications.*`, `p2.recovery.delivery-*`
- Amplía: [ADR-015](ADR-015-arquitectura-p2.md) y [ADR-017](ADR-017-recuperacion-de-credenciales.md)

## Contexto

Notificaciones necesita bandeja, preferencias versionadas, dispositivos, deduplicación y resultados
por medio durables. Cuentas ya publica trabajos de recuperación y conserva la autoridad del material
sensible mediante claim/result. Un reinicio debe distinguir un envío pendiente de un envío externo
cuyo resultado se desconoce. La selección tecnológica estaba delegada a este responsable en P2-12.

## Decisión

Usar Node.js 24 con TypeScript ejecutado por el runtime y verificación estática con TypeScript.
`services/notifications` contiene API y workers en un proceso desplegable independiente, con su
propio PostgreSQL y credenciales. `pg` proporciona transacciones; `amqplib` consume el RabbitMQ
compartido de ADR-016 y publica la outbox con confirmación. No se accede a bases de otros servicios.

Usar SMTP mediante Nodemailer con STARTTLS obligatorio, validación de certificado y tiempos de
espera limitados. Mailpit es el receptor de pruebas local, con TLS y UI expuesta solo en loopback;
no es un proveedor de correo de producción. SMTP/push mantienen resultados separados por destino.
El proveedor push queda pendiente de P2-13: el adaptador sin configurar registra fallo permanente.

Cada mutación HTTP y su respuesta de idempotencia comparten una transacción y conexión. Las
preferencias ausentes equivalen a versión 1 con medios apagados; el update condicional incrementa
la versión solo si cambia un valor. La selección fija los medios autorizados al crear el aviso.
La política de caducidad de avisos atrasados continúa pendiente del responsable.

Los trabajadores adquieren propiedad exclusiva por deliveryId mediante un advisory lock de sesión
en PostgreSQL, usando esa conexión para todas las fases. Los duplicados concurrentes vuelven al
broker como error temporal sin reclamar ni enviar. Las fases se confirman antes de llamadas externas:
no se mantiene una transacción sin confirmar alrededor de SMTP. Tras un reinicio, SENDING/IN_PROGRESS
externo se reporta UNKNOWN; bandeja local puede completarse con seguridad. Recuperación persiste
solo identificadores, fase y resultado; dirección y enlace existen únicamente en memoria.

## Opciones consideradas

- Extender Core Java: reutiliza infraestructura, pero contradice la frontera independiente de
  Notificaciones aceptada en ADR-015 y acopla su disponibilidad a SMTP. Descartada.
- Otro servicio Go/Java con PostgreSQL: satisface durabilidad, pero añade otro conjunto de
  adaptadores y tipos sin una ventaja necesaria para esta carga de I/O. TypeScript comparte
  herramientas de contratos con Web sin convertir tipos internos en contratos entre procesos.
- Solo memoria o ACK del broker como almacén de intentos: no conserva la distinción entre enviar
  y reportar tras un reinicio. Descartada.
- Redis para bandeja e intentos: posible con operaciones atómicas propias, pero requeriría modelar
  manualmente índices, paginación y transacciones relacionadas. PostgreSQL encaja con estas claves
  únicas y conserva la exigencia SQL; Chat mantiene su almacenamiento NoSQL independiente.
- API específica de proveedor de correo: puede aportar idempotencia externa, pero introduce
  credenciales y dependencia de proveedor. SMTP permite prueba local reproducible; se acepta
  explícitamente su resultado incierto ante pérdida de respuesta.

## Consecuencias

Se incorporan un proceso de lógica y una base de negocio. Mailpit es infraestructura de prueba,
no otra unidad de lógica. Hay que operar migraciones, respaldos, pool de conexiones, backlog,
DLQ y retención junto con la deduplicación. Los locks requieren conexiones de sesión estables;
un proxy SQL en modo transaction pooling no es compatible con este mecanismo.

Ni SMTP ni push acreditan exactly-once externo. UNKNOWN evita reenviar sin evidencia. Un fallo
por medio no revierte LIVE, seguimiento ni otro medio entregado. Las credenciales Core de
Notificaciones y recovery-email son distintas; las APIs privadas siguen restringidas por permiso.
La provisión push, caducidad de avisos y suspensión transversal siguen siendo dependencias abiertas.

No sustituye el protocolo de recuperación de ADR-017 ni las tecnologías de Core/Streaming/Chat.
Las migraciones de Notificaciones se aplican en orden bajo un lock. El despliegue local se actualiza
con `init`, `build`, `up`, conservando secretos y volúmenes existentes.

## Verificación

`services/notifications/test/notifications.test.ts` comprueba PostgreSQL real con adaptadores falsos:
versiones sucesivas, conflicto concurrente, rollback al fallar idempotencia, más comandos que
conexiones, duplicados concurrentes de recuperación/entrega y reanudación de bandeja interrumpida.
`tests/integration/p2-follow-notifications/run.py` define el recorrido con Core, broker y Mailpit;
su ejecución y la provisión real push se acreditan por separado, no por aceptar este ADR.

## Revisión

Notificaciones, Cuentas e Integración revisan esta elección si cambia el proveedor, la retención
broker/redrive, el modo de conexión PostgreSQL, la frontera del servicio o el contrato claim/result.
