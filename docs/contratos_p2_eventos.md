# Contratos de eventos y trabajos P2

[contracts/p2.json](../contracts/p2.json) registra tipos, productores y suscripciones. Los envelopes/payloads ejecutables
de ciclo y Discovery están definidos; las otras siete familias siguen como pending-owner,
sin schemas vacíos que simulen disponibilidad. [SPEC-17](spec-p2/spec_17_broker.md) define entrega y operación.
Los nombres son lógicos; su mapeo a exchanges/topics/streams/queues depende del broker elegido.

## Envelope

| Campo | Regla |
| --- | --- |
| eventId | ID estable, único por publicación lógica; mismo ID/payload en reintentos |
| eventType / schemaVersion | Tipo registrado terminado en v1 y versión 1 |
| producer | Identidad lógica autorizada por ACL; Streaming/Media se concreta con el escritor |
| aggregateId / sequence | Identidad y versión por agregado; sin orden global |
| occurredAtUtc | Instante original del productor en UTC, no recepción |
| correlationId | Correlación de operación; no contiene sesión de usuario ni credenciales |
| payload | Schema especializado del tipo; nunca bytes de video o secretos |

Los IDs de mensaje no son IDs de operación HTTP ni credenciales. El transporte puede añadir
headers de intento/trace, sin reescribir el envelope para simular un evento nuevo.

## Registro de mensajes y suscripciones

| Tipo | Productor | Schema | Suscripciones independientes |
| --- | --- | --- | --- |
| `stream.session.changed.v1` | Streaming | P2EventStreamSessionChanged | `core.lifecycle`, `chat.lifecycle`, `notifications.live`, `vod.lifecycle` |
| `stream.discovery.snapshot.v1` | Streaming | P2EventStreamDiscoverySnapshot | `core.discovery` |
| `recording.segment.available.v1` | Streaming/Media | P2EventRecordingSegmentAvailable | `vod.segments` |
| `recording.closed.v1` | Streaming/Media | P2EventRecordingClosed | `vod.recording` |
| `channel.follow.changed.v1` | Core | P2EventChannelFollowChanged | `notifications.follow` |
| `account.status.changed.v1` | Core | P2EventAccountStatusChanged | `streaming.account`, `chat.account`, `vod.account`, `notifications.account` |
| `vod.availability.changed.v1` | VOD | P2EventVodAvailabilityChanged | `chat.vod`, `core.vod` |
| `notification.delivery.requested.v1` | Notificaciones | P2EventNotificationDeliveryRequested | `notifications.delivery.inbox`, `notifications.delivery.email`, `notifications.delivery.push` |
| `credential.recovery.delivery.requested.v1` | Core | P2EventCredentialRecoveryDeliveryRequested | `notifications.recovery.email` |

Réplicas de una suscripción compiten por su trabajo. Core y Chat tienen suscripciones distintas
y ambos reciben el ciclo. Los bindings de delivery filtran payload.medium: INBOX, EMAIL y PUSH;
no se entrega un job EMAIL a todos los medios. Notificaciones recibe ciclo con el filtro `payload.initialLive=true`: solo la primera
transición reproducible. Persistir sessionId procesado impide reseleccionar seguidores al
reconectar. channelId y firstLiveAtUtc identifican destino e instante original; las transiciones
posteriores conservan firstLiveAtUtc y publican initialLive=false.

## Identidad y orden por familia

Ciclo: aggregateId `session:<sessionId>`, sequence=sessionVersion; generación distingue emisiones.
Proyección: `stream:<streamId>`, sequence=projectionVersion; el payload conserva discoveryPosition
para cortes/rebuild. No se compara sessionVersion con projectionVersion.

Segmento: unicidad `(sessionId,renditionId,sourceGeneration,sequence)`; el sequence del envelope
no se usa como orden global de todas las rendiciones. Un segmento con hash diferente y misma
identidad es conflicto. Llegar un segmento de generación anterior no lo invalida si pertenece
al inventario legítimo de la misma grabación; no reemplaza otro segmento ni la fuente vigente.

Cierre de grabación: un índice final confirmado por sesión/generación. Un cierre repetido conserva
identidad/fingerprint; cierres incompatibles producen conflicto. `stream.session.changed=ENDED`
no sustituye este mensaje ni acredita completitud del archivo.

Seguimiento: clave channelId/userId con versión monotónica incluso tras unfollow.
Cuenta: userId/accountVersion; una reentrega vieja no reactiva una cuenta suspendida.
VOD: vodId/version; DELETED es terminal para ese vodId y no se recrea desde un cierre atrasado.
Entrega: deliveryId; cada destino/medio es independiente de los demás intentos.

## Confirmación y recuperación

1. Productor confirma cambio e intención en su transacción local/outbox.
2. Publicador envía con identidad estable y espera confirmación durable del broker.
3. Consumidor valida identidad/schema, deduplica y persiste inbox/efecto o responsabilidad durable.
4. Confirma entrega después de ese commit. Los efectos externos guardan un intento y resultado.

Pérdida de ACK puede repetir cualquiera de esos pasos. La deduplicación es por consumidor;
no hay un inbox global compartido por todos los servicios. Fingerprint distinto con mismo ID
se conserva como conflicto; no se reemplaza el payload viejo silenciosamente.

Pérdida/desorden de eventos de proyección se repara con corte consistente/watermark HTTP.
Mensajes de Chat no se reconstruyen de snapshots de sesión. VOD recupera referencias faltantes
desde el índice final y verifica objetos. SMTP puede haber entregado aunque se pierda la respuesta;
se registra UNKNOWN en lugar de prometer exactly-once externo.

## Reintentos, dead-letter y redrive

La política elegida en P2-07 fija conexión, confirmación, prefetch, backoff, máximo de intentos,
retención y capacidad. Debe evitar retry inmediato infinito, dar margen a consumidores caídos y
cumplir el objetivo nominal de cinco segundos hasta la interfaz para eventos de estado.

DLQ conserva envelope original, consumidor, causa/código, intentos, primer/último fallo y ruta
original. Un operador autorizado inspecciona y redirige con el mismo eventId/payload; no altera
identidad para saltar dedupe. Un payload corregido requiere evento nuevo relacionado explícitamente
o cierre operativo de la incidencia. No se elimina un trabajo pendiente por TTL sin recuperación.

El redrive no es una API pública del negocio. El producto elegido debe proporcionar comando o
herramienta documentada para inspección, redrive y cierre con auditoría, más prueba de reinicio.

## ACL, datos y observabilidad

Credenciales por productor/consumidor; publicación restringida a sus tipos y consumo a sus bindings.
Broker sin acceso público, volúmenes y secretos fuera de Git. No incluir email, password, sesión,
providerToken, claves de ingesta, presigned URLs o token de recuperación en los mensajes.
Las referencias opacas se resuelven en un servicio autorizado, con permisos acotados.

Medir publicación pendiente, confirmación, lag/edad por suscripción, reintentos, DLQ y redrive.
Logs usan eventId/correlationId/consumer y resultado, sin imprimir payloads privados. Health del
broker no demuestra que VOD haya publicado o que push haya llegado al teléfono.

## Compatibilidad y pruebas

Un campo aditivo no puede introducir un secreto ni cambiar una regla de autorización. Un cambio
incompatible crea tipo/schemaVersion y transición de productores/consumidores; no se retira el
anterior mientras queden mensajes pendientes que necesiten leerlo.

Ejemplos válidos/negativos se comprueban con `contracts/check_p2.py`. Las pruebas funcionales
incluyen commit sin publicar, confirmación perdida, proceso muerto antes/después de ACK, versiones
viejas, consumidor detenido, corrupción de payload, reinicio de broker y redrive. No basta un
publish/subscribe con texto arbitrario para acreditar estos contratos.

## Mapeo de transporte vigente

[ADR-016](adr/ADR-016-rabbitmq-eventos-p2.md) selecciona RabbitMQ/AMQP. El exchange p2.events
usa la routing key del tipo; ciclo añade .initial/.update según initialLive y entregas por medio
añaden .inbox/.email/.push. El eventType del envelope no cambia. Cada suscripción tiene cola
propia; p2.redelivery devuelve solo a la suscripción original. Los filtros no inspeccionan JSON.

La integración inicial conecta ciclo a Core/Chat y snapshots a Discovery. Cuenta/suspensión,
VOD y Notificaciones requieren sus payloads y operaciones de dominio antes de activar productores.
Los contratos HTTP/WS/media P2 restantes aún no están materializados en la fuente ejecutable.
