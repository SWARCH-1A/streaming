# Segmentos S3, grabación y timeline de P2

[Streaming](spec-p2/spec_04_stream.md), [VOD](spec-p2/spec_14_vod.md),
[Chat](spec-p2/spec_05_chat.md) y [schemas P2](../contracts/p2.json) comparten este contrato.
La elección de empaquetador, escritor, motor VOD y proveedor S3 local sigue en P2-08…10.

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

## Objeto confirmado y escritor

P2ObjectRef contiene objectId opaco, byteLength, sha256 y contentType. El objectId no deriva de
la clave de ingesta ni contiene paths privados, credenciales o URL arbitraria. Un prefijo S3
configurado traduce de forma determinista `objects/<objectId>` a la clave del bucket privado.
Si el responsable elige otra disposición, publica esa resolución como parte del contrato antes
de integrar escritor/lector; no obliga a intercambiar credenciales en eventos.

Solo el escritor acordado carga el objeto. Una identidad ya confirmada no se sobrescribe con
contenido distinto. Tras PUT correcto se verifica longitud/checksum según el proveedor y se
persiste la referencia/intención durable de avisar. Una confirmación perdida se recupera
consultando identidad/hash; no genera un segundo segmento lógico. Una carga fallida queda
pendiente y no publica `recording.segment.available.v1` como si existiera.

El almacenamiento final es S3 externo; un buffer temporal de captura no equivale a archivo VOD.
Local usa endpoint configurable compatible, con MinIO/RustFS como candidatos. Roles/prefijos
separan escritura de captura, lectura/publicación y limpieza. La elección del escritor no cambia
el dueño de biblioteca, que sigue siendo VOD.

## Inventario final y publicación

`P2RecordingIndex` enumera rendiciones y todos los segmentos confirmados. El índice mismo se
carga como objeto inmutable y `recording.closed.v1` lo referencia con hash/longitud, sesión,
generación y copia de autopublicación/version de preferencia.

El proceso sigue este orden: finalizar captura → confirmar cargas pendientes → confirmar índice
→ persistir intención de cierre → publicar cierre. ENDED puede precederlo. No se publica por
haber esperado una cantidad fija de segundos ni porque el broker no tenga mensajes visibles.

VOD aplica eventos recibidos y coteja el índice. Recupera referencias de eventos faltantes,
comprueba identidades/hashes, existencia, orden, duración y rendiciones. Un faltante conserva
estado VALIDATING/FAILED observable; no anuncia una grabación completa. Un evento duplicado o
cierre viejo nunca recrea un VOD eliminado. El vínculo sessionId/generación es único.

Al validar, VOD genera/valida sus manifiestos y duración. Autopublicación copiada al iniciar,
true por defecto, permite publicar; false conserva DRAFT. Cambiar la preferencia del canal no
reescribe la copia de una sesión. La ubicación autoritativa de esa preferencia se ratifica en
P2-09; los contratos proponen Core por ser configuración de canal.

## Tiempo del video y replay

`P2Timeline` tiene vodId, sessionId, mappingVersion e intervalos de sourceGeneration,
sessionStartMs, mediaStartMs y durationMs. Los intervalos se validan ordenados, sin solapamientos
en el medio y con referencias a objetos del inventario. Dentro de un intervalo:

`mediaOffsetMs = mediaStartMs + (streamOffsetMs - sessionStartMs)`.

El reloj de sesión avanza durante la gracia aunque falten bytes. No se puede tratar directamente
streamOffsetMs como tiempo de VOD. P2-10 decide si el medio conserva huecos y qué ocurre con
mensajes sin intervalo: omisión, marcador u otra política explícita. El formato permite señalar
intervalos sin imponer una de esas decisiones. No se inventa sincronización para un tramo faltante.

Replay usa fromMs inclusivo y toMs exclusivo del medio; fromMs < toMs. Orden estable por posición
y secuencia. Un seek abre otra ventana y cancela respuesta anterior. Cursores incluyen contexto
de mapping/moderación y no se reutilizan en otra ventana. Supresiones vigentes se aplican antes
de entregar mensajes; una consulta antigua no repone contenido moderado.

## Entrega, suspensión y borrado

S3 es privado. `/vod-media/{vodId}/{assetPath}` resuelve exclusivamente assets del inventario
con disponibilidad válida, conserva MIME/Range y no sigue URLs aportadas por el cliente.
Manifest master y playlists de variante usan rutas que mantienen la misma comprobación.
El contrato P2-17 debe impedir acceso directo a manifiestos, segmentos, pistas y replay después
de suspensión/retirada; ocultar enlaces o usar una URL pública permanente no lo satisface.

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
