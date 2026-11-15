# ADR-027: suscripciones con efecto y retiro auditable

- Estado: aceptada para integración local P2
- Fecha: 2026-11-03
- Responsable: Integración
- SPEC/contratos: SPEC-05/10/15/17/23/24; contratos de eventos P2
- Precisa ADR-016 sin cambiar los nueve tipos ni sus schemas.

## Contexto

El registro inicial aprovisionó `chat.account` y `core.vod` antes de concretar sus efectos.
Ningún runtime las consume. Sus copias acumuladas no representan una proyección implementada.
Chat obtiene autorización vigente de Core para cada envío y autenticación nativa. Core no posee
un catálogo VOD: el Gateway obtiene biblioteca, búsqueda y disponibilidad del servicio VOD.
Añadir consumidores que solo confirmen estos mensajes ocultaría la ausencia de un efecto.

## Decisión

El registro conserva dieciséis suscripciones con consumidores reales. `account.status.changed.v1`
se entrega a Streaming, VOD y Notificaciones; `vod.availability.changed.v1`, a Chat para su replay.
Chat conserva la comprobación HTTP de autoridad actual; Gateway conserva la consulta directa VOD.
No se replica identidad ni catálogo para satisfacer una cola previamente aprovisionada.

En instalaciones nuevas no se crean las dos suscripciones retiradas. En instalaciones existentes,
`broker-ops retire` admite exclusivamente esos dos nombres, exige motivo y rechaza consumidores
activos. Desvincula la entrada, archiva cuerpo y propiedades AMQP exactos con SHA-256 en el volumen
privado de auditoría y sincroniza archivo/directorio antes del ACK. Después elimina únicamente
colas vacías y sin consumidores y sus exchanges privados sin bindings. Un fallo conserva los
mensajes sin confirmar; un ACK perdido puede dejar dos registros idénticos en el archivo.
Las colas quorum no admiten flags de borrado condicional: se comprueban vacías y sin
consumidores inmediatamente antes de borrarlas, después de desvincular productores y archivar.
No ejecutar otro operador/redrive sobre estas dos suscripciones durante su retiro.
El archivo no tiene TTL y forma parte de los backups locales. No hay purge ni redrive a otro dominio.

También se distingue el estado de reconexión P1 del P2. La migración Streaming 0008 materializa
`RECONNECT_GRACE/RECONNECTING` en nuevos envelopes P2 mientras conserva `LIVE/RECONNECTING` en P1.
No reescribe envelopes históricos. Un evento histórico inválido requiere cierre operativo tras
comprobar la reconciliación de su sesión o un nuevo evento relacionado, conforme al contrato.

## Consecuencias y verificación

Se evita acumulación sin efecto y una caché adicional de autorización. HTTP caído continúa
fallando de forma cerrada en operaciones protegidas. Una futura proyección deberá definir su
responsabilidad, consumidor, reconstrucción y nueva migración de topología antes de suscribirse.

Las pruebas de retiro comprueban archivo exacto, fallo de disco sin ACK y rechazo de consumidores
activos/suscripciones vigentes; el smoke RabbitMQ ejecuta la migración con colas reales. PostgreSQL
comprueba P1/P2 durante la misma pérdida de fuente y mantiene la inmutabilidad de envelopes.

El build Streaming declara las dos carpetas de migraciones mediante `build.rs`; una migración
nueva invalida también la compilación SQLx con target Cargo en caché. La imagen Docker copia
el script antes de compilar. La verificación de actualización comprueba la versión en la base
del contenedor desplegado, además de ejecutar las pruebas en una base desechable.
