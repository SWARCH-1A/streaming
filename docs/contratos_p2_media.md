# Segmentos S3, grabación y timeline de P2

[Streaming](spec-p2/spec_04_stream.md), [VOD](spec-p2/spec_14_vod.md),
[Chat](spec-p2/spec_05_chat.md) y [schemas P2](../contracts/p2.json) comparten este contrato.
ADR-022 fija escritor VOD, Go/PostgreSQL, MinIO local y mapping. La entrega real del empaquetado
y cierre multicalidad sigue en P2-08; el replay durable, en Chat/P2-10.

## Rendiciones e identidad

Una sesión admite de una a cuatro rendiciones codificadas en origen. renditionId es estable
durante la grabación; ancho/alto, bitrate y códecs describen contenido realmente recibido.
Todas comparten sesión, cupo y referencia temporal. No se anuncia una resolución no recibida.
La entrada multitrack no acredita por sí sola salida HLS multivariante.

La identidad de segmento es `(sessionId,renditionId,sourceGeneration,sequence)`. sequence ordena
segmentos dentro de su fuente/rendición; sourceGeneration evita colisiones al reconectar.
sessionStartMs y durationMs describen su intervalo de sesión; discontinuity identifica ruptura
que debe reflejar el empaquetado. Audio, keyframes y límites de segmento deben permitir cambiar
de calidad sin saltos incoherentes. Esas propiedades requieren prueba audiovisual real.
Solo la generación inicial de la sesión admite posiciones negativas hasta -120000 ms para conservar medios
PREPARING anteriores al reloj LIVE; no se admiten tiempos negativos en los intervalos públicos.
`sourceGeneration` es monotónica por streamId: una sesión nueva puede comenzar por encima de 1.
El prefijo PREPARING no lleva discontinuidad; el índice comprueba que pertenece a la generación inicial.

## Objeto confirmado y escritor

P2ObjectRef contiene objectId opaco, byteLength, sha256 y contentType. El objectId no deriva de
la clave de ingesta ni contiene paths privados, credenciales o URL arbitraria. Un prefijo S3
configurado traduce de forma determinista `objects/<objectId>` a la clave del bucket privado.
La disposición aceptada en ADR-022 es `<prefix>/objects/<objectId>`. No se intercambian credenciales
en eventos. VOD es escritor/verificador y Media entrega bytes con una identidad S3 de solo lectura.

Solo el escritor acordado carga el objeto. Una identidad ya confirmada no se sobrescribe con
contenido distinto. Tras PUT correcto se verifica longitud/checksum según el proveedor y se
persiste la referencia/intención durable de avisar. Una confirmación perdida se recupera
consultando identidad/hash; no genera un segundo segmento lógico. Una carga fallida queda
pendiente y no publica `recording.segment.available.v1` como si existiera.

El almacenamiento final es un bucket S3 privado (MinIO local o endpoint externo configurado);
un buffer temporal de captura no equivale a archivo VOD.
Local usa MinIO y endpoint configurable compatible (ADR-022). Roles/prefijos
separan escritura de captura, lectura/publicación y limpieza. La elección del escritor no cambia
el dueño de biblioteca, que sigue siendo VOD.

## Transferencia privada implementada

`p2.recording.start` registra el snapshot; PUT de objetos recibe bytes, Content-Length,
X-Object-SHA256, Content-Type e Idempotency-Key. Los segmentos incluyen X-Segment-Metadata,
base64url del schema P2SegmentUploadMetadata: identidad, tiempos, discontinuidad e inicialización.
Esos metadatos quedan ligados transaccionalmente a la identidad inmutable. La clave identifica segmento por
rendición/generación/secuencia, inicialización por rendición/generación o índice final. Solo la
respuesta200 posterior a PUT/verificación/confirmación habilita publicar su referencia. Misma
identidad con otros bytes da409. El índice también se confirma por este protocolo.

Streaming ofrece snapshots/ACK privados; Media mantiene checkpoint/bytes/outbox con fsync.
El snapshot incluye `initialSourceGeneration`, copiada de la reserva original e inmutable al
reconectar, y `sourceGeneration` vigente. Media conserva ambos límites en el checkpoint y exige
el cierre de todas las generaciones de ese intervalo, incluso si empieza a capturar tras una
reconexión. No exige generaciones de sesiones anteriores ni deduce el inicio de los archivos presentes.
La extensión privada `media.recording-binding.v1` de la respuesta204 de `media.auth` devuelve
`X-Media-Recording-Session` y `X-Media-Recording-Source-Generation` según `P2MediaRecordingBinding`.
Solo se emite para publicación autorizada con grabación habilitada. El engine liga el binding al
publisher autenticado, lo consume una vez y deriva una ruta de sesión/generación sin la clave.
El binding en memoria caduca a los dos minutos; no sustituye el inventario durable del grabador.
`p2.media.vod-object` entrega por HTTP privado exclusivamente IDs de inventario resueltos por VOD,
con tamaño/tipo/hash y Range. Los listeners no se enrutan desde Internet. Los contratos concretos
y cabeceras están en `contracts/p2.json` y [operación VOD](../services/vod/README.md).

La captura predeterminada usa el inventario nativo de MediaMTX fijado en ADR-022: cada archivo
MPEG-TS creado queda registrado antes de confirmar su checksum, y la generación se sella después
de drenar el grabador. Es un archivo privado de Media, no una base compartida con VOD. El worker
verifica/copia bytes y conserva el inventario al reintentar. Una muerte del proceso no certifica
cierre. La alternativa HLS exige fechas de programa, metadatos reales y ENDLIST; su ventana
efímera no prueba el último segmento. La entrega real multicalidad sigue en P2-08.

## Inventario final y publicación

`P2RecordingIndex` enumera rendiciones y todos los segmentos confirmados. El índice mismo se
carga como objeto inmutable y `recording.closed.v1` lo referencia con hash/longitud, sesión,
generación. La copia de autopublicación/version se conserva en el snapshot inicial inmutable de sesión.

El proceso sigue este orden: finalizar captura → confirmar cargas pendientes → confirmar índice
→ persistir intención de cierre → publicar cierre. ENDED puede precederlo. No se publica por
haber esperado una cantidad fija de segundos ni porque el broker no tenga mensajes visibles.

VOD aplica eventos recibidos y coteja el índice. Recupera referencias de eventos faltantes,
comprueba identidades/hashes, existencia, orden, duración y rendiciones. Un faltante conserva
estado VALIDATING/FAILED observable; no anuncia una grabación completa. Un evento duplicado o
cierre viejo nunca recrea un VOD eliminado. El vínculo sessionId/generación es único.

Al validar, VOD genera/valida sus manifiestos y duración. Autopublicación copiada al iniciar,
true por defecto, permite publicar; false conserva DRAFT. Cambiar la preferencia del canal no
reescribe la copia de una sesión. Core conserva la preferencia autoritativa; Streaming consulta al iniciar y conserva la copia/versión
por sesión, incluyendo reconexiones. Fallar esa consulta rechaza el inicio, sin asumir true.

## Tiempo del video y replay

`P2Timeline` tiene vodId, sessionId, mappingVersion e intervalos de sourceGeneration,
sessionStartMs, mediaStartMs y durationMs. Los intervalos se validan ordenados, sin solapamientos
en el medio y con referencias a objetos del inventario. Dentro de un intervalo:

`mediaOffsetMs = mediaStartMs + (streamOffsetMs - sessionStartMs)`.

Los bytes PREPARING retenidos preceden al primer intervalo: ese intervalo puede comenzar en
un `mediaStartMs` positivo, con `sessionStartMs=0`. No hay mensajes para el prefijo; el offset cero
de Chat se sitúa en ese inicio LIVE. A partir de allí, los intervalos cubren todo el medio restante.

El reloj de sesión avanza durante la gracia aunque falten bytes. No se puede tratar directamente
streamOffsetMs como tiempo de VOD. ADR-022 resuelve P2-10: el video compacta huecos conocidos con discontinuidades. Los mensajes sin
intervalo se colocan en el inicio del tramo siguiente; los posteriores al último tramo, en su último
milisegundo reproducible. Chat conserva su secuencia original para desempatar en la unión. Un
objeto esperado faltante impide READY; no se convierte en hueco compactable.

Replay usa fromMs inclusivo y toMs exclusivo del medio; fromMs < toMs. Orden estable por posición
y secuencia. Un seek abre otra ventana y cancela respuesta anterior. Cursores incluyen contexto
de mapping/moderación y no se reutilizan en otra ventana. Supresiones vigentes se aplican antes
de entregar mensajes; una consulta antigua no repone contenido moderado.

## Entrega, suspensión y borrado

Las pistas de SPEC-22 se preparan contra el tiempo del medio final, con mappingVersion y duración
autoritativos. Una revisión del mapping invalida su lectura/listado; no se reutiliza directamente
el reloj live ni se corrigen offsets en silencio. VOD confirma pista/objeto y permisos antes de
anunciar READY. Los [contratos HTTP](contratos_p2_http.md#subtitulos-vod) tienen proveedor VOD y
controles Web; el checker offline sigue sin acreditar reproducción audiovisual.

S3 es privado. `/vod-media/{vodId}/{assetPath}` resuelve exclusivamente assets del inventario
con disponibilidad válida, conserva MIME/Range y no sigue URLs aportadas por el cliente.
Manifest master y playlists de variante usan rutas que mantienen la misma comprobación.
Cada manifiesto se autoriza con Core. El permiso firmado dura60s y Web lo renueva a los45s; cada
segmento comprueba versión/mapping/cuenta, vencimiento y disponibilidad local sin consultar Core.
Eventos por versión invalidan ante suspensión; retirada/eliminación invalidan localmente antes
de responder202. Hasta recibir un evento, el permiso queda acotado por su vencimiento. Las pistas
comprueban también Core; Chat debe aplicar disponibilidad/contexto al replay.

DELETE retira publicación/acceso y conserva tombstone/version antes de encolar limpieza.
La limpieza idempotente cubre variantes, manifiestos, índice, pistas y replay, sin borrar objetos
de otra sesión. Su estado puede consultarse mediante la operación del dueño. Repetir DELETE
no restaura contenido ni crea otro VOD. Un fallo de limpieza se observa y reintenta; no devuelve
el recurso a catálogo. Reactivación de cuenta no deshace borrados ni retiradas administrativas.

## Validación semántica

Los schemas validan forma y ejemplos. Las pruebas de productor/consumidor verifican también
un solo cupo, máximo cuatro IDs de rendición distintos, keyframes/audio, duración/orden,
checksum real, cobertura del inventario, mapping sin solapamientos y acceso por URL directa.
Se prueban segmentos repetidos/desordenados, evento faltante con objeto existente, objeto
faltante con evento existente, cierre adelantado, desconexión/reconexión y reinicio de VOD.
