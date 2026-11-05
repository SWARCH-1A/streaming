# SPEC-05 Chat en vivo P1

> Perfil de la primera entrega. Para P2 rigen el [alcance vigente](../alcance_p2.md),
> las [SPEC P2](../spec-p2/README.md) y [ADR-015](../adr/ADR-015-arquitectura-p2.md).
> Este documento conserva contratos y aceptación de P1; no acredita las ampliaciones de P2.


- **Módulo:** chat
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

El chat permite conversar durante una emisión y cubre RF-031…RF-035. Sus mensajes se vinculan a
una sesión concreta. Si el servicio falla, el video debe seguir funcionando.

Acordamos conservar el chat mientras dura la sesión y eliminarlo poco después del fin,
según [ADR-010](../adr/ADR-010-chat-go-redis-efimero.md). Durante su retención, los mensajes
confirmados deben sobrevivir a los reinicios.

## 2. Definición del componente

Chat tiene su propio servicio Go y su almacenamiento Redis. Expone historial por REST y mensajes
en tiempo real por WebSocket. Se encarga de las salas, el orden, la deduplicación, la cuota por
cuenta y la distribución de mensajes.

La moderación avanzada, RF-036 y RF-037, queda fuera de P1. Esta iteración no permite bloquear
usuarios ni eliminar mensajes.

## 3. Historia de usuario

Como espectador, quiero leer mensajes de la sesión en vivo; como usuario autenticado, quiero publicar mensajes y verlos distribuirse rápidamente.

## 4. Alcance

### Dentro de P1

- Sala por stream session; lectura pública a visitantes; escritura autenticada.

- Últimos 50 mensajes disponibles al entrar; texto NFC, recortado en extremos y de máximo 500 puntos de código Unicode; máximo uno por ventana móvil de 1000 ms por cuenta en todas las salas combinadas, sin ráfaga acumulada.

- Mantener sala activa durante la ventana de reconexión de 30 s; después del fin queda solo lectura durante 5 minutos y luego se elimina.

- Guardar cada mensaje mientras dure la sala con identificador de mensaje, sessionId, autor visible snapshot, texto seguro, timestamp de servidor, secuencia estrictamente creciente por sesión y offset respecto a la línea temporal de emisión.

- Deduplicar un reintento por `(sessionId, userId, clientMessageId)`; el mensaje aceptado se guarda de forma durable antes del ACK y se distribuye sin crear un segundo mensaje.

- Abrir WebSocket antes de solicitar backlog; el cliente combina backlog y eventos live por sequence para no perder mensajes en la transición.

- Objetivo de carga: 20 mensajes por segundo agregados, 10 minutos.

### Fuera de P1

- Borrar mensajes/bloquear usuarios y moderación avanzada; VOD playback/replay UI y almacenamiento para Chat Replay; mensajes multimedia y modos follower/subscriber-only.

### Supuestos acordados

- La cuenta es necesaria para publicar pero no para leer.

- P1 solo necesita texto y Unicode; renderizar como texto, no ejecutar HTML.

- El chat no se conserva después de la retención de 5 minutos posterior al fin. Chat Replay futuro deberá definir su persistencia en un ADR del dueño Chat.

## 5. Requisitos funcionales

- RF-031 sala por sesión en vivo.

- RF-032 envío autenticado.

- RF-033 el sistema distribuye cada mensaje aceptado a los participantes conectados.

- RF-034 el sistema muestra el autor y contenido del mensaje.

- RF-035 el sistema rechaza el envío cuando la sala de chat no está disponible. Devuelve un error estable y comprensible; la indisponibilidad de chat no afecta la reproducción de video.

## 6. Criterios de aceptación

- CA-01 — visitante anónimo recibe historial reciente y mensajes nuevos, pero no puede enviar.

- CA-02 — usuario autenticado envía texto NFC, recortado en extremos y de 1–500 puntos de código Unicode; el sistema rechaza vacío/mayor y más de un mensaje en cualquier ventana móvil de 1000 ms en todas sus salas, sin ráfagas acumuladas y con error estable.

- CA-03 — mensaje aceptado se guarda de forma durable antes del ACK; llega a participantes conectados en menos de 1 s p95 bajo carga objetivo, conserva sequence creciente por sesión y reintentar el mismo clientMessageId no lo duplica.

- CA-04 — quien entra recibe como máximo últimos 50 mensajes de la sesión, no mensajes de otra sesión; abrir WS antes del backlog y fusionar por sequence no deja hueco ni duplicado visible.

- CA-05 — durante pérdida de fuente menor a 30 s la sala continúa; enviar un mensaje válido cerca del segundo 29 usa el contexto autorizado vigente y se acepta si cumple las demás validaciones; al terminar la emisión la sala se vuelve read-only, rechaza envíos nuevos y se elimina 5 minutos después.

- CA-06 — fallo de Chat no detiene un playback ya disponible; UI muestra chat no disponible y deshabilita composer.

- CA-07 — un mensaje guardado contiene campos para autor, sessionId y posición temporal; mensajes no incluyen HTML ejecutable ni secretos.
- CA-08 — contexto Core valida sesión/autor localmente y obtiene estado/timeline actual desde Streaming; jamás autoriza desde Discovery. Sin personalización usa handle/avatar nulo; Core inaccesible CORE_UNAVAILABLE, Streaming inaccesible STREAMING_UNAVAILABLE, sesión inválida AUTH_REQUIRED, envío nuevo en ENDED CHAT_READ_ONLY, timeline inválido TIMELINE_UNAVAILABLE. Son frames tras Upgrade; rechazado no persiste. No timeout de Profile remoto.

## 7. Diseño técnico y datos

Chat permanece servicio independiente por conexiones largas, fan-out e aislamiento de HLS. Posee mensajes, dedupe, secuencia por sesión, cuota global por cuenta y broadcast; no credenciales/usuarios maestros. Implementación: Go y Redis según [ADR-010](../adr/ADR-010-chat-go-redis-efimero.md).

Para aceptar un mensaje nuevo, Chat valida el texto y solicita una vez el contexto autenticado Core:
principal vigente, autor público y estado y línea temporal de la emisión. Después aplica la
deduplicación y la cuota, y guarda el mensaje antes de enviar el ACK. Ese contexto no se reutiliza
para otros mensajes.

Core consulta Streaming dentro de un presupuesto total de 400 ms, con hasta 200 ms para ese salto.
Chat sigue siendo responsable de los mensajes y sus secuencias. Si la sesión se revoca o la emisión
termina después de autorizar una operación, esta puede continuar dentro del presupuesto máximo
de 500 ms. Las nuevas autorizaciones se rechazan.

El cliente abre WebSocket antes de consultar el historial y combina ambas fuentes por sequence.
En Redis, un script atómico resuelve la deduplicación, la cuota, la secuencia y el mensaje.
Todas las réplicas usan el mismo Redis para mantener consistentes esas escrituras.

El Redis Stream de la sala también funciona como outbox: otra réplica puede entregar un mensaje
confirmado si el proceso que lo aceptó cae antes de difundirlo. El Stream se conserva completo
mientras existe la sala, por lo que los mensajes con ACK siguen disponibles hasta su expiración.
AOF con fsync por escritura conserva esos mensajes ante un reinicio.

El contexto autenticado expone writeAllowed/denialCode: dedupe puede recuperar ACK de un mensaje
previo aun con sala ENDED, sin consumir cuota ni escribir. Nuevo envío exige writeAllowed. Reusar
clientMessageId con otro texto canónico da MESSAGE_ID_CONFLICT.

Estado de sala recibe notificaciones Streaming de sesión deduplicadas y se reconcilia por snapshot; eventos no autorizan writes. Nuevos envíos siempre consultan autoridad. Un evento ENDED atrasado no abre una sesión vieja. Lectura de sala conocida/ENDED puede seguir sin Core; sala desconocida requiere snapshot. Origin validado también para anónimos; no es identidad.

Retención: al conocer ENDED, todas las claves de la sala expiran a los 5 minutos; después el historial queda vacío. Un ENDED no recibido se descubre con un barrido periódico por snapshot de las salas con mensajes sin retención iniciada. El TTL de caché de estado no elimina mensajes, secuencia ni dedupe activos;
reentregar ENDED no extiende la retención. Moderación y consultas replay futuras tendrán el mismo dueño Chat. P1 no implementa esos RF futuros ni un servicio Replay aparte.

## 8. Dependencias y contratos de integración

Core compone message-context autorizado con usuario/autor locales y timeline Streaming; GET snapshot Core delega estado autoritativo a Streaming. Streaming publica session-events durables a Chat. Core caído bloquea nuevas escrituras con CORE_UNAVAILABLE, Streaming caído con STREAMING_UNAVAILABLE; historia ya conocida conserva lectura. Proxy permite Upgrade/Origin/cookie, Media no depende de Chat.

## 9. Decisiones y preguntas abiertas

**Decisiones:** login para escribir; anónimo para leer; 50 mensajes recientes; texto NFC recortado de hasta 500 puntos de código Unicode; 1 mensaje por cuenta en cualquier ventana móvil global de 1000 ms, sin ráfaga; 20 msg/s agregado objetivo; deduplicación por clientMessageId mientras se retenga la sala; chat read-only al terminar la emisión y eliminado 5 minutos después; sin pérdida de mensajes con ACK durante la sesión.

**Abierto:** no hay preguntas de producto bloqueantes. Chat Replay futuro define su persistencia en un ADR nuevo.

## 10. Verificación

- Probar lectura anónima y envío autenticado, texto de 500 y 501 puntos de código, y cuota global de 1000 ms entre salas y réplicas.
- Comprobar deduplicación, persistencia antes del ACK, recuperación de la distribución tras una caída y combinación de WebSocket e historial sin huecos.
- Medir p95 <1 s con 20 mensajes/s agregados.
- Verificar rechazo de nuevas autorizaciones después de logout o fin de emisión, y el límite de una operación autorizada que sigue en curso.
- Comprobar CORE_UNAVAILABLE y STREAMING_UNAVAILABLE sin persistir mensajes nuevos, y perfiles por defecto desde el contexto, sin consulta remota a Profile.
- Verificar la eliminación de la sala 5 minutos después de ENDED y que HLS continúa cuando Chat cae.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M/L. Los riesgos principales son la pérdida o el desorden de mensajes, la latencia
de fsync en Redis, las diferencias entre el reloj del reproductor y el del chat, y un fallo Chat
que termine afectando al video.

P1 conserva solo el historial dentro de la retención acordada. La moderación y Chat Replay
necesitan sus decisiones e implementación en fases futuras.
