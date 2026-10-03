# SPEC-05 Chat en vivo y eventos para Replay P1

- **Módulo:** chat
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-031…RF-035 y cubre lectura/envío/distribución vinculados a una sesión. Debe fallar de forma aislada respecto al video y conservar datos para Chat Replay futuro.

## 2. Definición del componente

La moderación avanzada (RF-036 y RF-037) queda fuera de P1; esta iteración no implementa bloqueo ni eliminación de mensajes.

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

- RF-035 el sistema rechaza el envío cuando la sala de chat no está disponible. Devuelve un error estable y comprensible; la indisponibilidad de chat no afecta la reproducción de video.

## 6. Criterios de aceptación

- CA-01 — visitante anónimo recibe historial reciente y mensajes nuevos, pero no puede enviar.

- CA-02 — usuario autenticado envía texto NFC, recortado en extremos y de 1–500 puntos de código Unicode; el sistema rechaza vacío/mayor y más de un mensaje en cualquier ventana móvil de 1000 ms en todas sus salas, sin ráfagas acumuladas y con error estable.

- CA-03 — mensaje aceptado se persiste antes del ACK; llega a participantes conectados en menos de 1 s p95 bajo carga objetivo, conserva sequence creciente por sesión y reintentar el mismo clientMessageId no lo duplica.

- CA-04 — quien entra recibe como máximo últimos 50 mensajes de la sesión, no mensajes de otra sesión; abrir WS antes del backlog y fusionar por sequence no deja hueco ni duplicado visible.

- CA-05 — durante pérdida de fuente menor a 30 s la sala continúa; enviar un mensaje válido cerca del segundo 29 usa el contexto autorizado vigente y se acepta si cumple las demás validaciones; al terminar la emisión la sala se vuelve read-only y rechaza envíos nuevos.

- CA-06 — fallo de Chat no detiene un playback ya disponible; UI muestra chat no disponible y deshabilita composer.

- CA-07 — un evento persistido contiene campos para autor, sessionId y posición temporal; mensajes no incluyen HTML ejecutable ni secretos.
- CA-08 — contexto Core valida sesión y autor/estado/timeline localmente. Sin personalización usa handle/avatar nulo; Core inaccesible CORE_UNAVAILABLE, sesión inválida AUTH_REQUIRED, envío nuevo en ENDED CHAT_READ_ONLY, timeline inválido TIMELINE_UNAVAILABLE. Son frames tras Upgrade; rechazado no persiste. No timeout de Profile remoto.

## 7. Diseño técnico y datos

Chat permanece servicio independiente por conexiones largas, fan-out, historial y aislamiento de HLS. Posee mensajes, dedupe, secuencia por sesión, cuota global por cuenta y broadcast; no credenciales/usuarios maestros. Go/MongoDB son candidatos, no selección acreditada ni implementación.

Por mensaje nuevo: validar texto, solicitar una sola vez contexto Core autenticado (principal vigente + autor público + estado/timeline), aplicar dedupe/cuota y persistir antes del ACK. El contexto no se cachea para otros envíos. Core no procesa mensajes ni decide secuencias; Chat no recorre Identity/Profile/Streaming por red. Revocación/fin posteriores a autorización no revierten operación en vuelo, dentro del presupuesto máximo de 500 ms; nuevas autorizaciones se rechazan.

Conservar WS antes de historial y fusionar por sequence; buffer cincuenta separado del historial persistente. Índices únicos de dedupe y sequence, consulta por sessionId/offset; asignación/commit/cuota consistentes entre réplicas. Persistencia→broadcast requiere mecanismo durable para recovery tras crash, no simple envío después de guardar sin recuperador.

El contexto autenticado expone writeAllowed/denialCode: dedupe puede recuperar ACK de un mensaje
previo aun con sala ENDED, sin consumir cuota ni escribir. Nuevo envío exige writeAllowed. Reusar
clientMessageId con otro texto canónico da MESSAGE_ID_CONFLICT.

Estado de sala recibe notificaciones Core de sesión deduplicadas y se reconcilia por snapshot; eventos no autorizan writes. Nuevos envíos siempre consultan autoridad. Un evento ENDED atrasado no abre una sesión vieja. Lectura de sala conocida/ENDED puede seguir sin Core; sala desconocida requiere snapshot. Origin validado también para anónimos; no es identidad.

Retención/supresión de moderación y consultas replay tendrán el mismo dueño Chat. P1 no implementa esos RF futuros ni un servicio Replay aparte.

## 8. Dependencias y contratos de integración

Única dependencia de negocio de Chat: Core. message-context autorizado incluye usuario/autor/timeline; GET snapshot abre/reconcilia sala; session-events recibe cambios durables. Core caído bloquea nuevas escrituras con frame CORE_UNAVAILABLE, no lectura de historia ya conocida. Proxy permite Upgrade/Origin/cookie, Media no depende de Chat.

## 9. Decisiones y preguntas abiertas

**Decisiones:** login para escribir; anónimo para leer; 50 mensajes recientes; texto NFC recortado de hasta 500 puntos de código Unicode; 1 mensaje por cuenta en cualquier ventana móvil global de 1000 ms, sin ráfaga; 20 msg/s agregado objetivo; deduplicación por clientMessageId mientras se retenga el mensaje; persistencia temporal para replay futuro; chat read-only al terminar la emisión.

**Abierto:** no hay preguntas de producto bloqueantes. La persona responsable decide librería/implementación WS y persistencia en ADR sin alterar los contratos visibles.

## 10. Verificación

Lectura anónima/envío protegido, Unicode 500/501, cuota global 1000 ms entre salas/réplicas, dedupe y ACK durable, recuperación broadcast tras crash, WS→historial sin huecos; p95 <1 s a 20 msg/s. Logout/fin en autorización posterior, snapshot antes de fin con operación en vuelo acotada, CORE_UNAVAILABLE sin persistencia; perfiles por defecto desde contexto sin dependencia remota. HLS continúa al caer Chat.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M/L. **Riesgos:** pérdida/reordenamiento de mensajes, sobredimensionar almacenamiento del replay, incompatibilidad de reloj del player y chat, y caída del chat acoplada al stream. **Consecuencia:** no implementar capacidades explícitamente fuera de P1.
