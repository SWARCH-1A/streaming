# SPEC-04 Sesión en vivo e integración multimedia P1

- **Módulo:** streaming
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-017…RF-026 para ingesta, sesión, emisión, reproducción, metadatos y espectadores. Debe existir video real reproducible; no se simula el estado LIVE.

## 2. Definición del componente

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

- ADR-005 selecciona MediaMTX autogestionado y adaptador técnico Rust. Se verifican configuración/códecs, ingesta, recuperación y latencia antes del despliegue.

- La UI distingue interrupción temporal de estado OFFLINE; el contrato de medio debe informar un manifiesto HLS reproducible.

- Media adapter informa fuente conectada, playback listo y fuente perdida por `POST /internal/streaming/sessions/{sessionId}/source-connected`, `/playback-ready` y `/source-lost`; son privados y autenticados; HTTP loopback dentro del proceso P1 según ADR-011, HTTPS/TLS cuando crucen contenedores. Streaming deduplica por eventId, valida sourceGeneration y confirma durablemente antes del ACK.

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

- CA-04 — pérdida de fuente pone la misma sesión en `RECONNECT_GRACE` durante 30 s; el estado de ciclo sigue activo, availability=RECONNECTING, Channel lo muestra como LIVE · reconectando, Discovery no lo ofrece en resultados reproducibles y Chat conserva lectura/escritura. Core obtiene de Streaming el timeline vigente por mensaje y lo incluye en el contexto autorizado; un mensaje cercano al segundo 29 se valida contra ese estado. Una reconexión gana solo si MediaPlaybackReady se valida antes del deadline; al medir `elapsed >= 30 s` desde la pérdida con reloj monotónico gana la finalización, transición atómica a ENDED/OFFLINE y callback tardío se ignora. Retorno posterior requiere sessionId nuevo.

- CA-05 — stop del propietario finaliza inmediatamente y los cambios de estado llegan a consultas en 5 s.

- CA-06 — cambio de metadatos durante LIVE aparece tras confirmación en canal y Discovery dentro de la frescura definida; categoría una, tags 0–5.

- CA-07 — player muestra handle/nombre visible del canal, título actual y categoría actual del stream; ninguna respuesta revela email, handle de ingestión ni otros secretos.

- CA-08 — un lease por instancia de player se crea solo tras primer frame; se heartbeat cada 10 s, se elimina al cerrar player y expira 30 s después del último heartbeat válido. Cliente no controla viewerCount; no se requiere login.

- CA-09 — prueba de 5 emisiones y 100 espectadores concurrentes totales durante 10 minutos según el perfil reproducible SPEC-13.
- CA-10 — nuevas configuraciones y cambios explícitos de categoría/tags solo admiten IDs activos; si un valor actualmente asociado queda inactivo, un patch de título preserva esa asociación y la metadata pública conserva su último label.
- CA-11 — `viewerCount` es una estimación best-effort de leases de player vigentes, no una medida resistente a bots ni a tráfico automatizado. Discovery puede usarla para ordenar y mostrar popularidad aproximada; P1 no la usa para autorización, cobros, beneficios ni decisiones de seguridad. Antes de asignarle consecuencias económicas o de abuso, se requiere un control antiabuso fuera del alcance de P1.
- CA-12 — las tres rutas de callback validan el envelope común eventId/streamId/sessionId/streamGeneration/sourceGeneration, auth de servicio, sesión y path HLS. ACK 202 ocurre tras aceptación durable; repetir mismo eventId/payload es idempotente, reuso con payload diferente da 409 y generación vieja se ignora con 200. Timeout por intento 2 s; se reintenta timeout/408/429/5xx tras 100/250/500/1000/2000 ms y luego cada 2 s con el mismo eventId durante un máximo de 15 min desde el primer intento. Cualquier 2xx confirma ACK; 410 SESSION_ENDED cierra como callback obsoleto. 409/422 u otro 4xx permanente pasa a dead-letter y alerta. Si no llega respuesta terminal en 30 s, se alerta una vez por sesión/sourceGeneration y se sigue reintentando hasta 15 min; entonces pasa a dead-letter durable sin reintento automático. El operador la redrivea con el mismo eventId/payload y una nueva ventana de 15 min, o la cierra tras confirmar que es irrecuperable/obsoleta; no expira automáticamente y la capacidad se fija en ADR operativo. Intentos, edad y cola se miden. PlaybackReady solo pasa a PLAYABLE tras comprobar manifiesto y segmento.
- CA-13 — Streaming calcula/versiona conteo de leases en su SQL; cambios agrupados como máximo 1/s. Publica snapshots públicos por outbox e inbox Discovery. En carga normal observación/publicación <=2 s y entrega/aplicación <=3 s, para reflejar cambios <=5 s; viewerCountFresh=false si observación supera 5 s. Discovery excluye ENDED y estado PLAYABLE no confirmado/fuera de frescura. Duplicados/versiones viejas no revierten datos; reconstrucción completa usa snapshot consistente y watermark.

## 7. Diseño técnico y datos

Streaming es servicio Rust independiente según ADR-005. Posee StreamConfig/streamId, claves de ingesta, StreamSession/sessionId, cupos, reloj, generaciones, leases y versiones en PostgreSQL privado con pooling. Core conserva Cuentas, Canales, Catálogo y Discovery. Para cada comando protegido Streaming solicita un contexto Core nuevo con identidad, propiedad y validación tipada/activa de categoría/tags presentes; usa IDs opacos y snapshots de labels/tombstones, sin FK entre bases ni permiso cacheado. Una autorización en vuelo tiene presupuesto acotado explícito en contratos.

P1 usa tres contenedores: PostgreSQL, MediaMTX y Streaming con adaptador técnico en el mismo runtime Tokio (ADR-011). El adaptador conserva base/rol y repositorio separados; comparte fallo/reinicio con Streaming. Los contratos técnicos internos usan HTTP loopback autenticado en desarrollo; el perfil persistente SPEC-13/ADR-014 usa HTTPS autenticado con CA explícita, sin contenedor adicional. MediaMTX transporta RTMP/LL-HLS; el adaptador Rust autoriza ingest y envía callbacks idempotentes con ACK durable, eventId/sourceGeneration y path HLS validado. Streaming verifica manifiesto/segmento y frame antes de LIVE. Stop confirma ENDED inmediatamente y ordena cortar la fuente. Cupos cinco global/uno por canal se reservan atómicamente en SQL.

El mismo commit conserva eventos de sesión para Chat y snapshots públicos para la proyección Discovery, con entregas independientes por consumidor. Discovery aplica en Core y combina con datos públicos locales; sus filas no autorizan comandos ni mensajes. Timeline se calcula al leer/contextualizar cada envío; no se replica como muestra periódica. Claves, secretos y datos de identidad privada nunca viajan en eventos.

P1 arranca una réplica Streaming; timers/callbacks concurren bajo bloqueo/CAS. Reinicio no extiende gracia: recuperar restante verificable o terminar. Multi-réplica necesita fencing/enrutamiento/clock probado antes de habilitar. Media no posee sesión de negocio ni permisos.

## 8. Dependencias y contratos de integración

Core publica contexto de owner/catálogo y recibe snapshots públicos en inbox; Discovery usa su proyección SQL. Core consulta estado/timeline autoritativo a Streaming para cada contexto de mensaje Chat. Streaming entrega lifecycle a Chat y recibe señales Media. Web usa GraphQL Core, API Streaming y HLS Media; integration conecta extremos/proxy y verifica contratos después de desarrollo del módulo.

## 9. Decisiones y preguntas abiertas

**Decisiones:** un streamId/configuración persistente por canal; nueva sessionId por emisión; RTMP/HLS, LIVE solo al confirmar playback, auto-start tras metadata, PREPARING máximo 30 s, máximo cinco sesiones no terminadas, estado/disponibilidad separados, gracia 30 s, lease por player/expiración 30 s; máximo 5 s al primer frame según RNF-012. Rust/SQLx/PostgreSQL y MediaMTX/LL-HLS aceptados en ADR-005.

**Abierto:** no hay preguntas de producto bloqueantes. ADR-005 fija la frontera y tecnología. La entrega durable y capacidad DLQ del adaptador se definen en la [ADR de callbacks Media](../../services/streaming/docs/adr/0001-media-callback-delivery.md); la aceptación de recuperación y latencia se rige por los criterios de esta SPEC y SPEC-13.

## 10. Verificación

State machine real, 29/30/31 s, PREPARING 30 s, callback duplicado/sourceGeneration vieja/ACK perdido, cupos concurrentes, metadata y contexto Core de catálogo/owner, HLS primer frame máximo 5 s, cien players/leases por diez min; Streaming reinicio sin nueva gracia; proyección Discovery con duplicados/desorden/atraso/rebuild. Medir el timeline Streaming proporcionado por el contexto Core.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** L. **Riesgos:** latencia de HLS, incompatibilidad de codecs/OBS, discrepancia entre health del medio y API, coste de recursos al llegar al quinto stream y exposición de stream keys. **Consecuencia:** no implementar capacidades explícitamente fuera de P1.
