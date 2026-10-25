# ADR-017: Administración local en Cuentas y propagación durable

- Estado: propuesta implementada; revisión de Cuentas y consumidores pendiente.
- Fecha: 2026-10-24.
- Responsable: Administración / Cuentas.
- SPEC y contratos: SPEC-23, RF-076…079, `p2.admin.*`, `account.status.changed.v1`.

## Contexto

Suspender una cuenta debe revocar su identidad utilizable sin dejar estado parcial entre
la cuenta, sus sesiones y la auditoría. Los servicios poseen sus propios datos; Core no
puede detener una emisión escribiendo en PostgreSQL de Streaming.

## Decisión

Implementar Administración dentro de Cuentas, en el Core Java/PostgreSQL existente. Una
transacción bloquea actor/objetivo, revalida sesión/rol y registra cambio, revocación,
resultado idempotente, auditoría e intención de evento. Canales publica una interfaz local
de lectura para el listado administrativo; no se duplican sus datos.

El operador provisiona ADMIN sobre una cuenta ACTIVE existente mediante SQL explícito.
El registro público siempre crea USER. La provisión revoca las sesiones anteriores.
Las operaciones Web conservan cookie/CSRF; no se implementa todavía login móvil.

El outbox publica JSON neutral persistente mediante el RabbitMQ de ADR-016, con confirmación
y retorno obligatorio. No se marca enviado ante falta de confirmación/ruta. Un reintento
conserva eventId y payload; consumidores deben deduplicar y aplicar accountVersion creciente.
La confirmación del broker no demuestra que los consumidores hayan aplicado la suspensión.

## Alternativas y consecuencias

Un servicio Admin con acceso a todas las bases violaría propiedad y ampliaría privilegios.
Una escritura seguida de publicación sin outbox perdería eventos en una caída intermedia.
Una transacción distribuida añadiría coordinación que los proveedores actuales no ofrecen.

La transacción local ofrece consistencia de identidad; la propagación es eventual y exige
controles síncronos de acceso definidos con los consumidores en P2-17. No promete retirada
instantánea ni recuperación de bytes descargados. Auditoría, claves idempotentes y outbox
se conservan sin limpieza automática en este incremento; tienen costo de almacenamiento.

## Verificación y límites

Las pruebas de Administración cubren permisos, CSRF, versiones, repetición, rollback,
concurrencia, migración y publicación/recuperación del broker. La aceptación integrada de
RF-078/079 permanece pendiente de corte de medios, retirada, replay y autorización directa.
Las rutas de avatar/banner y sus cachés P1 requieren revisión de acceso en P2-17. Ocultar
un perfil no revoca esas URLs; tampoco debe eliminar sus objetos durante la suspensión.
