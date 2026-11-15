# SPEC-17 — Broker y entrega durable de eventos y trabajos

**Prioridad:** P2. **Responsable:** Mensajería / integración. **Estado:** nueve familias integradas en el stack local, VOD/SMTP reales verificados; push y ventanas de fallo exhaustivas pendientes.
**RF:** RF-011, 020…022, 052, 056, 061, 079, 082. **RNF:** RNF-001, 006, 009…010, 014, 020…024, 025, 038, 041…047, 050…051.

## 1. Contexto y problema

Streaming debe informar independientemente a Core y Chat, VOD debe procesar segmentos y
Notificaciones debe distribuir entregas. Una entrega perdida o repetida no puede dejar datos
incoherentes ni perder trabajos aceptados.

## 2. Definición del componente

Un único broker interno compartido y adaptadores de publicación/consumo en los servicios.
No se implementa un motor de mensajería desde cero. RabbitMQ y AMQP 0-9-1 están seleccionados en
[ADR-016](../adr/ADR-016-rabbitmq-eventos-p2.md); esta SPEC conserva la semántica exigida.

## 3. Historia de usuario

Como responsable de un servicio, quiero recibir mis eventos aunque otro consumidor esté caído,
poder reintentarlos y conocer los trabajos que requieren intervención.

## 4. Alcance

Aprovisionamiento reproducible, autenticación/ACL, nombres lógicos, suscripciones durables,
publicación confirmada, outbox/inbox, dead-letter/redrive, observabilidad y recuperación. HTTP
continúa para consultas y autorización inmediata. El broker no transporta bytes de video.

## 5. Requisitos funcionales

- Cada suscripción de dominio recibe su copia; réplicas del mismo consumidor comparten trabajo.
- Entrega al menos una vez: eventId estable y dedupe por consumidor; no se promete exactly-once.
- Versiones de agregado impiden regresiones por desorden; una secuencia incompleta se reconcilia.
- Los trabajos de bandeja, correo y push se enrutan según medio, con intentos independientes.
- Cada productor puede recuperar publicaciones confirmadas localmente tras reinicio.

## 6. Criterios de aceptación

- [ ] CA-01: Core y Chat reciben el mismo ciclo de sesión; detener Chat no bloquea a Core ni pierde su copia pendiente.
- [ ] CA-02: matar el publicador entre commit local/publicación/confirmación repite el mismo eventId sin duplicar efectos.
- [ ] CA-03: matar consumidor antes/después del commit y antes del ACK recupera el efecto una sola vez lógicamente.
- [ ] CA-04: duplicados y versiones viejas se ignoran; mismo ID con contenido distinto se registra como conflicto y no se aplica.
- [ ] CA-05: payload inválido no entra en un bucle infinito: se conserva en DLQ con causa, origen e identidad y puede redirigirse con autorización.
- [ ] CA-06: reiniciar broker y servicios con volúmenes conservados no pierde eventos confirmados ni suscripciones.
- [ ] CA-07: una credencial de VOD no publica eventos de cuentas ni consume trabajos de correo; el broker no tiene listener público.
- [ ] CA-08: correo confirmado no se repite por un fallo posterior de push. La cola de segmentos detenida no bloquea ciclo de sala.
- [ ] CA-09: métricas muestran backlog, edad, reintentos y DLQ; la ruta nominal cumple cinco segundos hasta la interfaz.

## 7. Diseño técnico y datos

[Mensajería P2](../contratos_p2_eventos.md) y `contracts/p2.json.events` fijan nueve tipos de
mensaje, productores y suscripciones lógicas. Los nueve payloads tienen schemas ejecutables
y las suscripciones se materializan en la topología RabbitMQ de ADR-016. Streaming consume
además su cola de estado de cuenta con inbox durable y cercado por versión. La aceptación
por familia comprueba el efecto real de cada proveedor, además de la entrega de transporte.

El outbox se escribe con el cambio de dominio. Solo se marca publicado tras confirmación durable
del broker. El inbox tiene unicidad `(consumer,eventId)`, fingerprint y efecto/estado de trabajo
persistidos atómicamente. Un ACK de transporte no acredita entrega SMTP ni publicación VOD.

Los workers no mantienen una transacción SQL abierta mientras esperan un proveedor externo.
Persisten un intento y resultado; si el resultado externo es desconocido, lo registran como tal.
Ordenar por partición ayuda, pero no reemplaza chequeo de versión ni detección de huecos.

El despliegue documenta capacidad, límite de mensajes, prefetch/concurrencia, tiempo de reentrega,
backoff, retención y ventana de dedupe. La retención cubre reentrega/redrive y reconstrucción; no
se acepta un TTL que elimine una responsabilidad durable pendiente sin recuperación.

## 8. Dependencias y contratos de integración

| Publicador / consumidor | Contrato |
| --- | --- |
| Streaming → Core/Chat | Ciclo por suscripción propia; snapshots públicos para Discovery |
| Streaming/Media → VOD | Segmentos confirmados y cierre de grabación con índice |
| Core → Notificaciones | Seguimiento, estado de cuenta y trabajos de recuperación |
| Notificaciones → workers | Trabajo filtrado por medio y target/deliveryId |
| VOD → Chat | Disponibilidad y borrado con versión |

Cada dueño implementa su outbox/inbox. El responsable del broker entrega topología y librería o
adaptador neutral, sin importar clases de dominio de otro lenguaje.

## 9. Decisiones y preguntas abiertas

P2-07: [ADR-016](../adr/ADR-016-rabbitmq-eventos-p2.md) fija producto, protocolo, SDK iniciales,
topología, tamaños, reintentos, DLQ/redrive y retención. ADR-017 incorpora la publicación desde
Cuentas y contratos privados de recuperación. Notificaciones implementa los consumidores en TypeScript/PostgreSQL y SMTP según ADR-020;
Mailpit valida el correo en local. La entrega push externa requiere provisión. La compatibilidad
de runtimes futuros y la aceptación completa se verifican por consumidor.
La entrega durable, suscripciones independientes y un único broker son obligatorios. El ADR
incluye matriz de compatibilidad Java/Rust/Go y runtimes nuevos, más evidencia de recuperación.

## 10. Verificación

Usar mensajes reales de cada familia, reinicios en las ventanas de confirmación, duplicados,
desorden, payload inválido y consumidor detenido. Comprobar ACL y recuperación de volúmenes.
La prueba de una cola con texto arbitrario no acredita contratos de los servicios ni asincronía
funcional; SPEC-15 exige grabación o entrega real procesada por un worker.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. Introduce dependencia operativa y coordinación por contratos. La instancia única
no garantiza alta disponibilidad; el runbook debe describir recuperación y efecto de su caída.
No se bloquea un commit de dominio esperando a todos los consumidores.

## Integración inicial disponible

El [perfil RabbitMQ](../../infra/broker/README.md) conecta los eventos de Streaming con
core.lifecycle, core.discovery y chat.lifecycle. Se verificaron emisión real, efectos en Core/Chat,
Discovery, duplicados/desorden/conflictos, consumidores detenidos, reintentos, DLQ/redrive y reinicio
con outbox/colas conservados. La prueba automatizada de infraestructura y su alcance están en
el runbook del perfil. Los criterios anteriores conservan pendiente la aceptación completa:
faltan las ventanas exactas de commit/confirm/ACK de todos los consumidores y la medición
hasta interfaz bajo carga; no se infieren de las pruebas de transporte. Las funciones VOD/SMTP se acreditan además mediante los recorridos reales descritos abajo.

El gestor infra/local/dev.py incorpora el broker por defecto en init/build/up/status/down.
La inicialización conserva datos y secretos existentes; up espera salud de RabbitMQ y los demás
servicios con healthcheck, además de Web/API. Los perfiles de prueba P1 mantienen su aislamiento.

## Recuperación y consumidores verificados

La prueba de infraestructura se ejecutó el 2026-11-03: TLS, ACL negativas, suscripciones
independientes, reinicio con envelope conservado y operaciones inspect/redrive/close con
auditoría pasaron. Los escenarios VOD/Chat ejecutaron segmentos/cierre, disponibilidad,
replay y borrado; seguimiento/notificaciones y recuperación ejecutaron bandeja y SMTP real.
El proveedor push sigue sin configurar y no se presenta como una entrega exitosa.

Streaming mantiene índices parciales de orden, predecesor y alerta del outbox. Los claims
excluyen filas cerradas; la alerta no recorre todo el backlog por cada entrega antigua.
La prueba PostgreSQL con 30.000 snapshots conserva el orden por agregado, impide reclamar
el siguiente antes del ACK y ejecuta cien ciclos claim/alert/ACK en un presupuesto de cinco
segundos. No se borran ni rejuvenecen snapshots atrasados para ocultar la edad de cola;
Discovery solo aplica versiones mayores y reconstruye por corte consistente/watermark.

La topología vigente conserva dieciséis suscripciones con efecto (ADR-027). Chat no mantiene
una réplica de identidad y Core no mantiene una réplica del catálogo VOD. Las antiguas colas
`chat.account` y `core.vod` se archivan con la operación privada `retire` antes de retirarlas.
Streaming 0008 conserva P1 pero publica `RECONNECT_GRACE` en el envelope P2 de pérdida de fuente;
las pruebas PostgreSQL comprueban ambos cuerpos y la inmutabilidad histórica.

El ensayo final de reconexión con cuatro pistas, tras aplicar 0008 en el contenedor desplegado,
conservó la sesión y finalizó el VOD con sus cuatro calidades. Las 80 colas vigentes quedaron sin
mensajes pendientes ni DLQ y el outbox quedó vacío. Los eventos inválidos históricos se cerraron
con auditoría tras comprobar sesión ENDED y barreras terminales/generación posterior; no se
reescribieron ni se redirigieron cuerpos inválidos. El smoke también verifica selección de un
ID DLQ detrás de otro mensaje y conserva sin ACK la otra entrega.
