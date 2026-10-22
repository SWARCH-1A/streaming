# SPEC-04 — Multicalidad, grabación y eventos de Streaming

**Prioridad:** P2. **Responsable:** Streaming / Media. **Estado:** diseño para desarrollo.
**RF:** RF-017…030, 052, 056…057, 079…080. **RNF:** RNF-012…018, 021…025, 038, 041…047, 050…051.

## 1. Contexto y problema

La misma emisión debe ofrecer hasta cuatro calidades recibidas, grabarlas y avisar a Core,
Chat y VOD sin atar su estado a la disponibilidad de esos consumidores.

## 2. Definición del componente

Streaming Rust sigue siendo autoridad de sesión, cupos, reloj, generaciones, configuración y
leases. Media ingiere, empaqueta y captura; VOD valida/publica la grabación. El responsable
resuelve el empaquetado que la versión MediaMTX actual no acredita.

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
- [ ] CA-02: fuente 720p no anuncia 1080p; dos rendiciones no se rechazan como dos emisiones del mismo canal ni consumen dos cupos.
- [ ] CA-03: cambio de calidad conserva línea temporal, audio y fuente; keyframes/discontinuidades permiten conmutación reproducible.
- [ ] CA-04: checksum/longitud coinciden con objeto S3 antes del evento; clave de ingesta no aparece en objeto, URL o payload.
- [ ] CA-05: reintentar carga/publicación mantiene identidad de segmento sin duplicar ni sobrescribir una generación anterior.
- [ ] CA-06: al finalizar se confirma índice completo y `recording.closed.v1`; un segmento faltante impide publicar un VOD completo.
- [ ] CA-07: reconexión dentro de gracia conserva sesión y grabación, incrementa generación de fuente y hace explícita la discontinuidad.
- [ ] CA-08: broker/consumidor caído conserva intención durable; ciclo y proyección se recuperan sin reiniciar la emisión.
- [ ] CA-09: suspensión corta fuente y nuevas entregas; los casos de cupos, leases, stop y reconexión base no regresan.

## 7. Diseño técnico y datos

`P2Rendition` identifica resolución/bitrate/códec. No se fija una escalera 1080/720/480/360:
se anuncian las recibidas. El ciclo P2 incluye channelId, firstLiveAtUtc e initialLive. Solo la primera transición
reproducible de la sesión tiene initialLive=true; reconexiones posteriores conservan la hora y
publican false. La preferencia se consulta por API privada al reservar una sesión nueva, se
persiste con ella y se reutiliza al reconectar. Si no puede validarse, no se inventa true.

La estrategia de ingesta puede requerir adaptar autorizaciones de
publisher, pero conserva un cupo y secreto por emisión, sin exponer ese secreto en paths públicos.

`P2Segment` tiene identidad lógica `(sessionId,renditionId,sourceGeneration,sequence)`, tiempo de
sesión, duración, discontinuidad y objeto confirmado. El objeto se direcciona por objectId opaco
bajo un prefijo S3 configurado; los roles autorizados traducen esa referencia sin credenciales
en eventos. Escribir repetido con hash diferente genera conflicto, no reemplazo silencioso.

`P2RecordingIndex` enumera todos los segmentos/rendiciones y `P2RecordingClosed` referencia su
objeto confirmado. Permite a VOD recuperar eventos faltantes del índice, comparar checksum y
distinguir fin de fuente de fin de cargas. La ubicación exacta del escritor y su recuperación
se ratifica en P2-09. Fallar captura no fabrica un objeto ni un cierre completo.

## 8. Dependencias y contratos de integración

`p2.streaming.renditions`, los eventos de sesión/snapshot y `recording.segment.available.v1` /
`recording.closed.v1`; [S3 y timeline](../contratos_p2_media.md). Core valida owner/estado actual;
VOD consume referencia e índice; Chat conserva contexto temporal actual por HTTP.

## 9. Decisiones y preguntas abiertas

P2-08: prueba real de ingestión/empaquetado multicalidad, alineación y soporte MediaMTX.
P2-09: escritor, proveedor y persistencia de trabajos. P2-10: tratamiento de huecos en timeline.
Las representaciones de segmentos e inventario están definidas sin imponer runtime nuevo.

## 10. Verificación

Usar fuente de una y cuatro calidades, verificar maestro/variantes y guardar todas. Inyectar
corte entre PUT/confirmación/evento, pérdida de evento de segmento, cierre adelantado, duplicado
y reconexión. VOD debe detectar incompletitud y recuperar desde inventario, nunca por espera fija.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. El principal riesgo es confundir soporte de ingesta multitrack con HLS multivariante.
No se acepta retirar calidades del VOD para ocultar una limitación del empaquetado.
