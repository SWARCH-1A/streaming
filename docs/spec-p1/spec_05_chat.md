# SPEC-05 Chat en vivo y eventos para Replay P1

- **Módulo:** chat
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-031…RF-035 y cubre lectura/envío/distribución vinculados a una sesión. Debe fallar de forma aislada respecto al video y conservar datos para Chat Replay futuro.

## 2. Estado del sistema y brecha

La moderación avanzada SRC-RF-35 y SRC-RF-36 queda fuera de P1; esta iteración no implementa bloqueo ni eliminación de mensajes.

## 3. Historia de usuario

Como espectador, quiero leer mensajes de la sesión en vivo; como usuario autenticado, quiero publicar mensajes y verlos distribuirse rápidamente.

## 4. Alcance

### Dentro de P1

- Sala por stream session; lectura pública a visitantes; escritura autenticada.

- Últimos 50 mensajes disponibles al entrar; texto NFC, recortado en extremos y de máximo 500 puntos de código Unicode; máximo uno por ventana móvil de 1000 ms por cuenta en todas las salas combinadas, sin ráfaga acumulada.

- Mantener sala activa durante la ventana de reconexión de 30 s; después del fin queda solo lectura.

- Persistir eventos con identificador de mensaje, sessionId, autor visible snapshot, texto seguro, timestamp de servidor, secuencia estrictamente creciente por sesión y offset respecto a la línea temporal de emisión.

- Deduplicar un reintento por `(sessionId, userId, clientMessageId)`; el mensaje aceptado se persiste antes del ACK y se distribuye sin crear un segundo mensaje.

- Abrir WebSocket antes de solicitar backlog; el cliente combina backlog y eventos live por sequence para no perder mensajes en la transición.

- Objetivo de carga: 20 mensajes por segundo agregados, 10 minutos.

### Fuera de P1

- Borrar mensajes/bloquear usuarios y moderación avanzada; VOD playback/replay UI; mensajes multimedia y modos follower/subscriber-only.

### Supuestos acordados

- La cuenta es necesaria para publicar pero no para leer.

- P1 solo necesita texto y Unicode; renderizar como texto, no ejecutar HTML.

- El offset y timestamp permiten Chat Replay futuro; política de retención se acuerda con VOD.

## 5. Requisitos funcionales

- RF-031 sala por sesión en vivo.

- RF-032 envío autenticado.

- RF-033 el sistema distribuye cada mensaje aceptado a los participantes conectados.

- RF-034 el sistema muestra el autor y contenido del mensaje.

- RF-035 el sistema rechaza el envío cuando la sala de chat no está disponible (SRC-RF-34 legado). Devuelve un error estable y comprensible; la indisponibilidad de chat no afecta la reproducción de video.

## 6. Criterios de aceptación

- CA-01 — visitante anónimo recibe historial reciente y mensajes nuevos, pero no puede enviar.

- CA-02 — usuario autenticado envía texto NFC, recortado en extremos y de 1–500 puntos de código Unicode; el sistema rechaza vacío/mayor y más de un mensaje en cualquier ventana móvil de 1000 ms en todas sus salas, sin ráfagas acumuladas y con error estable.

- CA-03 — mensaje aceptado se persiste antes del ACK; llega a participantes conectados en menos de 1 s p95 bajo carga objetivo, conserva sequence creciente por sesión y reintentar el mismo clientMessageId no lo duplica.

- CA-04 — quien entra recibe como máximo últimos 50 mensajes de la sesión, no mensajes de otra sesión; abrir WS antes del backlog y fusionar por sequence no deja hueco ni duplicado visible.

- CA-05 — durante pérdida de fuente menor a 30 s la sala continúa; enviar un mensaje válido cerca del segundo 29 usa una muestra fresca y se acepta si cumple las demás validaciones; al cerrar sesión se vuelve read-only y rechaza writes.

- CA-06 — fallo de Chat no detiene un playback ya disponible; UI muestra chat no disponible y deshabilita composer.

- CA-07 — un evento persistido contiene campos para autor, sessionId y posición temporal; mensajes no incluyen HTML ejecutable ni secretos.
- CA-08 — si Profile falla o expira, el mensaje válido se acepta con el handle de Identity y avatar nulo; si Identity no valida la sesión, el mensaje se rechaza como `IDENTITY_UNAVAILABLE`, y si no hay estado de sesión/timeline válido de Streaming se rechaza como `STREAMING_UNAVAILABLE` o `TIMELINE_UNAVAILABLE`. Todos son frames WebSocket después del Upgrade; ningún fallo escribe o distribuye un mensaje rechazado.

## 7. Diseño técnico y datos

- Propiedad: Chat posee MessageId, sessionId, userId, authorDisplayName snapshot, body, serverCreatedAt, streamOffsetMs y sequence; `ChatMessageCreated` usa `aggregateId=chat-session:{sessionId}` y esa sequence monotónica. El catálogo completo se persiste separado del buffer reciente.

- WebSocket es el transporte P1 único de distribución y envío en `/realtime/chat/sessions/{sessionId}`; REST `GET /api/chat/sessions/{sessionId}/messages?limit=50` sirve backlog. El cliente abre WS, espera `chat.ready`, pide el backlog y combina por sequence; esto evita el hueco entre historial y eventos live. El contrato fija auth, Origin, errores, orden, reconexión e intervalos de heartbeat.

- Separar buffer de 50 mensajes de persistencia de eventos para VOD futuro. No incluir storage NoSQL por inercia; justificar acceso, TTL y consulta temporal.

- Chat acepta la sala y publica `chat.ready` solo desde LIVE; durante RECONNECT_GRACE permite leer/escribir; ENDED permite lectura (`READ_ONLY`) y rechaza writes. PREPARING falla con `409 CHAT_NOT_OPEN`.

- `userId`, displayName/avatar y hora/sequence/offset nunca se aceptan desde payload como autoridad; en cada send Chat valida la cookie por HTTPS/TLS a `POST /internal/identity/sessions/introspect` sobre red privada sin cachear; userId/handle vienen del principal Identity, perfil snapshot de Profile, tiempos/offset/sequence del servidor.

- Si Profile no responde, Chat acepta el mensaje usando el handle canónico del principal como `authorDisplayName` y `avatarUri=null`; no falla el envío ni detiene playback. El snapshot almacenado no cambia cuando se recupere Profile o se edite el perfil.

- Streaming publica muestras al menos cada segundo durante LIVE y toda RECONNECT_GRACE; Chat usa la última muestra recibida en 3 s, la extrapola con reloj monotónico local y guarda su `sessionVersion` como `timelineSampleVersion`. Si falta una muestra fresca, responde al `message.send` con frame WebSocket `error` de code `TIMELINE_UNAVAILABLE`, sin persistir ni distribuir; el cliente reintenta con el mismo `clientMessageId` al recibir una muestra nueva. Nunca acepta offset del cliente. El offset es aproximado, coordenada de sesión futura, no timestamp del cliente ni prueba de que P1 ya graba VOD.

## 8. Dependencias y contratos de integración

- Identity para introspección de sesión y principal por cada envío autenticado; si falla la dependencia durante un mensaje, Chat rechaza con frame WebSocket `error`/`IDENTITY_UNAVAILABLE` y no acepta identidad no validada. Si falla antes del Upgrade, el handshake devuelve HTTP 503.

- Streaming para sessionId, start/end, grace state y offset multimedia; su indisponibilidad durante un envío rechaza con frame `STREAMING_UNAVAILABLE` (la falta de sample fresco usa `TIMELINE_UNAVAILABLE`).

- Profile para displayName/avatar visible; si no responde, se usa el handle de Identity y avatar nulo.

- Frontend player y shell web para websocket upgrade/ruta del proxy.

- VOD futuro consume export/history sin acoplarse a tablas internas.

## 9. Decisiones y preguntas abiertas

**Decisiones:** login para escribir; anónimo para leer; 50 mensajes recientes; texto NFC recortado de hasta 500 puntos de código Unicode; 1 mensaje por cuenta en cualquier ventana móvil global de 1000 ms, sin ráfaga; 20 msg/s agregado objetivo; deduplicación por clientMessageId mientras se retenga el mensaje; persistencia temporal para replay futuro; chat read-only al cerrar sesión.

**Abierto:** no hay preguntas de producto bloqueantes. La persona responsable decide librería/implementación WS y persistencia en ADR sin alterar los contratos visibles.

## 10. Verificación

- lectura anónima y denegación de escritura; normalización, límites 500/501 puntos de código, vacío/espacios y cuota móvil 1000 ms.

- broadcast p95 <1 s con carga objetivo; orden de mensajes y recuperación de conexión.

- backlog último 50 aislado por sessionId; conexión WS antes de backlog; deduplicación/reorden por sequence; campos de Chat Replay y offsets verificables, incluyendo error por muestra stale.

- caída/timeout de Profile mantiene envío con snapshot fallback handle + avatar nulo; caída/timeout de Identity rechaza sin persistir. Durante prueba de 20 mensajes/s informar hasta 20 introspecciones/s y 20 lookups Profile/s, sus p95 y errores.

- fallo de chat deja playback disponible; al término chat queda read-only; XSS/string rendering seguro.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M/L. **Riesgos:** pérdida/reordenamiento de mensajes, sobredimensionar almacenamiento del replay, incompatibilidad de reloj del player y chat, y caída del chat acoplada al stream. **Consecuencia:** no implementar capacidades explícitamente fuera de P1.
