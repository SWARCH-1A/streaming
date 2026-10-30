# ADR-022: VOD con Go, PostgreSQL y objetos privados S3

- Estado: aceptada para la implementación VOD por instrucción del usuario
- Fecha: 2026-10-29
- Responsable: VOD / integración con Streaming, Media, Core, Chat y Web
- SPEC/contratos: SPEC-14, SPEC-22, P2-09, P2-10 y P2-17; `p2.vod.*`, `p2.recording.*`, `p2.media.vod-object`
- Amplía: [ADR-015](ADR-015-arquitectura-p2.md) y [ADR-018](ADR-018-subtitulos-vod-webvtt.md)

## Contexto

La biblioteca necesita conservar identidad por sesión, inventario, borradores, versiones y trabajos
tras reinicios. Los avisos del broker pueden llegar duplicados, desordenados o perderse antes de su
publicación. ENDED no acredita que todos los bytes hayan llegado. El acceso no puede depender de
URLs S3 públicas ni de consultar Core por cada segmento.

## Decisión

`services/vod` es un proceso Go 1.26 con HTTP estándar, pgx, minio-go y amqp091-go. Usa PostgreSQL
propio con migraciones embebidas, checksum y lock; no accede a bases ajenas. Separa procesamiento,
publicación y restricciones. Conserva tombstones, inventario lógico, snapshot original, inbox/outbox,
auditoría de comandos y trabajos con reintento. La respuesta idempotente y el cambio comparten commit.

VOD es el único escritor S3. Media captura en un volumen durable, transfiere bytes por HTTP privado
y anuncia la referencia solo después de recibir confirmación de VOD: reserva SQL → PUT S3 →
verificación de tamaño/SHA-256 → confirmación SQL. La identidad es sesión/rendición/generación/
secuencia; bytes distintos para la misma identidad generan conflicto. Locks por VOD serializan las
escrituras de objetos con la limpieza; una caída libera el lock y deja la reserva recuperable.

La entrada de una calidad graba MPEG-TS H264/AAC sin recodificar desde MediaMTX 1.21.1,
fijado al commit `048255986f7e04b859b4c4efe651448ec785ecd4`. Una adaptación comprobada durante el
build registra sincrónicamente creación, bytes/checksum confirmados y cierre de cada generación
en un volumen exclusivo de Media. La autorización privada devuelve sesión/generación en cabeceras,
ligadas al ID del publisher y consumidas una vez antes de crear el grabador. La ruta usa
`sesión/generación/rend_source`, sin persistir la clave de ingesta ni cambiar configuración durante
la autorización. Los hooks externos
asíncronos de [MediaMTX](https://github.com/bluenviron/mediamtx/blob/v1.21.1/internal/core/path.go)
no certifican ese orden. Un fallo/reinicio interno del grabador conserva un fallo; SIGKILL no
produce un cierre. La cola de captura copia con fsync antes de liberar los bytes nativos.

Se conservan también los bytes de PREPARING. Solo los segmentos de generación 1 pueden tener
`sessionStartMs` negativo, acotado a 120 segundos; el índice los valida como cobertura continua.
Los intervalos públicos de sesión siguen siendo no negativos y empiezan en el instante LIVE,
con `mediaStartMs` posterior al prefijo PREPARING. Chat no desplaza su reloj ni genera mensajes
para ese prefijo. Esta decisión conserva todos los bytes sin confundir los orígenes temporales.

Media tiene otra identidad S3, de solo lectura. VOD autoriza la ruta pública y resuelve el inventario;
Media entrega el objeto por HTTP privado conservando MIME, Range/206 y cancelación. Ni navegador ni
móvil reciben bucket, credenciales o URLs prefirmadas. El endpoint externo S3 se configura sin cambiar
los contratos. Local usa MinIO compilado desde las fuentes oficiales fijadas a
[RELEASE.2025-10-15T17-29-55Z](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)
y [mc RELEASE.2025-08-13T08-35-41Z](https://github.com/minio/mc/releases/tag/RELEASE.2025-08-13T08-35-41Z).
El build evita depender de una imagen binaria pública que no estuvo disponible en este entorno.

Core conserva la preferencia autoritativa por canal. Streaming consulta `vod-settings` al reservar
una sesión nueva y conserva valor/versión; una reconexión reutiliza la copia. Una consulta fallida
rechaza el inicio sin asumir `enabled=true`. VOD solo publica tras índice final confirmado,
comprobación de inventario/hashes, decodificación, duración y cobertura de todas las rendiciones.
El índice recupera avisos faltantes; la ausencia de un objeto no se interpreta como un hueco legítimo.

El video compacta exclusivamente huecos conocidos, con discontinuidades explícitas. `P2Timeline`
publica intervalos y `mappingVersion`; los mensajes en huecos se sitúan en la unión siguiente, o el
último milisegundo reproducible si no existe tramo siguiente. Chat ordena esos mensajes por su
secuencia original, conserva mensajes/moderación y realiza replay/limpieza. El contrato no migra la
propiedad de Chat ni acredita su persistencia durable, aún pendiente en P2-10.

Cada manifiesto se autoriza en Core. Los segmentos comprueban un permiso firmado con propósito,
VOD, versiones de recurso/mapping/cuenta y vencimiento de **60 segundos**, más disponibilidad local.
Web renueva a los 45 segundos sin reiniciar HLS. Los eventos de cuenta se aplican por versión e
invalidan permisos y disponibilidad; retirada/eliminación invalida por versión del VOD antes del 202.
Si no se puede verificar Core, no se concede ni renueva un permiso. Hasta recibir un evento, un
permiso ya concedido tiene como cota su vencimiento; no se promete revocación instantánea distribuida.

Las pistas siguen el perfil `p2-webvtt-text-v1` de ADR-018: carga manual, duración/mapping autoritativos,
objetos privados, límites y tombstones. No se añade transcripción ni otro perfil.

## Opciones consideradas

- Incorporar VOD a Core: contradice la independencia aceptada y acopla trabajo audiovisual al Core.
- Rust o Java independientes: viables; Go conserva un despliegue pequeño y adaptadores HTTP/S3/AMQP
  sin compartir tipos internos con otros lenguajes.
- Solo broker o almacenamiento en memoria: no conserva biblioteca, versiones ni reservas tras caída.
- Media como escritor S3: exige entregar permisos de escritura a captura y otro protocolo de
  confirmación. Se adopta la decisión explícita del usuario de centralizar escritura en VOD.
- Conservar silencios del reloj live en el video: requiere generar medio para huecos sin fuente.
  Se adopta compactación y mapping explícito; no se inventan bytes ni variantes.
- Una llamada Core por segmento: amplifica el tráfico; se usa permiso corto y proyección local.

## Consecuencias y aceptación

Hay que respaldar PostgreSQL y objetos juntos, conservar secretos de firma/cursores y observar jobs,
outbox y spool. DELETE elimina acceso antes de la limpieza; el estado distingue PENDING/RUNNING/
FAILED/COMPLETED de objetos locales y declara `replayCleanup=DELEGATED_TO_CHAT`.

La captura nativa acredita el cierre de la entrada actual de una calidad. El adaptador HLS
alternativo exige fechas de programa, metadatos reales y ENDLIST de todas las generaciones;
una ventana live sola no certifica el tramo final. `CAPTURE_FINAL_INDEX_UNAVAILABLE` impide
publicar si falta evidencia de cierre. La entrega real de cuatro calidades sigue en P2-08;
no se anuncian ni generan variantes adicionales. La aceptación de Chat, S3 externo y reproducción
audiovisual larga se ejecuta por separado de las pruebas con MinIO y medios de fixture.

La evidencia y los comandos reproducibles están en [VOD](../../services/vod/README.md).
