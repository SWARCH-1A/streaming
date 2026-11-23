# ADR-030 — Segmentación de red por propiedad de datos y dependencias

**Estado:** Aceptada para integración local. **Fecha:** 2026-11-22.
**Responsabilidad:** Integración / despliegue, SPEC-24; contribución P3 de segmentación.

## Contexto

El despliegue completo conectaba los servicios y sus almacenes a una misma red Docker.
Las credenciales y TLS protegían las operaciones, pero cualquier servicio podía abrir una
conexión al motor de datos de otro dominio. Logger/Auditor añade un almacén y una interfaz
de consulta que tampoco deben quedar al alcance de todos los productores de eventos.

## Decisión

Asignar explícitamente redes bridge del proyecto por propiedad y dependencia. Cada PostgreSQL
y Redis solo comparte una red `internal` con su propietario. Chat tiene redes distintas para
Redis y PostgreSQL. Streaming y su adaptador Media, en el mismo proceso Rust, comparten su
motor privado. MinIO usa otra red interna con VOD, el grabador lector y el inicializador;
las identidades S3 existentes distinguen escritura y lectura.

La zona de APIs une Caddy, Gateway, SSR y las APIs de dominio que ya se llaman entre sí.
Conserva el nombre lógico `default`, la subred/IPAM y las IP fijas de Caddy/Gateway para
actualizar instalaciones existentes sin superponer subredes ni cambiar proxies confiables.
Todos los servicios declaran su pertenencia; los almacenes dejan de unirse a esa red.

La red `messaging` solo incluye RabbitMQ, sus clientes de dominio y la herramienta de operación.
Auditor accede al mismo RabbitMQ por `audit-messaging`, exclusiva de esos dos servicios:
unirlo a `messaging` expondría también su listener HTTP a todos los clientes del broker.
`audit-query` permite la consulta Core–Auditor y `audit-data` separa su PostgreSQL.

`media` conecta Streaming, MediaMTX, el grabador y VOD; `smtp` une Notificaciones y Mailpit.
Estas dos redes y la zona de APIs conservan conectividad externa para los puertos publicados
y los proveedores configurables. Las demás son `internal: true`. Ningún almacén publica
puertos al host. Se conservan únicamente HTTPS, RTMPS y la UI local de Mailpit, con la política
loopback/LAN existente. No se añaden contenedores privilegiados ni acceso al socket Docker.

El inventario y la vista de despliegue vigentes están en
[segmentación P3](../p3/segmentacion-red.md); las declaraciones ejecutables viven en
`compose.yaml`, `infra/broker/compose.yaml` e `infra/audit/compose.yaml`.

## Alternativas

- Una red general con autenticación: conserva la exposición de todos los motores.
- Una red por base y dos redes generales API/broker: adecuada como base, pero insuficiente
  para medios, objetos, SMTP y la API privada de Auditor.
- Una red por cada llamada o reglas por puerto/dirección: permitiría límites más finos,
  a costa de más interfaces y operación. No se necesita para aislar los almacenes del prototipo.

## Consecuencias y límites

Los miembros de una misma red pueden alcanzar todos los puertos de sus pares. Un cliente
multihomed puede compartir más de una interfaz con otro: las etiquetas API/mensajería no
imponen por sí mismas rutas HTTP/AMQP exclusivas. TLS, permisos de servicio, roles SQL,
ACL Redis y credenciales RabbitMQ/S3 siguen siendo necesarios (ADR-028/029).

La segmentación limita el movimiento lateral directo desde otro contenedor; no protege el
almacén del compromiso de su propio servicio autorizado, ni de un administrador del host o
Docker que cambie las conexiones. No se introduce una política de salida por destino.
Los Compose de desarrollo aislado por servicio y los fixtures P1 mantienen su alcance previo.

El primer `up` recrea los contenedores cuya conexión cambió; conservar volúmenes y realizarlo
sin emisiones activas. No se requiere borrar datos, rotar secretos ni instalar certificados.

## Verificación

`infra/local/network_policy.py` valida el modelo fusionado y las pruebas de regresión rechazan
almacenes en la zona API, puertos SQL publicados, redes de host, contenedores privilegiados,
la pérdida de `internal` y el acceso amplio a Auditor. La aceptación Docker reutiliza exactamente
las conexiones/IPAM de Compose con listeners TCP desechables: prueba propietarios, servicios
ajenos, DNS e IP directa y dependencias permitidas. El smoke separado usa los servicios reales,
TLS, certificados nuevos y volúmenes aislados. Comandos y alcance en
[la guía de pruebas](../../tests/integration/network-segmentation/README.md).

Semántica de aislamiento: [redes Compose](https://docs.docker.com/reference/compose-file/networks/)
y [bridge de Docker](https://docs.docker.com/engine/network/drivers/bridge/).
