# ADR-019: Administración local en Cuentas y propagación durable

- Estado: aceptada para integración local; recorridos Core/VOD/live e imágenes verificados.
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
Las operaciones Web conservan cookie/CSRF y el login móvil comparte la revocación de Core.

Recuperar contraseña no reactiva una cuenta suspendida. Se permite recuperar manteniendo
su estado/rol/versión y las garantías de privacidad de ADR-017; la reactivación exige una
operación administrativa separada. Ambas operaciones y login bloquean la cuenta y login
revalida contraseña y ACTIVE antes de crear sesión. Impedir la recuperación durante la
suspensión mezclaría recuperación de credenciales con autorización administrativa.

El outbox publica JSON neutral persistente mediante el RabbitMQ de ADR-016, con confirmación
y retorno obligatorio. No se marca enviado ante falta de confirmación/ruta. Un reintento
conserva eventId y payload; consumidores deben deduplicar y aplicar accountVersion creciente.
La confirmación del broker no demuestra que los consumidores hayan aplicado la suspensión.

Streaming captura la versión actual de Cuentas al autorizar cada sesión; consulta el contexto
privado `p2.streaming.channel-access` antes de autorizar ingesta y entregar lectura/media.
Suspensión o revisión distinta cerca las sesiones anteriores, incluso tras reactivar. El
consumidor durable de `streaming.account` termina las sesiones antiguas con el stop existente,
y la reconciliación periódica contra Core cubre indisponibilidad del broker. No se usa la
proyección Discovery para conceder permisos. Un fallo de Core impide nuevas entregas; no
se inventa una suspensión ni se reactiva una sesión desde un evento retrasado.

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
concurrencia, migración y publicación/recuperación del broker. El 2026-11-03 se ejecutó
el escenario VOD/Chat completo: consola real, permisos/CSRF, versiones, respuesta perdida e
idempotencia, suspensión/reactivación, restricción de manifiestos/segmentos/pistas/replay,
eventos viejos, reinicio VOD y Core caído. Streaming comprobó corte de nuevas entregas HLS/preview
en menos de cinco segundos y rechazo de reconexión de una fuente suspendida.
La lectura de autoridad de Streaming también falla cerrada y cerca la sesión por accountVersion.
El rechazo de nuevos mensajes Chat se cubre por sus contextos/revocación y suites; no se
atribuye al escenario de suspensión live una comprobación WS que no ejecutó.
Las rutas de avatar/banner comprueban referencia vigente y cuenta ACTIVE antes de leer el objeto; responden 404 si está suspendida y `Cache-Control: no-store, private` cuando es legible. Perfil/Canales consultan la vista pública de Cuentas, sin acceso a sus tablas privadas. Reactivar permite leer de nuevo las mismas referencias sin borrar objetos. Una prueba de integración con PNG real verifica ambos recursos, suspensión y reactivación.
