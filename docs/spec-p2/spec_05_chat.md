# SPEC-05 — Chat, moderación y replay

**Prioridad:** P2. **Responsable:** Chat. **Estado:** diseño para desarrollo.
**RF:** RF-031…037, RF-082…083. **RNF:** RNF-013, 018…019, 022…032, 041…050.

## 1. Contexto y problema

Los espectadores conversan durante una emisión y consultan esos mensajes al reproducir su VOD.
El propietario modera mensajes y participantes desde su canal. [Alcance](../alcance_p2.md).

## 2. Definición del componente

Chat conserva su servicio Go, API y WebSocket. Posee mensajes, secuencias, deduplicación,
cuota, bloqueos, supresiones e historial durable. El código P1 ofrece chat live y retención
temporal; el archivo para replay y la moderación requieren implementación.

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

El bloqueo identifica canal, cuenta, modalidad, vencimiento si es temporal y versión. La
aceptación verifica el estado vigente, no solo un timer en memoria. La selección del almacén
durable, el archivo y los límites de duración están pendientes del responsable.

Los schemas y operaciones `p2.chat.*` están en [contracts/p2.json](../../contracts/p2.json) y
[HTTP/WS](../contratos_p2_http.md). Historial y message.created añaden moderationVersion; borrado
e invalidación impiden restaurar contenido desde respuestas/eventos viejos. La selección del
borrado por usuario está marcada en revisión P2-11. Replay exige acceso VOD y cursor ligado a
ventana/mapping/moderación. Se consultan trabajos asíncronos por statusUri del dueño.

## 8. Dependencias y contratos de integración

| Dependencia | Contrato | Fallo |
| --- | --- | --- |
| Core | Sesión, suspensión, autor y propiedad del canal; cookie web o credencial móvil | Rechazar nuevas operaciones protegidas si no se puede autorizar |
| Streaming | Estado/timeline actual por HTTP; ciclo de sesión por broker | Eventos no conceden permisos; fin cierra escritura |
| VOD | Vínculo VOD–sesión, disponibilidad, mapping y eliminación | Replay no se sirve sin acceso válido al VOD |
| Gateway / clientes | WS, historial, moderación y consulta temporal | Error acotado y visible sin cortar video |

## 9. Decisiones y preguntas abiertas

Bloqueo por canal, modalidades temporal/permanente, retiro manual, borrado opcional y replay
durable están definidos. [P2-10 y P2-11](../decisiones_pendientes_p2.md) asignan mapping,
persistencia, alcance del borrado por usuario y formato de duración al responsable de Chat.

## 10. Verificación

| RF/RNF | Evidencia requerida |
| --- | --- |
| RF-031…035; RNF-013/018/019 | Envío/historial/WS reales, reintentos, carga y caída de Chat con HLS continuo |
| RF-036/037/083; RNF-027/028 | Propietario/tercero, bloqueo vigente/vencido, retiro y borrado opcional |
| RF-082; RNF-022/024/050 | Reinicio después de ENDED, seek/reconexión, supresión y limpieza |
| RNF-025…032/041…049 | Credenciales por transporte, datos públicos, schemas y logs sin secretos |

Son criterios por ejecutar; esta SPEC no registra resultados.

## 11. Esfuerzo, riesgos y consecuencias

El archivo durable y el mapping temporal atraviesan Chat, VOD y Media. El ACK de envío conserva
su garantía de durabilidad. Moderación, temporizadores y reentregas deben producir el mismo
resultado tras reiniciar; la falta de archivo impide acreditar replay.
