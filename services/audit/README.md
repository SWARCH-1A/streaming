# Security Logger y Auditor — Vault TV P3

Recibe evidencia Core/VOD desde RabbitMQ y conserva eventos en PostgreSQL propio. La consulta
se expone mediante Core a administradores activos, sin publicar el servicio ni su base.
[Escenario y aceptación](../../docs/spec-p3/spec_25_security_audit.md),
[decisión](../../docs/adr/ADR-028-security-logger-auditor.md).

## Ejecutar en local

Desde la raíz:

```sh
python3 infra/local/dev.py init
python3 infra/local/dev.py build
python3 infra/local/dev.py up
```

El gestor incorpora infra/audit/compose.yaml, genera certificados/secretos nuevos conservando
los existentes, y añade audit/audit-db. Reiniciar broker después de init aplica usuarios,
bindings y ACL nuevas. En una instalación ya iniciada, ejecutar `docker compose` con los tres
manifests y `restart broker` antes de comprobar la primera entrega a auditoría:

```sh
docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml -f infra/audit/compose.yaml restart broker
```
No ejecutar reset para habilitar auditoría: elimina los datos de desarrollo.

Una sesión ADMIN consulta GET `/api/admin/audit-events?targetId=vod_example&limit=20`
a través del origen HTTPS habitual de Vault TV. Admite Cookie web o Bearer móvil de Core.
Para provisionar ADMIN usar el mecanismo existente de Core; el registro público no concede rol.
La consola Web está en **Administración → Auditoría de seguridad** (`/admin/audit`).
Permite filtrar por Core/VOD, acción, resultado, fechas e IDs exactos; ofrece páginas de 50
eventos y detalle de solo lectura. La consulta inicial usa las últimas 24 horas y las fechas
se muestran en hora local. El historial no incluye los registros propios del WAF.
No hay alerta automática en este incremento.

Filtros: actorId, targetId, action, outcome, producer, requestId, from, to, cursor y limit.
Respuesta: items con position, receivedAtUtc y event; nextCursor null al agotar la página.
Orden de ingreso ascendente; para reconstrucción temporal usar occurredAtUtc y requestId.
Las fechas tienen zona explícita, desde inclusivo/hasta exclusivo. Límite 1..100, default 50.

## Configuración

- AUDIT_WRITER_DATABASE_URL / AUDIT_READER_DATABASE_URL: roles separados, sslmode=verify-full como parámetro único, sin valores duplicados.
- AUDIT_BROKER_URL / AUDIT_BROKER_CA_FILE: identidad audit, AMQPS, CA y hostname verificados.
- AUDIT_QUERY_TOKEN: secreto exclusivo que posee Core, separado de productores y SQL.
- AUDIT_TLS_CERT_FILE / AUDIT_TLS_KEY_FILE: listener HTTPS :8096; sin puerto publicado.
- CORE_AUDIT_ENABLED=true, CORE_AUDIT_BASE_URL=https://audit:8096 y AUDIT_QUERY_TOKEN.
- VOD_AUDIT_ENABLED=true: outbox y actor SESSION para las mutaciones instrumentadas.
- AUDIT_DEVELOPMENT_HTTP / CORE_AUDIT_DEVELOPMENT_HTTP: solo fixtures aislados explícitos.

El inicializador de PostgreSQL crea propietario, esquema y roles; el runtime nunca migra como
superusuario. Tras desplegar, runtime writer puede INSERT/SELECT y reader solo SELECT;
UPDATE/DELETE/TRUNCATE/DDL quedan denegados. La lectura del historial también se registra.
La base retiene todos los eventos sin vencimiento automático en P3. Backup incluye audit-db y
las bases/outboxes de origen. Un backup lógico debe ejecutarse con la identidad operativa,
restaurarse en una base aislada y comprobarse antes de sustituir datos existentes.

## Fallos y diagnóstico

- Logger/broker caído: origen conserva outbox; reintenta sin cambiar eventId ni payload.
- Base del productor caída: no confirmar la acción sensible sin evidencia local.
- SQL auditoría caído: consumidor deja entrega sin ACK; consulta da 503, no lista vacía.
- Evento inválido/conflictivo: audit.core.dlq / audit.vod.dlq; inspección y redrive con
  herramientas existentes de infra/broker, nunca editar eventos almacenados.
- `/readyz` verifica conexión SQL; no acredita que todos los productores estén al día.
- Logs del servicio: mensajes técnicos acotados, nunca body/credenciales/URLs privadas.

Los administradores de host/DB pueden modificar los datos; este diseño no afirma WORM,
firma digital ni alta disponibilidad. Sin límite automático al crecimiento, observar espacio
de SQL/outboxes/colas y definir retención antes de ampliar uso/carga.

## Verificar

[Runner y alcance de pruebas](../../tests/integration/audit/README.md).

```sh
python3 tests/integration/audit/run.py
```

Construye imágenes desde el checkout y usa únicamente contenedores/red/bases desechables,
sin tocar volúmenes del despliegue local. Incluye validación, roles SQL y recorrido Core/VOD real.
