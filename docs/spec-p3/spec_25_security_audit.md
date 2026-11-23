# SPEC-25 — Security Logger y Auditor (P3)

Responsable: Ivan Castellanos. Decisión: [ADR-028](../adr/ADR-028-security-logger-auditor.md).
RF relacionados: RF-058/060/076/078/079. RNF: RNF-022/024/025/027/028/032.
P3 incorpora trazabilidad de seguridad sin renumerar el catálogo P2.

## Escenario y tácticas

- Fuente: cuenta autenticada que intenta modificar grabaciones de otro streamer.
- Estímulo: solicitudes PATCH, publicación o eliminación sobre VOD ajenos.
- Artefacto: autorización de VOD y evidencia de operaciones sensibles de VOD/Core.
- Ambiente: operación normal; se prueba adicionalmente caída/reinicio de Logger y broker.
- Respuesta: el dominio rechaza; guarda actor verificado, acción, objetivo, instante,
  resultado y requestId en su outbox. Logger conserva y Auditor permite reconstruir la secuencia.
- Medida: cada intento del ensayo nominal aparece una vez en la consulta; objetivo de
  visibilidad <=2 segundos con dependencias saludables. Las acciones autorizadas también se
  registran. Reinicios conservan evidencia y pendientes; reentregas no duplican eventos.

Táctica principal: Maintain Audit Trail. El patrón aplicado es Security Logger and Auditor.
Authorize Actors y Limit Access protegen las consultas. La correlación facilita investigación;
no implementa detección automática ni promete no repudio criptográfico o identificar a la
persona física detrás de una sesión. El servicio no decide los permisos del negocio.

## Alcance

Core: denegaciones de requireAdministrator, listados administrativos exitosos y cambios de
estado confirmados. VOD: PATCH/PUBLISH/DELETE y retirada administrativa REMOVE, éxitos y
rechazos 401/403 dentro de esos handlers. Rechazos previos del proxy, bearer malformado/CSRF
antes del controlador Core, otros dominios, subtítulos y lecturas masivas HLS no se incluyen.
Actor nulo significa no verificado, nunca se toma de un header público. VOD solicita contexto
SESSION antes de la autorización de escritura cuando la auditoría está activada.

## Diseño y contratos

`services/audit`: Go/PostgreSQL, Logger AMQP y Auditor HTTP en un proceso nuevo para esta
responsabilidad transversal. Esquemas, eventos y consultas en contracts/p2.json, generados por
contracts/check_p2.py. La contribución a vistas se encuentra en [arquitectura](../arquitectura_c4_cnc_despliegue.md#contribución-p3--security-logger-y-auditor).
Dos routing keys y suscripciones separadas por productor protegen la
atribución mediante ACL RabbitMQ. No hay acceso a bases ajenas.

Eventos: actorId nullable, action, targetType/targetId, outcome SUCCESS/DENIED, reasonCode.
Envelope: eventId UUID, schemaVersion=1, aggregateId=audit:eventId, sequence=1, productor,
instante UTC y correlationId=requestId. Sin cuerpos, cookies, direcciones, motivos libres ni
credenciales. Los nombres de campo son exactos; campos adicionales, variantes de mayúsculas y claves JSON
duplicadas se rechazan.

Core escribe outbox junto con el cambio de estado; denegaciones en transacción REQUIRES_NEW.
VOD escribe en su outbox con el comando; un rechazo usa un commit independiente con límite
2s. Idempotencia de comandos conserva una sola acción lógica exitosa. Cada intento denegado
es un evento distinto. Publicaciones persistentes/mandatory con confirm; ACK del consumidor
solo después de persistir. Error SQL deja entrega pendiente. Evento inválido/conflictivo va a
DLQ con publicación confirmada. Retención sin purga automática para este prototipo.

Consulta pública: GET /api/admin/audit-events, sesión ADMIN vigente comprobada en Core.
Core consulta GET /internal/audit/events con secreto exclusivo; el servicio no tiene puerto
publicado. Filtros: actorId, targetId, action, outcome, producer, requestId, from inclusivo,
to exclusivo, cursor y limit (1..100; default 50). Orden por posición de ingreso, no por reloj
del cliente/productor. Los retrasos pueden alterar el orden temporal original; usar occurredAtUtc
para reconstrucción. Cursor es decimal de posición; no una autorización. Cada lectura permitida
inserta actor verificado e instante en audit.reads antes de revelar eventos. Core limita a 256 KiB
y 3s la respuesta completa del Auditor, incluido su cuerpo. Las consultas mal codificadas se
rechazan; el requestId de errores VOD coincide con su header y evidencia normalizada.

PostgreSQL: propietario de esquema reservado para inicialización; runtime audit_writer tiene
INSERT/SELECT, audit_reader solo SELECT y transacciones de lectura. Ninguno tiene UPDATE,
DELETE, TRUNCATE, CREATE o propiedad de tablas. No protege contra el dueño del host/DB; no es
WORM ni firma digital. La disponibilidad nominal de consulta verifica SQL; no certifica ausencia
de pendientes del broker. La caída del Logger/broker conserva operaciones en outbox; falta de
persistencia local de evidencia impide confirmar una acción sensible o su consulta.

### Consulta administrativa Web

`/admin/audit`, enlazada desde `/admin`, presenta la evidencia de Core/VOD en modo solo lectura.
Reutiliza la sesión Web y GET `/api/admin/audit-events`; Core comprueba ADMIN en cada petición.
La entrada pasa por el WAF y Caddy existentes, sin nuevos puertos ni acceso directo al Auditor.
Los registros propios de ModSecurity y otros productores no forman parte de esta vista.

La consulta inicial abarca las últimas 24 horas. Permite última hora, últimos siete días o un
intervalo personalizado; fechas locales se convierten a UTC, con inicio inclusivo y fin exclusivo.
Filtros por productor, acción, resultado y coincidencia exacta de actorId/targetId/requestId.
Los filtros se aplican al enviar el formulario; no se ejecuta una consulta por cada pulsación.

Cada página contiene hasta 50 eventos, ordenados por posición de ingreso ascendente. Siguiente
y Anterior usan cursores, conservando los mismos límites temporales de la consulta; cambiar
filtros reinicia la página y cierra el detalle. La UI no solicita un total global ni acumula todas
las páginas en memoria. El detalle distingue fecha de origen y recepción, muestra el código de
motivo, identificadores, correlación y JSON del evento. No permite editar o borrar evidencia.

Carga, resultado vacío, fallo y falta de permisos tienen estados distintos. Las consultas
obsoletas se cancelan y sus respuestas tardías se ignoran; un nuevo 403 retira los resultados
visibles. No hay polling, descarga masiva ni persistencia del historial en localStorage.

## Aceptación y verificación

La suite reproducible se documenta en tests/integration/audit/README.md. Debe ejecutar:

1. Usuarios A/B y grabación de A: B recibe rechazo y no modifica datos; evidencia identifica B.
2. A modifica y ADMIN retira/cambia estado: evidencia solo tras commit, un éxito por comando.
3. Visitante/usuario común no consulta; ADMIN sí; filtros, límites, cursor y lectura auditada.
4. Reinicio Logger y broker: recuperación sin pérdida/duplicación; eventoId conflictivo a DLQ.
5. Credenciales de runtime no alteran/borran historial; productor VOD no publica como Core.
6. Eventos sin cuerpos/secretos; payload inválido/duplicado/inyección no se acepta.
7. Medir desde respuesta del dominio hasta consulta y reportar valores reales. <=2s es objetivo,
   no evidencia acreditada por compilar. La Persona 6 realiza análisis de rendimiento completo.
8. Web: filtros, fechas, siguiente/anterior, detalle/foco, consultas obsoletas, vacío/error y
   403 tras revocar ADMIN; navegación y consultas legítimas a través del WAF, escritorio y móvil.

La ejecución y sus límites se reportan por separado; la existencia de esta SPEC no acredita pruebas.

El despliegue completo aplica [ADR-030](../adr/ADR-030-segmentacion-red.md): solo Core
comparte la red de consulta con Auditor entre aplicaciones; el broker comparte una bridge
exclusiva con Auditor y `audit-db` solo admite al runtime Audit. Los demás productores
publican en el broker por `messaging`, sin acceso directo a la API ni al almacén de auditoría.
