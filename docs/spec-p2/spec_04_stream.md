# SPEC-04 — Multicalidad, grabación y eventos de Streaming

**Prioridad:** P2. **Responsable:** Streaming / Media. **Estado:** ingesta, HLS y VOD multicalidad integrados y verificados en local; configuración OBS disponible, operación manual OBS pendiente.
**RF:** RF-017…030, 052, 056…057, 079…080. **RNF:** RNF-012…018, 021…025, 038, 041…047, 050…051.

## 1. Contexto y problema

La misma emisión debe ofrecer hasta cuatro calidades recibidas, grabarlas y avisar a Core,
Chat y VOD sin atar su estado a la disponibilidad de esos consumidores.

## 2. Definición del componente

Streaming Rust sigue siendo autoridad de sesión, cupos, reloj, generaciones, configuración y
leases. Media ingiere, empaqueta y captura; VOD valida/publica la grabación.
[ADR-026](../adr/ADR-026-multicalidad-enhanced-rtmp.md) fija la adaptación comprobada de MediaMTX.

## 3. Historia de usuario

Como streamer, quiero emitir mis calidades como una única sesión y conservarlas en VOD sin
que el servidor genere versiones de mayor resolución.

## 4. Alcance

Conserva [máquina de estados y control base](../spec-p1/spec_04_stream.md), incorpora rendiciones,
manifiesto maestro, referencia de segmentos durables, cierre de grabación y publicación por broker.
No incorpora transcoding de calidades en servidor ni un cupo por conexión/rendición.

## 5. Requisitos funcionales

- Hasta cuatro rendiciones reales comparten sessionId/generación y un cupo, sin upscale.
- Fuente nueva, reconexión y stop mantienen garantías de estado/tiempo de la base.
- Segmentos se notifican solo después de carga confirmada en S3, con identidad, orden y checksum.
- Session ENDED y grabación cerrada son señales distintas; un cierre incluye inventario final.
- La preferencia de autopublicación se captura al abrir la sesión y no cambia durante ella.

## 6. Criterios de aceptación

- [ ] CA-01: OBS → ingesta → maestro HLS → player permite selección manual/automática de hasta cuatro rendiciones realmente recibidas.
- [x] CA-02: fuente 720p no anuncia 1080p; dos rendiciones no se rechazan como dos emisiones del mismo canal ni consumen dos cupos.
- [ ] CA-03: cambio de calidad conserva línea temporal, audio y fuente; keyframes/discontinuidades permiten conmutación reproducible.
- [x] CA-04: checksum/longitud coinciden con objeto S3 antes del evento; clave de ingesta no aparece en objeto, URL o payload.
- [x] CA-05: reintentar carga/publicación mantiene identidad de segmento sin duplicar ni sobrescribir una generación anterior.
- [x] CA-06: al finalizar se confirma índice completo y `recording.closed.v1`; un segmento faltante impide publicar un VOD completo.
- [x] CA-07: reconexión dentro de gracia conserva sesión y grabación, incrementa generación de fuente y hace explícita la discontinuidad.
- [ ] CA-08: broker/consumidor caído conserva intención durable; ciclo y proyección se recuperan sin reiniciar la emisión.
- [x] CA-09: suspensión corta fuente y nuevas entregas; los casos de cupos, leases, stop y reconexión base no regresan.

## 7. Diseño técnico y datos

`P2Rendition` identifica resolución/bitrate/códec. No se fija una escalera 1080/720/480/360:
se anuncian las recibidas. El ciclo P2 incluye channelId, firstLiveAtUtc e initialLive. Solo la primera transición
reproducible de la sesión tiene initialLive=true; reconexiones posteriores conservan la hora y
publican false. La preferencia se consulta por API privada al reservar una sesión nueva, se
persiste con ella y se reutiliza al reconectar. Si no puede validarse, no se inventa true.

Enhanced RTMPS recibe de uno a cuatro videos H.264 y un AAC opcional en una sola conexión.
Media conserva los timestamps y agrupa un video con el mismo audio por muxer HLS/grabador.
El maestro anuncia las rendiciones realmente recibidas; no se recodifica ni escala en servidor.
Una sola autorización de publisher conserva un cupo y secreto por emisión, sin exponerlo en paths públicos.

`P2Segment` tiene identidad lógica `(sessionId,renditionId,sourceGeneration,sequence)`, tiempo de
sesión, duración, discontinuidad y objeto confirmado. El objeto se direcciona por objectId opaco
bajo un prefijo S3 configurado; los roles autorizados traducen esa referencia sin credenciales
en eventos. Escribir repetido con hash diferente genera conflicto, no reemplazo silencioso.

`P2RecordingIndex` enumera todos los segmentos/rendiciones y `P2RecordingClosed` referencia su
objeto confirmado. Permite a VOD recuperar eventos faltantes del índice, comparar checksum y
distinguir fin de fuente de fin de cargas. El escritor VOD y su recuperación
están definidos en ADR-022. Cada inventario nativo declara el plan completo de rendiciones;
el cierre exige todos los grupos y generaciones, incluso si falta el primer segmento de un grupo. Fallar captura no fabrica un objeto ni un cierre completo.

La autorización de ingesta consulta el estado actual de Cuentas mediante
`p2.streaming.channel-access`: el listener privado solo acepta la credencial completa Streaming.
Cada sesión guarda `owner_account_version`; una cuenta inicialmente activa tiene versión cero.
Sesiones previas a la migración quedan cercadas por -1 hasta terminarse. Suspender o cambiar la
versión rechaza nuevas autorizaciones y lecturas públicas, incluidas HLS/preview; reactivar no
revive una sesión antigua. Una autoridad caída falla cerrada.

`streaming.account` consume `account.status.changed.v1` con inbox durable, hash del cuerpo,
ACK posterior a la persistencia y retries/DLQ confirmados. Los eventos viejos no detienen una
sesión autorizada con una versión nueva. La reconciliación con Core cada 500 ms cubre eventos
pendientes o perdidos durante indisponibilidad del broker y usa el stop/outbox habitual para
liberar cupo, cortar fuente y propagar fin a Chat/VOD. No accede a tablas de Core.

## 8. Dependencias y contratos de integración

`p2.streaming.renditions`, los eventos de sesión/snapshot y `recording.segment.available.v1` /
`recording.closed.v1`; [S3 y timeline](../contratos_p2_media.md). Core valida owner/estado actual;
VOD consume referencia e índice; Chat conserva contexto temporal actual por HTTP.

La consulta `GET /api/streams/sessions/{sessionId}/renditions` lee el maestro público de la
sesión LIVE/PLAYABLE mediante el adaptador Media. Devuelve `P2Renditions` con uno a cuatro IDs
estables derivados del path público de variante, resolución, BANDWIDTH y códecs declarados por
HLS. Comprueba que cada playlist tenga segmentos anunciados; no genera una escalera ni deduce
metadatos ausentes. Paths de variante y segmento permanecen dentro del directorio de la sesión.
Una sesión inexistente devuelve 404; una sesión no reproducible, medio no disponible, metadatos
insuficientes, variantes duplicadas o más de cuatro devuelve 503; agotar el plazo total de dos
segundos devuelve 504. Todas las respuestas llevan `Cache-Control: no-store`. Al terminar la
lectura se revalida la sesión para descartar cambios de estado/fuente durante la consulta.

El reproductor Web carga las opciones del maestro mediante hls.js y comienza en modo automático.
La selección manual conserva el búfer, posición y lease; cambia la calidad de nuevas cargas.
La UI distingue la calidad solicitada de la variante que ya se reproduce. Si desaparece la
variante seleccionada, vuelve a automático; reordenar las variantes conserva la selección por
URI. Con HLS nativo, el navegador administra la adaptación y la UI informa esa limitación sin
ofrecer selección manual. Web y móvil incorporan reproductores VOD; la aceptación multicalidad exige una fuente real.
La ruta watch SSR obtiene el estado inicial de Streaming y renderiza el marco del player sin
cargar HLS. La hidratación reutiliza emisión/sesión/canal; el selector descubre las variantes
después de montar hls.js, y el lease continúa ligado al primer frame decodificado. Véase
[SPEC-18](spec_18_ssr.md) para destinos de API, plazos y revalidación.

## 9. Decisiones y preguntas abiertas

P2-08 queda implementada por ADR-026 y su prueba Enhanced RTMPS → HLS → VOD.
Se entrega configuración OBS oficial; falta registrar su operación manual en el equipo emisor.
P2-09/10 están resueltas por ADR-022/024: escritor VOD, objetos privados y compactación temporal.

## 10. Verificación

Usar fuente de una y cuatro calidades, verificar maestro/variantes y guardar todas. Inyectar
corte entre PUT/confirmación/evento, pérdida de evento de segmento, cierre adelantado, duplicado
y reconexión. VOD debe detectar incompletitud y recuperar desde inventario, nunca por espera fija.

Las pruebas locales cubren lectura de maestro con una/cuatro variantes, fuente 720 sin 1080
inventado, IDs estables, entradas inválidas, límites, estados no reproducibles y respuestas HTTP.
Las pruebas Web cubren selección manual/automática, actualización de variantes y conservación
de posición/lease. `services/streaming/tests/smoke_stack.py` valida además una fuente RTMP real
de 320×180: consulta de calidades sin caché, reproducción HLS con un frame decodificado y
rechazo de la consulta tras finalizar la sesión.

El 2026-11-03 el [runner multicalidad](../../tests/integration/p2-multirendition/README.md)
publicó cuatro H.264 (720/540/360/180p, 25 fps, GOP alineado) y AAC por una conexión RTMPS.
Chromium decodificó las cuatro calidades live, cambió manual/automático conservando posición
y sesión, y reprodujo las cuatro del VOD READY/publicado. El test nativo VOD exige el grupo
ausente antes de certificar cierre y conserva secuencia cero independiente por rendición.
La prueba de protocolo no equivale a una operación manual de la UI de OBS ni a medición humana de sincronía audiovisual.

El recorrido `tests/integration/p2-account-live/run.py` verificó suspensión durante RTMPS:
HLS/preview dejan de entregarse en menos de cinco segundos, el reconector es rechazado,
la reactivación no revive la sesión y una nueva emisión recupera el cupo. Las suites PostgreSQL
y del dominio conservan los casos de leases, versiones, stop, reconexión y outbox.
La ejecución adicional `--reconnect` publica las cuatro calidades en dos generaciones de fuente,
conserva la sesión y las variantes y termina en un VOD READY que reproduce las cuatro.
El timeline declara ambas generaciones y el montaje conserva sus discontinuidades.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. El principal riesgo es confundir soporte de ingesta multitrack con HLS multivariante.
No se acepta retirar calidades del VOD para ocultar una limitación del empaquetado.
