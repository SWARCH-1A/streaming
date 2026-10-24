# ADR-016: RabbitMQ y AMQP 0-9-1 para eventos y trabajos P2

- Estado: aceptada para la integración inicial; recuperación integrada por acreditar
- Fecha: 2026-10-23
- Responsable: Integración, con consumidores Streaming, Core y Chat
- SPEC/contratos: SPEC-04/05/07/10/17/24; P2-07; contratos de eventos P2
- Amplía ADR-015. Sustituye la entrega HTTP de eventos de ADR-008/010 únicamente en el perfil P2.

## Contexto

SPEC-17 exige un broker interno compartido, entregas durables y suscripciones independientes.
Core y Chat deben recibir el mismo ciclo aunque uno esté detenido. ADR-015 conserva HTTP para
consultas/autorización inmediata y la propiedad de datos de cada servicio. RabbitMQ no estaba
seleccionado por esa decisión.

La primera integración implementa dos familias: ciclo de sesión y snapshot público Discovery.
El registro conserva nueve familias y dieciocho suscripciones. Cuenta/suspensión, grabación,
VOD, seguimiento, recuperación y notificaciones requieren payloads/implementaciones de sus
responsables; aprovisionar sus colas no acredita esas funciones ni completa SPEC-17.

## Decisión

1. RabbitMQ 4.3.7-management, AMQP 0-9-1 sobre TLS con verificación de CA y hostname.
   Un nodo con nombre estable y volumen persistente en el perfil local P2. Sin puertos publicados.
   La imagen se fija por versión y digest del índice verificado en el registro oficial de Docker.
2. Java usa Spring AMQP gestionado por el BOM existente; Rust, Lapin 4.12.3/Tokio/Rustls/ring;
   Go, rabbitmq/amqp091-go 1.10.0. Se conservan los runtimes y las clases de dominio propias.
   JSON Schema es el contrato neutral; los adaptadores no comparten clases entre lenguajes.
3. Vhost streaming-p2; exchange topic durable p2.events; una cola quorum durable por suscripción
   lógica. Réplicas del mismo consumidor compiten; Core y Chat tienen copias independientes.
   El inicializador genera la topología desde el registro neutral.
4. Las routing keys conservan eventType. Ciclo añade .initial o .update para filtrar initialLive;
   trabajos por medio añaden .inbox/.email/.push. El broker no inspecciona JSON. Los consumidores
   validan el envelope/payload, identidad, productor y versión; un header no concede permisos.
5. Publicaciones persistentes y mandatory, con publisher confirm y rechazo de devoluciones
   por ausencia de ruta. El outbox solo se marca publicado después de confirmación durable.
   Streaming materializa el envelope P2 en su transacción, incluyendo channelId/firstLiveAtUtc/
   initialLive; conserva ID y cuerpo al reintentar. CorrelationId usa el eventId opaco del outbox:
   no se fabrica una correlación con credenciales ni con una petición histórica desconocida.
6. Inbox con identidad (consumer,eventId), fingerprint y efecto atómicos antes del ACK manual.
   Core usa SQL local; Chat, Lua/Redis con AOF. Las versiones de ciclo y proyección no se comparan
   entre sí. Core conserva una barrera de ciclo separada; Chat conserva la barrera incluso si
   expira la caché de sala. Los eventos no autorizan operaciones protegidas.
7. Un consumidor por cola y prefetch 16. Errores transitorios: tres reintentos tras 1, 5 y 30 s;
   inválidos/conflictos: DLQ inmediata. Antes de ACK del origen se confirma la publicación de
   retry/DLQ, con el cuerpo original y headers de consumidor/causa/intentos/fechas/ruta.
   Las colas de espera usan TTL exclusivamente para transferir al consumidor, con dead-letter
   at-least-once y overflow reject-publish; no para eliminar trabajo pendiente.
8. Sin TTL automático para cola principal, DLQ ni inbox P2. Se retienen identidad/hash y barreras,
   sin copiar todos los payloads al inbox. Esta selección cubre recuperación/redrive sin una
   ventana menor que la responsabilidad pendiente. Su crecimiento exige seguimiento y una
   política de compactación coordinada antes de ampliar carga/retención; P1 mantiene sus TTL.
9. Límite inicial por cola 256 MiB, mensaje 64 KiB, reserva libre de disco 1 GiB y umbral de
   memoria del broker 512 MiB. Al alcanzar capacidad se rechaza publicación y se conserva
   el outbox; no se descartan mensajes antiguos. Reintento del publicador 1/2/5/10 s sin
   agotar por tiempo una caída del broker. Conexión/confirmación presupuestadas a 3 s;
   heartbeat 30 s. El presupuesto nominal hasta interfaz sigue siendo cinco segundos.
10. Credenciales separadas por servicio; ACL de recurso y routing key según productores/
    suscripciones. Solo el operador administra topología o redirige DLQ; las aplicaciones no
    crean recursos. El inicializador conserva secretos existentes y genera certificados/
    definiciones privadas en estado ignorado. La clave de CA no se monta en servicios.
11. Inspección privada, métricas de colas y redrive/cierre explícitos con auditoría persistente.
    El redrive apunta solo a la suscripción original, conserva eventId/body y reinicia el
    presupuesto operativo de intentos. Publica con confirmación antes de ACK de DLQ.
12. Activación optativa --broker en el arranque local. Cada servicio usa HTTP P1 o AMQP P2
    para entrega de eventos, sin doble publicación. Las consultas de contexto/rebuild siguen
    por HTTP, el video por HLS y Chat por WebSocket. Core conserva sus módulos en un proceso.

## Opciones consideradas

| Opción | Ajuste, esfuerzo y riesgo |
| --- | --- |
| RabbitMQ | Elegida: colas independientes, confirms/ACK, routing y herramientas de operación encajan con eventos/trabajos. Añade Erlang/RabbitMQ, configuración y costo de persistencia. |
| NATS JetStream | Viable: persistencia, consumidores durables y reentrega. Exige concretar igualmente DLQ/redrive/ACL y verificar los tres adaptadores. |
| Kafka | Viable para registro particionado retenido y reprocesamiento. No hay requisito actual que justifique adoptar ese modelo para esta primera integración de colas/trabajos. |
| Mantener solo HTTP | Conservado en P1. No satisface el broker compartido y las suscripciones exigidos para P2. |
| Un inbox compartido o una sola cola para Core/Chat | Incumple la propiedad de datos o distribuye trabajo entre dominios cuando ambos deben recibirlo. |

La elección es por ajuste funcional, no por un benchmark comparativo. Quorum permite transferencia
durable a través del dead-letter de reintentos; tiene mayor costo que una cola clásica. Un quorum
de un miembro **no proporciona alta disponibilidad** ni tolerancia a pérdida del disco/host.

## Consecuencias y transición

Migraciones aditivas conservan el formato HTTP P1. Detener emisiones y los tres servicios antes
de cambiar de transporte. Los eventos aún pendientes mantienen identidad; los ya publicados P1
no se vuelven a emitir. Reiniciar con volúmenes conservados; no ejecutar reset para migrar.
Una reversión de transporte requiere detener consumidores y reconciliar pendientes, evitando
tratar el ACK del broker como confirmación de todos los servicios. No se promete cambio en caliente.

Durante caída del broker el outbox crece; Core/Chat pueden atender operaciones cuyo contexto y
almacenamiento sigan disponibles. La frescura vencida se refleja como UNKNOWN. Presión de disco
puede frenar nuevas publicaciones; requiere intervención, no eliminación silenciosa. Broker y
volumen son infraestructura de comunicación, no una base de negocio adicional.

## Verificación y revisión

La compilación y validación de contratos no acreditan recuperación integrada. Para aceptación:
eventos reales, consumidores detenidos, fallos entre commit/publicación/confirm/ACK, duplicados,
desorden/conflictos, DLQ/redrive, ACL negativas, validación TLS y reinicio con volumen. Medir hasta
interfaz el objetivo nominal; faltan los recorridos VOD/Notificaciones de SPEC-15/17.

Revisar con los dueños al añadir payloads pendientes, fijar sus runtimes, ampliar carga,
necesitar HA, compactar inbox o cambiar retención/capacidad. La matriz inicial es Java 25/
Spring Boot 4.1.1 + Spring AMQP, Rust 1.98/Tokio + Lapin y Go 1.26 + amqp091-go. Los runtimes
VOD/Notificaciones continúan pendientes y requieren su propia comprobación.

Fuentes: [confirms y ACK](https://www.rabbitmq.com/docs/confirms),
[quorum y dead-letter](https://www.rabbitmq.com/docs/4.3/quorum-queues),
[clientes](https://www.rabbitmq.com/client-libraries/devtools),
[NATS JetStream](https://docs.nats.io/concepts/jetstream),
[diseño Kafka](https://kafka.apache.org/41/design/design/).

Evidencia de esta integración inicial: validadores P1/P2 y configuración Compose ejecutados;
Core empaquetado con JDK 25 y Chat compilado para Windows y Linux. No se ejecutaron suites
de pruebas. La comprobación de compilación Rust en este host quedó detenida por ausencia del
linker MSVC link.exe; Cargo.lock está resuelto y el código Rust formateado/parseado. Docker
no respondió para levantar el conjunto, por lo que entrega/recuperación integrada sigue pendiente.
