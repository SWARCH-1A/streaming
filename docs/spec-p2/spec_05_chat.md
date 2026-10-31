# SPEC-05 — Chat, moderación y replay

**Prioridad:** P2. **Responsable:** Chat. **Estado:** chat live, Upgrade Bearer nativo, moderación y replay implementados; aceptación integrada pendiente.
**RF:** RF-031…037, RF-082…083. **RNF:** RNF-013, 018…019, 022…032, 041…050.

## 1. Contexto y problema

Los espectadores conversan durante una emisión y consultan esos mensajes al reproducir su VOD.
El propietario modera mensajes y participantes desde su canal. [Alcance](../alcance_p2.md).

## 2. Definición del componente

Chat conserva su servicio Go, API y WebSocket. Posee mensajes, secuencias, deduplicación,
cuota, bloqueos, supresiones e historial durable. Redis guarda el vivo y PostgreSQL propio
(`chat-pg`) la copia durable para replay y moderación ([ADR-024](../adr/ADR-024-chat-redis-postgresql-replay-moderacion.md)).

## 3. Historia de usuario

Como espectador, quiero consultar el chat correspondiente al momento del video. Como streamer,
quiero retirar mensajes y bloquear a un participante temporalmente o hasta retirar el bloqueo.

## 4. Alcance

Incluye sala por sesión, historial live, envío autenticado, replay por ventana temporal/cursor,
borrado individual y por usuario, bloqueos temporales/permanentes y retiro manual. Conserva
lectura pública de contenido disponible, texto seguro, cuota y secuencia de la línea base.

## 5. Requisitos funcionales

- RF-031…035 mantienen sala por sesión, lectura, mensajes autenticados y errores visibles.
- RF-036 permite eliminar mensajes; RF-083 permite retirar mensajes de un usuario mediante
  una acción explícita, con alcance visible y autorizado.
- RF-037 aplica el bloqueo al canal y admite vencimiento o permanencia hasta retiro manual.
- RF-082 ofrece replay sincronizado con VOD y conserva el historial mientras exista ese VOD.
- Bloquear impide mensajes nuevos. El borrado previo es opcional y separado.
- Una supresión rige tanto para consultas live como para replay.

## 6. Criterios de aceptación

- [ ] CA-01: un envío autorizado se persiste antes del ACK; reintentar conserva ID/secuencia
  sin consumir otra cuota. Un envío no autorizado no produce mensaje.
- [ ] CA-02: después de bloquear, el usuario no publica en ninguna sesión del canal afectado;
  otro canal conserva su propia política. Retirar el bloqueo restablece la posibilidad de envío.
- [ ] CA-03: un bloqueo temporal vence según tiempo de servidor y conserva el vencimiento al
  reiniciar; uno permanente continúa hasta retiro manual.
- [ ] CA-04: eliminar un mensaje o ejecutar el borrado por usuario retira las coincidencias del
  chat y del replay. Un bloqueo sin borrado conserva los mensajes anteriores.
- [ ] CA-05: buscar otra posición del VOD consulta la ventana correcta; secuencias y cursores
  evitan duplicados. El mapping contempla generaciones y huecos de reconexión.
- [ ] CA-06: terminar la emisión y reiniciar Chat no elimina mensajes necesarios para replay.
  Una limpieza de datos temporales no precede a la confirmación del archivo durable.
- [ ] CA-07: VOD suspendido, retirado o eliminado no expone replay por su API directa; la
  eliminación genera limpieza idempotente del historial asociado.
- [ ] CA-08: los casos live de SPEC-05 P1 conservan cuota global, límite Unicode, historial,
  orden, permisos y p95 bajo la carga aplicable. Caída de Chat no interrumpe el video.

## 7. Diseño técnico y datos

Identidad del mensaje: `sessionId`, `messageId`, `userId`, `clientMessageId`, `sequence`, autor
público, texto, timestamp UTC, `streamOffsetMs` y generaciones. Las supresiones tienen identidad
y versión para impedir que una consulta o reentrega antigua reponga contenido retirado.

**Almacenes.** El script Redis que confirma el envío (dedupe, cuota, bloqueo, sequence) agrega
el mensaje al stream `chat:archive`. El ACK sale tras ese script y Redis usa AOF con
`appendfsync always`. Un archivador por réplica copia a PostgreSQL de forma idempotente y
confirma la entrada solo después del commit. La sala viva expira 5 minutos tras el fin. El
historial durable dura mientras exista un VOD de la sesión. Una sesión sin VOD se borra a los
7 días de su último mensaje (`CHAT_UNLINKED_RETENTION`).

**Retiro.** Un mensaje retirado conserva su fila y posición sin texto. Historial y frame
`message.removed` muestran el aviso "Mensaje eliminado por el moderador"; el replay lo omite. Cada
retiro incrementa `moderationVersion` de la sesión. Un `message.created` posterior no repone el texto.

**Bloqueo.** El bloqueo identifica canal, cuenta, modalidad (`TEMPORARY` con `PT10M`, `PT1H`,
`PT24H` o `P7D`; `PERMANENT` hasta su retiro), vencimiento y versión. Gana la última escritura.
PostgreSQL registra el estado y Redis guarda una copia que vence en el mismo instante absoluto.
El script de envío consulta esa copia, de modo que la aceptación verifica el estado vigente y no
un timer en memoria. La cuenta bloqueada recibe `CHAT_BLOCKED` con `blockMode` y
`blockedUntilUtc`, y la Web muestra hasta cuándo dura el bloqueo.

**Borrado por usuario.** Retira los mensajes de la cuenta en la emisión indicada, hasta el
sequence observado al aceptar la orden. Responde 202 con la operación terminada y su `statusUri`.

**Mapping temporal.** `streamOffsetMs` es la posición del timeline de la sesión al autorizar el
mensaje. VOD compacta los huecos conocidos y publica el timeline (`P2Timeline`, ADR-022) en
`p2.vod.replay-context`. En cada página Chat lo pide con la credencial del espectador; así VOD
decide si el VOD es legible (publicado, o borrador para su propietario) y no hay copia local que
pueda quedar desfasada. Dentro de un tramo, la posición en el video es `mediaStartMs +
streamOffsetMs - sessionStartMs`. Un mensaje enviado durante un corte va a la unión siguiente y uno
posterior al último tramo, al último milisegundo reproducible. El cero de Chat es el instante
LIVE, después del prefijo PREPARING. Como ese mapping es no decreciente, la ventana [fromMs, toMs)
del video corresponde a un único tramo de la sesión. Chat lee ese tramo en orden de `sequence`
original y omite los retirados. Cada mensaje lleva `mediaOffsetMs`, `streamOffsetMs` y
`streamGeneration`. La página publica `moderationVersion` (retiros de la sesión más uno).

Las ventanas son de hasta 300 000 ms, con 20 mensajes por página por defecto y 50 como máximo. El
cursor se liga a vodId, ventana y `mappingVersion` y avanza por `sequence`, así que un retiro
posterior no desplaza la paginación. Si VOD cambió el mapping responde 410 y hay que pedir la
ventana sin cursor. La página VOD de Web consulta ventanas de 10 s de la posición reproducida.

Los schemas y operaciones `p2.chat.*` están en [contracts/p2.json](../../contracts/p2.json) y
[HTTP/WS](../contratos_p2_http.md). Las mutaciones exigen propietario confirmado por Core
(`p2.chat.moderation-context`), Origin permitido, doble envío CSRF e `Idempotency-Key`.

El Upgrade nativo acepta Authorization Bearer sin Cookie ni Origin ficticio y verifica el contexto
Core antes de 101. Cada envío vuelve a autorizar con la misma credencial. Un origen de navegador
fuera de la lista sigue devolviendo 403; Cookie más Bearer devuelve 401. Un cliente nativo anónimo
puede leer, sin permiso de envío. `native_test.go` verifica handshake, rechazo y revocación posterior.

## 8. Dependencias y contratos de integración

| Dependencia | Contrato | Fallo |
| --- | --- | --- |
| Core | Sesión, suspensión, autor y propiedad del canal (`message-context`, `moderation-context`); cookie web o credencial móvil | Rechazar nuevas operaciones protegidas si no se puede autorizar |
| Streaming | Estado/timeline actual por HTTP; ciclo de sesión por broker | Eventos no conceden permisos; fin cierra escritura |
| VOD | `p2.vod.replay-context` (autorización y `P2Timeline` por página); `vod.availability.changed.v1` por broker (`chat.vod`): vínculo VOD–sesión, versión y eliminación (ADR-022) | Sin respuesta de VOD el replay devuelve 503 `REPLAY_UNAVAILABLE` y el video sigue; VOD no legible es 404; `DELETED` borra el historial sin otro VOD (`replayCleanup=DELEGATED_TO_CHAT`) |
| Gateway / clientes | WS, historial, moderación y consulta temporal | Error acotado y visible sin cortar video |

## 9. Decisiones y preguntas abiertas

[P2-10 y P2-11](../decisiones_pendientes_p2.md) quedan resueltas en
[ADR-024](../adr/ADR-024-chat-redis-postgresql-replay-moderacion.md), con el mapping de
[ADR-022](../adr/ADR-022-vod-go-postgresql-minio.md). La credencial Bearer móvil para moderar
sigue P2-14; el replay ya acepta Bearer.

## 10. Verificación

| RF/RNF | Evidencia requerida |
| --- | --- |
| RF-031…035; RNF-013/018/019 | Envío/historial/WS reales, reintentos, carga y caída de Chat con HLS continuo |
| RF-036/037/083; RNF-027/028 | Propietario/tercero, bloqueo vigente/vencido, retiro y borrado opcional |
| RF-082; RNF-022/024/050 | Reinicio después de ENDED, seek/reconexión, supresión y limpieza |
| RNF-025…032/041…049 | Credenciales por transporte, datos públicos, schemas y logs sin secretos |

Las pruebas de [services/chat](../../services/chat/README.md) cubren estos comportamientos con
Redis simulado y PostgreSQL real. La aceptación de los CA exige además el stack integrado, la
carga y un VOD real; esta SPEC no registra esos resultados.

## 11. Esfuerzo, riesgos y consecuencias

El archivo durable y el mapping temporal atraviesan Chat, VOD y Media. El ACK de envío conserva
su garantía de durabilidad. Moderación, temporizadores y reentregas deben producir el mismo
resultado tras reiniciar; la falta de archivo impide acreditar replay.
