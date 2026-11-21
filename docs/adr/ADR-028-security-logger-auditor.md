# ADR-028: Security Logger y Auditor para P3

- Estado: aceptada para la asignación de auditoría P3; resultados de ejecución por separado.
- Fecha: 2026-11-20. Responsable: Ivan Castellanos.
- Afecta SPEC-25 nueva, SPEC-14/17/23/24, contratos y despliegue local.

## Problema

Vault TV autoriza en Core/VOD y tiene auditorías locales y logs HTTP. Esos registros no ofrecen
una consulta protegida común para investigar intentos contra grabaciones ajenas ni relacionar
las acciones administrativas con su cuenta, recurso, instante y resultado.

## Decisión

Crear `services/audit` (Go 1.26, PostgreSQL 18, amqp091-go/pgx en versiones existentes). Logger y
Auditor son módulos del mismo runtime. La responsabilidad transversal de evidencia justifica
un componente nuevo: posee su almacenamiento y contrato de consulta; no extrae módulos Core
ni obtiene acceso a bases ajenas. Reutiliza RabbitMQ y la sesión/rol ADMIN de Core.

Cada productor confirma evidencia local durable antes de enviarla. Core tiene un outbox nuevo;
VOD reutiliza vod_outbox. Las acciones confirmadas comparten transacción con su evidencia;
las denegaciones tienen commit independiente. No confirmar una acción sensible si falla esa
escritura; el broker/Logger no forma parte de la transacción HTTP. Reintentos conservan eventId.
No se registra el motivo libre administrativo, solo el código de resultado.

Routing keys `security.audit.core.v1` y `security.audit.vod.v1`, suscripciones `audit.core` y
`audit.vod`. ACL de productor por key evita atribuir un evento VOD a Core. Consumidor valida
esquema, correlación, productor/tipo y MessageId; persiste antes de ACK. Misma identidad y cuerpo
es idempotente; identidad conflictiva o evento inválido van a DLQ con confirmación previa al ACK.
Error transitorio conserva la entrega y reconecta con espera de 1s.

Almacén privado con roles audit_writer y audit_reader. Propiedad/migración solo al inicializar;
ningún rol de runtime modifica/elimina/trunca la evidencia. No usar un hash simple para afirmar
inmutabilidad frente al dueño del host. No hay firma digital, WORM ni alta disponibilidad.
Retención sin borrado automático en este prototipo; crecimiento requiere política posterior.

Core publica GET /api/admin/audit-events y comprueba sesión ADMIN vigente en cada consulta.
Consulta por HTTPS al Auditor con secreto dedicado; no se publica su puerto ni SQL/AMQP. Cada
lectura registra actor verificado e instante antes de revelar resultados. TLS verificado en
HTTP, AMQP y SQL del perfil local; HTTP permitido solo explícitamente en fixtures aislados.

## Alternativas

- Solo stdout/Caddy: no conserva semántica ni ofrece investigación correlacionada del dominio.
- Consulta directa de tablas Core/VOD: rompe propiedad y acopla el Auditor a bases privadas.
- Envío HTTP síncrono al Logger: añade su disponibilidad al camino de cada operación.
- SIEM/ELK completo: añade despliegue y operación innecesarios para el escenario de P3.

## Consecuencias

Un contenedor y base adicionales; adaptación acotada Core/VOD, topología y arranque. VOD añade
una lectura SESSION al auditar mutaciones para conservar actor cuando WRITE es denegado.
El outbox crece durante fallos; sin purga implícita. Las consultas no sustituyen autorización,
backups o respuesta al incidente. No se añade alerta automática en este incremento.

Paginación por posición de ingreso; el Logger serializa la asignación y commit de posiciones
para no omitir inserciones concurrentes entre productores. Los eventos conservan instante de
origen, pero no se promete orden global de acciones ni un snapshot entre páginas.

## Verificación

Pruebas negativas de esquema/claves JSON duplicadas, roles SQL y acceso HTTP; ensayo con
Core/VOD/RabbitMQ/PostgreSQL reales, permisos, idempotencia, rollback, reinicios, caída del
broker, recuperación de pendientes, DLQ, correlación y medición de visibilidad. Ejecutar mediante
[el runner de auditoría](../../tests/integration/audit/README.md).
