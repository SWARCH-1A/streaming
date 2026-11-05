# SPEC-11 Flujos de sesión y eventos entre dominios P1

> Perfil de la primera entrega. Para P2 rigen el [alcance vigente](../alcance_p2.md),
> las [SPEC P2](../spec-p2/README.md) y [ADR-015](../adr/ADR-015-arquitectura-p2.md).
> Este documento conserva contratos y aceptación de P1; no acredita las ampliaciones de P2.


- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Los recorridos de registro, emisión y chat necesitan respuestas consistentes ante reintentos,
retrasos y fallos. Esta SPEC describe la transacción local de registro y las comunicaciones
Core–Streaming/Chat y Streaming–Media/Chat/Discovery. Las operaciones que comparten un mismo
almacén se confirman localmente.

## 2. Definición del componente

Los datos e interfaces de estos flujos están en contratos_modelo_datos.md. Cada módulo implementa
las operaciones de su dominio; Integración conecta y comprueba sus interacciones.

## 3. Historia de usuario

Como espectador o streamer, quiero que vista de canal, reproductor, chat y búsqueda representen la misma sesión aun ante retrasos o reconexiones.

## 4. Alcance

### Dentro de P1

Registro transaccional e idempotente; configuración, sesión, cupos, metadatos y leases en Rust;
autorización y callbacks Media; contexto autorizado Chat; notificaciones durables y snapshots
para el estado de sala; y proyección pública Streaming→Discovery con reconstrucción.

En cada frontera comprobamos fallos, timeouts, deduplicación, generaciones y recuperación.

### Fuera de P1

Una saga cuenta–canal, replicación entre módulos Core, VOD o replay funcional, notificaciones,
pagos y garantía de entrega exactamente una vez.

### Supuestos

Streaming controla el reloj y el estado de la emisión; Chat controla sus mensajes.
Los eventos se entregan al menos una vez mediante outbox e inbox durables.

## 5. Requisitos de interacción

Core confirma cuenta, perfil y canal juntos, sin agregar un estado público PENDING.
Streaming solicita un contexto nuevo Core para validar identidad, propietario y catálogo en cada
comando protegido; no hay una transacción entre bases.

Media aporta la evidencia HLS. sourceGeneration y eventId permiten identificar fuentes o callbacks
antiguos. Chat solicita un contexto por mensaje nuevo; los eventos de sesión solo informan el estado
de la sala. Un lease representa una reproducción, no un participante de chat.

El contexto tiene un presupuesto de tiempo acotado. Una operación ya autorizada puede terminar
dentro de ese presupuesto, aunque una revocación posterior rechace los nuevos intentos.

## 6. Criterios de aceptación

- CA-01: los diagramas describen el registro local, la configuración, RTMP/HLS, la gracia y el fin de sesión, Chat y los leases.
- CA-02: repetir el registro después del commit conserva la cuenta, el perfil y el canal, sin activación pendiente ni duplicados. Repetir un evento de sesión no duplica la sala ni vuelve a abrir una generación anterior.
- CA-03: una reconexión HLS antes de 30 s conserva la sesión. Con 29.999 s, la recuperación válida continúa; con 30.000 s, la transición serializada confirma ENDED. Un reinicio no extiende la ventana.
- CA-04: el estado público se actualiza en hasta 5 s. Una autorización posterior a ENDED rechaza el envío, mientras el historial conserva lectura.
- CA-05: una edición válida de perfil, metadatos o catálogo se refleja después del commit. Una edición inválida conserva el estado anterior.
- CA-06: recuperación Chat por snapshot/outbox sin duplicados; fallo Chat o endpoint Discovery no corta HLS disponible. Caídas Core/Streaming documentan alcance real de APIs/contextos; atraso Discovery muestra UNKNOWN.
- CA-07: los leases se crean tras el primer frame, se renuevan cada 10 s, expiran a los 30 s sin heartbeat y se cierran inmediatamente cuando corresponde. El cliente no decide el conteo.
- CA-08: un fallo entre escrituras del registro revierte todas. Si se pierde la respuesta después del commit, el reintento recupera los IDs.
- CA-09: los callbacks Media conservan su envelope y el ACK durable. Cada intento tiene timeout de 2 s; se aplica el calendario de reintentos por hasta 15 min, con alerta a los 30 s. La dead-letter y su redrive no tienen TTL. Una respuesta 410 resuelve el callback como obsoleto.
- CA-10: conteo calculado en Rust desde leases; snapshot público por outbox/inbox Discovery. Observación/publicación<=2s, entrega/aplicación<=3s y frescura separada de estado. Duplicados/desorden no regresan versiones; ENDED excluido y atraso UNKNOWN. Reconstrucción consistente con watermark e inbox durante rebuild.
- CA-11: las versiones se comparan dentro del agregado y la sesión vigente, revisando streamGeneration antes de sessionVersion. La secuencia de un mensaje no se compara con countVersion.
- CA-12: una llamada Core por nuevo envío compone sesión/autor locales y estado/timeline actual Streaming (hop<=200ms incluido en contexto<=400ms); sin caché de permisos. Core/Streaming caído rechaza sin persistencia; operación ya autorizada tiene presupuesto acotado explícito.
- CA-13: bootstrap por handle compone cuenta/perfil/canal locales y un batch autoritativo Streaming; ausencia confirmada devuelve OFFLINE/streamStatusFresh=true, fallo devuelve UNKNOWN/false y conserva datos Core. Configuración sin sesión, PLAYABLE, gracia y ENDED conservan los DTO/IDs canónicos; Discovery no usa HTTP por fila.
- CA-14: edición de perfil/avatar y descripción/portada se observa desde sus APIs y canal compuesto; fallo de upload/SQL conserva referencia anterior. El siguiente mensaje autorizado usa el nuevo autor público, mientras mensajes previos conservan su snapshot. Propietario ajeno, sesión expirada y logout rechazan la siguiente escritura en Core, Streaming y Chat.
- CA-15: validar catálogo y filtros en extremos reales: IDs activos/tipados, 0/5/6 tags, omitido frente a null, edición LIVE inválida sin cambio, tombstone con último label y valor nuevo sin rebuild Web. Discovery verifica conjuntos exactos, categoría+tag AND, normalización parcial, ranking/empates/cursor, ENDED/gracia excluidos y UNKNOWN por atraso.
- CA-16: Chat conserva mensajes con ACK durante la vida de la sala y reinicios, fusiona WS/historial por sesión/sequence sin huecos ni duplicados y mantiene cuota global entre salas/réplicas. ENDED produce read-only y eliminación a los 5 min según ADR-010; reentrega de fin no renueva la retención. Recuperar inventario/estado de sesiones no reconstruye mensajes perdidos: estos requieren AOF/backup dentro de su retención.

## 7. Diseño técnico y datos

Registro una transacción SQL Core; sesión y snapshots públicos commit + outbox Rust→Chat/Discovery con ACKs independientes. Inbox durable HTTPS sin broker. Canal usa datos Core y batch autoritativo Streaming; Discovery SQL local con proyección/versiones/frescura y corte reconstruible. P1 una réplica Streaming, transiciones con bloqueo/CAS; pérdida owner/clock termina sin nueva gracia. Multi-réplica requiere fencing/transferencia probados. Correlación sin secretos. Accounts publica ChatContexts con perfil/sesión locales y un puerto Streaming;
Channels publica ChannelBootstrapService con lectura SQL local y un batch Streaming. Un adaptador
HTTP común aplica TLS por defecto, 64 KiB, 200 ms para sesión/1 s para canal y ninguna redirección
o caché de permisos. El token Chat solo permite sus dos rutas internas Core.
El TTL de caché de sala no elimina mensajes, sequence ni dedupe activos; ENDED inicia la retención
una sola vez.

En P1, ADR-011 integra el adaptador técnico en el proceso Streaming. Autorización/callbacks internos conservan HTTP loopback autenticado y persistencia separada. HLS público se enruta al listener 8888 del contenedor Streaming; RTMP sigue en MediaMTX. Reinicios/fallos del proceso afectan al control y al adaptador juntos.

## 8. Dependencias y contratos de integración

contratos_modelo_datos.md especifica schemas/timeouts; integracion_sistema_p1.md secuencias; proxy en documento frontend. Streaming–Media autentica/observa fuente; Core–Chat compone contexto usando Streaming; Streaming notifica ciclo a Chat y snapshots a Discovery. Cuentas/Canales/Catálogo siguen interfaces locales Core.

## 9. Decisiones y preguntas abiertas

Estado y cuotas preservados, topología de ADR-005; delivery HTTPS con outbox/inbox a Chat/Discovery, sin broker obligatorio. La proyección pública cruza la frontera Streaming→Core.

## 10. Verificación

Verificar: rollback/retry de registro, callback duplicado/ACK perdido/antiguo, bordes29.999/30.000s, autorización tras logout/fin, cuota/dedupe/commit/broadcast de Chat, reinicios y recuperación sin extender gracia, HLS aislado de Chat/Discovery; proyección reconstruida con cambios/ENDED concurrentes, watermark y reinicio sin regresión.

## 11. Esfuerzo, riesgos y consecuencias

Core concentra el fallo de sus módulos. La autorización síncrona de Chat, las operaciones ya
autorizadas que siguen en curso y las dependencias multimedia también requieren pruebas cuidadosas.

La coordinación de cada caso de uso queda en su módulo responsable. Integración conecta los
extremos y comprueba los resultados.
