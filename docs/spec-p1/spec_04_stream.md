# SPEC-04 Sesión en vivo e integración multimedia P1

- **Módulo:** streaming
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-017…RF-026 para ingesta, sesión, emisión, reproducción, metadatos y espectadores. Debe existir video real reproducible; no se simula el estado LIVE.

## 2. Estado del sistema y brecha

Calidad/transcoding queda fuera de P1. El módulo conecta productor RTMP, servidor multimedia, backend de control y reproductor HLS mediante los contratos de SPEC-10 y SPEC-11.

## 3. Historia de usuario

Como broadcaster, quiero emitir una señal audiovisual desde mi canal; como espectador, quiero reproducirla y conocer su estado e información.

## 4. Alcance

### Dentro de P1

- El propietario prepara título y categoría requeridos y hasta cinco etiquetas opcionales; RTMP válido inicia automáticamente la sesión.

- Cada canal tiene una configuración persistente `streamId`; se reutiliza al terminar una emisión. Cada conexión RTMP aceptada abre una sesión con `sessionId` nuevo.

- Cada emisión nueva incrementa `streamGeneration` monotónica por `streamId`; reconexión dentro de gracia reutiliza `sessionId`/generación y crea sourceGeneration nueva.

- Crear y editar metadata tanto OFFLINE como LIVE; crear la configuración entrega una clave privada RTMP una vez y se puede rotar solo estando OFFLINE.

- Máximo una sesión no terminada por canal y cinco en plataforma contando PREPARING, LIVE y RECONNECT_GRACE; ENDED libera el slot.

- LIVE solo tras confirmación de medio reproducible; HLS para playback anónimo.

- Desconexión: mantener la sesión/chat por 30 s; reconexión en ventana conserva sessionId; al expirar se finaliza y luego se crea nueva sesión.

- Stop voluntario inmediato.

- Contar sesiones de reproducción anónimas/autenticadas; expirar heartbeat a 30 s.

- Permitir actualizar título, categoría y etiquetas durante LIVE.

### Fuera de P1

- Transcoding/selección de calidad; grabación/VOD; subtítulos; múltiples canales por cuenta.

- Ingesta no RTMP, protocolo adicional y simulcast.

### Supuestos acordados

- MediaMTX es candidato recomendado, no selección obligatoria. Responsable registra ADR con comparación de opciones.

- La UI distingue interrupción temporal de estado OFFLINE; el contrato de medio debe informar un manifiesto HLS reproducible.

- Media adapter informa fuente conectada, playback listo y fuente perdida por `POST /internal/streaming/sessions/{sessionId}/source-connected`, `/playback-ready` y `/source-lost`; son HTTPS/TLS privados y autenticados. Streaming deduplica por eventId, valida sourceGeneration y confirma durablemente antes del ACK.

## 5. Requisitos funcionales

- RF-017…RF-018 iniciar y asociar sesión al canal propietario.

- RF-019 metadatos obligatorios/optativos y edición durante LIVE.

- RF-020…RF-022 estados, stop y timeout/reconexión.

- RF-023…RF-025 acceso anónimo, reproducción HLS e información actual.

- RF-026 conteo de espectadores por reproducción activa.

## 6. Criterios de aceptación

- CA-01 — RTMP válido sin título o categoría no obtiene sesión; metadata completa inicia PREPARING y solo medio reproducible pasa a LIVE/PLAYABLE. Si no logra playback dentro de 30 s desde la autorización RTMP, pasa a ENDED/OFFLINE y libera el slot. Reintento de la misma autorización con ingestAttemptId devuelve sessionId/generaciones originales sin reservar otro slot.

- CA-02 — intento de segunda fuente en un canal con una sesión PREPARING, LIVE o RECONNECT_GRACE se rechaza; nunca hay más de cinco sesiones no terminadas a nivel de plataforma.

- CA-03 — desde que el espectador solicita reproducir hasta el primer frame visible transcurren como máximo 5 s en el perfil de red/carga normal documentado por SPEC-13; el umbral es máximo, no percentil.

- CA-04 — pérdida de fuente pone la misma sesión en `RECONNECT_GRACE` durante 30 s; el estado de ciclo sigue activo, availability=RECONNECTING, Channel lo muestra como LIVE · reconectando, Discovery no lo ofrece en resultados reproducibles y Chat conserva lectura/escritura. Streaming publica timeline samples al menos cada segundo durante toda la gracia. Un mensaje válido cercano al segundo 29 usa sample de no más de 3 s. Una reconexión gana solo si MediaPlaybackReady se valida antes del deadline; al llegar a `now >= deadline` gana el timer, transición atómica a ENDED/OFFLINE y callback tardío se ignora. Retorno posterior requiere sessionId nuevo.

- CA-05 — stop del propietario finaliza inmediatamente y los cambios de estado llegan a consultas en 5 s.

- CA-06 — cambio de metadatos durante LIVE aparece tras confirmación en canal y Discovery dentro de la frescura definida; categoría una, tags 0–5.

- CA-07 — player muestra handle/nombre visible del canal, título actual y categoría actual del stream; ninguna respuesta revela email, handle de ingestión ni otros secretos.

- CA-08 — un lease por instancia de player se crea solo tras primer frame; se heartbeat cada 10 s, se elimina al cerrar player y expira 30 s después del último heartbeat válido. Cliente no controla viewerCount; no se requiere login.

- CA-09 — prueba de 5 emisiones y 100 espectadores concurrentes totales durante 10 minutos según el perfil reproducible SPEC-13.
- CA-10 — nuevas configuraciones y cambios explícitos de categoría/tags solo admiten IDs activos; si un valor actualmente asociado queda inactivo, un patch de título preserva esa asociación y la metadata pública conserva su último label.
- CA-11 — `viewerCount` es una estimación best-effort de leases de player vigentes, no una medida resistente a bots ni a tráfico automatizado. Discovery puede usarla para ordenar y mostrar popularidad aproximada; P1 no la usa para autorización, cobros, beneficios ni decisiones de seguridad. Antes de asignarle consecuencias económicas o de abuso, se requiere un control antiabuso fuera del alcance de P1.
- CA-12 — las tres rutas de callback validan el envelope común eventId/streamId/sessionId/streamGeneration/sourceGeneration, auth de servicio, sesión y path HLS. ACK 202 ocurre tras aceptación durable; repetir mismo eventId/payload es idempotente, reuso con payload diferente da 409 y generación vieja se ignora con 200. Timeout por intento 2 s; se reintenta timeout/408/429/5xx tras 100/250/500/1000/2000 ms y luego cada 2 s con el mismo eventId durante un máximo de 15 min desde el primer intento. Cualquier 2xx confirma ACK; 410 SESSION_ENDED cierra como callback obsoleto. 409/422 u otro 4xx permanente pasa a dead-letter y alerta. Si no llega respuesta terminal en 30 s, se alerta una vez por sesión/sourceGeneration y se sigue reintentando hasta 15 min; entonces pasa a dead-letter durable sin reintento automático. El operador la redrivea con el mismo eventId/payload y una nueva ventana de 15 min, o la cierra tras confirmar que es irrecuperable/obsoleta; no expira automáticamente y la capacidad se fija en ADR operativo. Intentos, edad y cola se miden. PlaybackReady solo pasa a PLAYABLE tras comprobar manifiesto y segmento.
- CA-13 — Streaming envía snapshot `ViewerCountChanged` con `aggregateId=viewer-count:{sessionId}` al entrar en PLAYABLE, al cambiar conteo (máximo uno por segundo) y cada 5 s aunque no cambie; countVersion crece por snapshot. Discovery aplica solo sesión/generación vigente y versión mayor, refleja un cambio en 5 s en operación normal, marca viewerCountFresh=false si pasan más de 5 s sin snapshot y reconstruye con GET de sesión. Una sesión ENDED no reaparece por count tardío.

## 7. Diseño técnico y datos

- Propiedad: StreamConfig/streamId estable por canal, ownerId, title/categoryId/tagIds y clave privada; StreamSession/sessionId nuevo por emisión, estado/disponibilidad, timestamps y viewer leases. Streaming posee estado, reloj y conteo. Estado interno PREPARING/LIVE/RECONNECT_GRACE/ENDED se separa de disponibilidad PLAYABLE/RECONNECTING/OFFLINE. Nueva configuración inicia metadataVersion=1; streamGeneration inicia en 0 y aumenta uno por cada sessionId nuevo.

- Estados de dominio: PREPARING, LIVE, RECONNECT_GRACE, ENDED. La respuesta pública expone disponibilidad PLAYABLE, RECONNECTING u OFFLINE para separar ciclo activo de posibilidad real de reproducción.

- Separar control plane de transporte multimedia mediante adapter/callback/health API; exponer estado reproducible y playback URL solo a lectores públicos cuando hay medio PLAYABLE. Stream key/ingest secret solo se muestra al propietario autenticado por canal seguro; nunca se entrega a player/Discovery/evento/log.

- Publicar creación/lectura/edición de configuración, rotación de stream key, finalización de sesión, `GET /api/streams/{streamId}`, `GET /api/streams/sessions/{sessionId}` y viewer leases según `contratos_modelo_datos.md`; no aceptar viewerCount, leaseId ni tokens escogidos por el cliente.

- Selección inicial y cambios explícitos de categoría/tag validan IDs activos en Taxonomy; IDs omitidos se conservan aunque se desactiven posteriormente. Discovery resuelve labels activos o tombstones por el lookup interno de Taxonomy.

- Aceptar callbacks HTTP autenticados en tres paths internos fijados por SPEC-10; sobre común `eventId`, `streamId`, `sessionId`, `streamGeneration`, `sourceGeneration`; Streaming registra el evento durable antes de responder y deduplica el eventId. `playbackPath` debe ser relativo y bajo `/hls/{sessionId}/`; timestamps son asignados por Streaming. Retries, timeouts, ACK y códigos de error quedan fijados en `contratos_modelo_datos.md`.
- `viewerCount` cuenta leases por instancia de player según heartbeat; es orientativo y puede inflarse con clientes automatizados aun cuando el servidor emita tokens. Streaming publica snapshots versionados y Discovery recibe su proyección; en P1 solo informa/rankea y nunca concede acceso, pagos ni ventajas.

- Publicar timeline sample al inicio de LIVE, al cambiar disponibilidad y terminar, y al menos cada segundo durante LIVE y toda RECONNECT_GRACE; el reloj monotónico de la sesión sigue avanzando en gracia hasta ENDED. Un sample incluye `sessionVersion` y llega a Chat con antigüedad máxima de 3 s para aceptar escritura.

- Conector multimedia: RTMP ingress y HLS delivery; definir reverse proxy para HLS y configurar RTMP por puerto específico; no exponer secreto de emisión.

- ADR debe comparar MediaMTX/OvenMediaEngine/u otra opción según códecs, HLS latency, callbacks, límites, local reproducible y compatibilidad.

## 8. Dependencias y contratos de integración

- Identity valida propietario y autorización del stream key.

- Channels aporta owner/channel data y consume eventos de estado.

- Taxonomy valida categoryId/tagIds.

- Chat abre/cierra la sala usando stream session lifecycle y asocia sessionId.

- Discovery consume stream state, metadata y count.

- Frontend integra control de emisión y player; proxy enruta HLS.

## 9. Decisiones y preguntas abiertas

**Decisiones:** un streamId/configuración persistente por canal; nueva sessionId por emisión; RTMP/HLS, LIVE solo al confirmar playback, auto-start tras metadata, PREPARING máximo 30 s, máximo cinco sesiones no terminadas, estado/disponibilidad separados, gracia 30 s, lease por player/expiración 30 s; máximo 5 s al primer frame según RNF-012. La tecnología concreta la decide el owner.

**Abierto:** no hay preguntas de producto bloqueantes. La persona responsable debe registrar el ADR y cerrar los detalles de implementación listados en Diseño.

## 10. Verificación

- state machine y transición con fuente real, start/stop, pérdida/reconexión en bordes 29/30/31 s, server callback tardío y duplicado.

- HLS anónimo; medir solicitud→primer frame y verificar máximo 5 s con perfil controlado; reproducción para 100 espectadores agregados.

- integridad y autorización de stream key; actualización de metadatos; conteo/heartbeat; errores de media server sin estado LIVE falso.

- simultaneidad 5 streams/100 reproductores durante 10 min; sexto stream rechazado; retención de datos de usuario/canal/stream tras reinicio según RNF-022 y RNF-050.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** L. **Riesgos:** latencia de HLS, incompatibilidad de codecs/OBS, discrepancia entre health del medio y API, coste de recursos al llegar al quinto stream y exposición de stream keys. **Consecuencia:** no implementar capacidades explícitamente fuera de P1.
