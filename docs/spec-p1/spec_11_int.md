# SPEC-11 Flujos de sesión y eventos entre dominios P1

- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Especifica el orden y significado de los flujos distribuidos que hacen posible registro con canal, sesión LIVE, chat, estado de canal, conteo y búsqueda. Es hijo de SPEC-09; no replica reglas internas de cada módulo.

## 2. Estado del sistema y brecha

Los dueños de datos están identificados, pero no existe protocolo de eventos, estrategia de sincronización, reglas de reintento ni secuencias verificables. Registro y cambios de emisión cruzan límites de módulo.

## 3. Historia de usuario

Como espectador o streamer, quiero que vista de canal, reproductor, chat y búsqueda representen la misma sesión aun ante retrasos o reconexiones.

## 4. Alcance

### Dentro de P1

- Definir flujos proveedor→consumidor, evento/consulta, idempotencia, orden, reintentos, deduplicación y reconciliación.

- Registro Identity y provisión de un canal; perfil público al consultar canal y snapshot del autor al crear mensaje.

- Validación de categoría/tags; inicio LIVE; asociación de sessionId; sala Chat; proyección en Channel/Discovery; viewer heartbeats.

- Creación de una configuración `streamId` por canal, entrega/rotación de stream key y creación de una sesión nueva por cada emisión RTMP aceptada.

- Desconexión de origen, grace de 30 s, recuperación a la misma sesión, finalización, canal OFFLINE, chat read-only y próxima sesión con ID nuevo.

- Fallos parciales: Chat o Discovery no detienen video; proyección degradada no inventa estado; recuperar eventos no duplica efectos.

### Fuera de P1

- VOD, Chat Replay UI/export, moderación avanzada, notificaciones, followers, pagos y garantía exactly-once.

### Supuestos acordados

- Estados internos PREPARING, LIVE, RECONNECT_GRACE, ENDED; availability pública PLAYABLE, RECONNECTING, OFFLINE. En grace se conserva sessionId por 30 s, chat lectura/escritura, canal LIVE · reconectando y Discovery excluye el stream de resultados PLAYABLE.

- Entrega al menos una vez es aceptable si cada consumidor procesa idempotentemente; la tecnología se elige por ADR.

## 5. Requisitos de interacción

- Identity publica userId/handle canónico y resultado de registro PENDING/ACTIVE/EXPIRED. ACTIVE solo después de exactamente un canal. Registro PENDING no expone cuenta ni inicia sesión; la misma Idempotency-Key permite consultar/reintentar por 24 h desde el `pendingSinceUtc` persistido al crearlo. Luego EXPIRED libera email/handle, GET y POST con la clave anterior responden 410 y un registro nuevo requiere otra clave. ACTIVE tampoco inicia sesión: el usuario hace login separado. Channels provisiona de forma idempotente por ownerUserId.

- Streaming publica sessionId, streamId, channelId, status interno, availability, metadata, hora servidor, eventId/version, graceDeadlineAtUtc (informativo) y timeline sample (sampledAtUtc/timelinePositionMs). Solo el dueño vigente arbitra el límite con reloj monotónico y fencing.

- Cada sesión nueva incrementa `streamGeneration` monotónica por `streamId`; reconexión durante grace conserva generación y sessionId. Las proyecciones comparan primero streamGeneration y después sessionVersion solo dentro del sessionId vigente.

- Media adapter autentica la clave RTMP y publica señales de fuente conectada, medio reproducible y fuente perdida; Streaming deduplica por eventId y descarta sourceGeneration vieja.

- Media adapter envía un `ingestAttemptId` estable durante retries de autorización; Streaming devuelve el mismo resultado para un retry y no reserva un segundo slot.

- Chat acepta read/write anónimo/autenticado respectivamente en LIVE y RECONNECT_GRACE; ENDED permite solo lectura. Rate limit es máximo un mensaje por ventana móvil de 1000 ms por cuenta en todas las salas, sin ráfaga.

- Channels y Discovery proyectan título/categoría/tags/estado/count desde Streaming y nombre/avatar desde Profile sin adueñarse del dato. Discovery publica el canal solo tras evento ACTIVE de Identity; `ChannelProvisioned` confirma la saga privada y no se propaga al índice público.

- Viewer count cuenta un lease por instancia de player después del primer frame; heartbeat 10 s, vence a 30 s sin heartbeat válido y se invalida en ENDED. No equivale a usuarios de chat ni acepta conteo del cliente.

- Profile update refresca consulta pública/proyección; mensaje Chat conserva authorDisplayName snapshot al enviarse.

## 6. Criterios de aceptación

- **CA-01:** diagrama de secuencia y tabla cubren registro, creación de StreamConfig/streamId, inicio RTMP, edición de metadata, señales del media adapter, sesión, reconexión, fin y lectura/envío de chat.

- **CA-02:** un `ChannelProvisioned`/request idempotente duplicado no duplica canal; el evento interno confirma provisión solo a Identity y nunca publica el canal. Discovery crea un documento de canal offline únicamente tras `IdentityPublicChanged` ACTIVE, consultando entonces identidad, Channel y Profile; los eventos de canal recibidos antes se reconcilian sin hacer visible el recurso. Evento de sesión duplicado no altera count ni abre sala múltiple; evento viejo de una streamGeneration menor no reactiva la sesión anterior. Todo `StreamSessionEnded` lleva sessionId, streamGeneration y sessionVersion final; consumidores comparan streamGeneration antes de sessionVersion. Un canal activo se mantiene en Discovery aunque esté OFFLINE.

- **CA-03:** pérdida menor a 30 s conserva sessionId, disponibilidad RECONNECTING y sala read/write; una reconexión solo se acepta si MediaPlaybackReady se valida con tiempo monotónico transcurrido `<30 s`. En elapsed `>=30 s` gana la transición atómica bajo el dueño/fencing vigente, publica ENDED/OFFLINE con streamGeneration y sessionVersion final, ignora callback tardío y cierra escritura; un traspaso no reinicia ni extiende el límite y, si no se conoce el restante, termina la sesión. El próximo intento genera sessionId/streamGeneration nuevos. Los criterios exigen un caso a 29.999 s y uno a 30.000 s con una sola transición.

- **CA-04:** al finalizar, estado público cambia a OFFLINE en no más de 5 s; una sala ENDED rechaza envíos y conserva lectura/historial según RF-035.

- **CA-05:** perfil/título/categoría/tags aparecen tras confirmación del cambio; un valor inválido no sustituye al último válido.

- **CA-06:** reinicio de consumidor y reentrega reconstruyen la proyección sin duplicar; fallos Chat/Discovery no interrumpen HLS.

- **CA-07:** lease por player se crea después del primer frame, heartbeat cada 10 s, expira a los 30 s sin heartbeat válido, cierre explícito lo retira y no se confunde con participantes del chat.

- **CA-08:** tras `pendingUntilUtc`, Channels cerca las creaciones por registrationId y el lookup de esa clave termina PROVISIONED o ABSENT sin operación en vuelo capaz de crear después. Si la respuesta de provisión se perdió y el estado es PROVISIONED, Identity compensa por registrationId hasta 204; ABSENT cierra la reparación. EXPIRED nunca emite `IdentityPublicChanged`; duplicados/reordenamiento no dejan canal ni documento público huérfano.
- **CA-09:** MediaSourceConnected, MediaPlaybackReady y MediaSourceLost usan las rutas HTTPS internas fijadas, eventId estable/reutilizado, envelope y ACK durable documentados. Timeout por intento 2 s; timeout/408/429/5xx reintenta tras 100/250/500/1000/2000 ms y luego cada 2 s por un máximo de 15 min. 2xx confirma; 410 SESSION_ENDED cierra como obsoleto; 4xx permanente pasa a dead-letter y alerta. Sin respuesta terminal en 30 s se alerta una vez por sesión/sourceGeneration; al alcanzar 15 min se detienen los reintentos automáticos y se conserva el evento durable para redrive manual con el mismo ID/payload tras recuperar el receptor. No hay TTL automático. La validación usa sessionId, streamGeneration y sourceGeneration; una pérdida de ACK tras guardar no duplica la transición.
- **CA-10:** Streaming emite `ViewerCountChanged` al pasar a PLAYABLE, por cambios efectivos (coalescidos a 1/s) y heartbeat cada 5 s. Discovery recibe/aplica countVersion creciente, refleja una modificación ≤5 s normal y marca conteo obsoleto separado de estado de playback; conteos atrasados no resucitan ENDED.
- **CA-11:** `sequence` se compara solo dentro del mismo `aggregateId`: IDs canónicos son `identity:{userId}`, `profile:{userId}`, `channel:{channelId}`, `stream:{streamId}`, `session:{sessionId}`, `viewer-count:{sessionId}` y `chat-session:{sessionId}`. Un consumidor nunca filtra ViewerCountChanged comparando su secuencia con la de eventos de sesión; aplica primero sesión/generación vigentes y luego countVersion.

## 7. Diseño técnico y datos

- Evento candidato contiene eventId, eventType, schemaVersion, aggregateId, sequence/version, occurredAt UTC, producer y payload mínimo.

- Flujo recomendado: confirmación durable del dueño → publicar outbox o equivalente → deduplicar por eventId → ack y métricas; mecanismo se decide por ADR.

- Transiciones internas PREPARING → LIVE → RECONNECT_GRACE → ENDED y disponibilidad pública PLAYABLE/RECONNECTING/OFFLINE. SessionStarted se emite solo tras HLS reproducible; Channel puede mostrar LIVE · reconectando en grace, Discovery lista solo PLAYABLE.

- Una configuración streamId por canal sobrevive a sesiones ENDED; cada inicio posterior recibe sessionId distinto. Eventos repetidos no duplican transición y callbacks de sourceGeneration antigua no alteran sesión vigente.

- Playback tracking usa leaseId/sessionId/token opaco emitidos por servidor, heartbeatAt y expiración; no exige cuenta ni acepta leaseId, token o viewerCount elegidos por cliente.

- Definir reconciliación por agregado; no asumir transacción distribuida entre almacenes.

## 8. Dependencias y contratos de integración

- Contratos y ejemplos de request/response/evento están en el inventario autocontenido `contratos_modelo_datos.md`; las rutas/proxy están centralizadas en `integracion_frontend_reverse_proxy.md`.

- Identity→Channels: identidad/canal inicial mediante endpoint HTTPS/TLS privado autenticado; Identity→servicios protegidos: introspección HTTPS/TLS privada por operación; Identity→Discovery: proyección userId/handle. Taxonomy→Streaming: validez de IDs; Streaming→Chat/Channels/Discovery: sesión/metadata; Profile→Channels/Discovery y snapshot Chat.

- Servicios con cookie de sesión consultan Identity por el contrato privado de introspección antes de cada operación protegida; Channels provisiona solo por endpoint interno autenticado, no por proxy público. Identity publica userId→handle para poblar/reconstruir Discovery.

- Streaming/media adapter determina cuándo HLS es reproducible; callbacks/polling se mantienen detrás del dueño Streaming.
- Streaming confirma callbacks solo tras aceptación durable; bus/outbox es decisión técnica de ADR, pero el contrato de ruta, ACK, idempotencia, retry y freshness es normativo.

- Observabilidad de cada salto usa correlation ID; errores se mapean según SPEC-10.

## 9. Decisiones y preguntas abiertas

**Acordado:** identidad/canal uno a uno, streamId distinto de sessionId, grace 30 s, chat replay-ready events sin UI Replay P1, viewer timeout 30 s, fallos aislados. **No bloqueante:** bus/outbox/webhook/polling, orden exacto y estrategia de replay de eventos se seleccionan con ADR; validar secuencias con proveedores/consumidores.

## 10. Verificación

- Pruebas de contrato y secuencia: caminos nominales, retry, duplicado, atraso/reorden y caída de consumidor.

- Simular pérdida RTMP y recuperación a 29 s y cierre tras superar 30 s.

- Comprobar consistencia Channel/Discovery/Chat y deduplicación heartbeat.

- Inyectar caída Chat/Discovery y verificar HLS activo continúa.

- Probar correlación/reconciliación luego de reiniciar consumidor.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** L. **Riesgos:** estado distribuido divergente, loops de eventos, duplicación, relojes/offset incompatibles y saga de cuenta/canal incompleta. **Consecuencia:** no se promete exactly-once, VOD ni replay; entrega al menos una vez exige deduplicación.
