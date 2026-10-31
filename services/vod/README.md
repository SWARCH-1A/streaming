# VOD

Implementación de [SPEC-14](../../docs/spec-p2/spec_14_vod.md), [ADR-022](../../docs/adr/ADR-022-vod-go-postgresql-minio.md)
y pistas de [SPEC-22](../../docs/spec-p2/spec_22_subtitles.md). Los schemas y rutas ejecutables están
en `contracts/p2.json`; TypeScript/Kotlin se regeneran con las herramientas compartidas.

## Arranque local

Desde la raíz del repositorio, con Docker:

```sh
python3 infra/local/dev.py init
python3 infra/local/dev.py build
python3 infra/local/dev.py up
```

`init` añade credenciales independientes y certificados sin rotar los existentes. Los volúmenes
`vod-db`, `vod-s3`, `media-native-recordings` y `media-recordings` conservan datos. No se publican puertos SQL/S3/privados.
La entrada pública Caddy sirve `/api/vods`, `/api/me/vods` y `/vod-media`; Web ofrece `/videos`,
`/studio/vods` y `/vod/:vodId`. La biblioteca de canal usa el agregado SSR de Gateway.

| Proceso | Puertos privados | Datos |
| --- | --- | --- |
| vod | 8098 API y 8099 servicio, ambos TLS | PostgreSQL propio; escritura S3 |
| media-recorder | 8890 TLS entrega privada | Buffer durable en disco y lectura S3 |
| vod-db / minio | 5432 / 9000 TLS | Metadatos / objetos privados |

El grabador es un worker técnico de Media empaquetado en la misma imagen Go. No consulta tablas
Streaming: usa snapshots privados de sesión y el inventario nativo privado de Media. Captura y
transferencia usan loops separados; una caída de VOD o RabbitMQ no modifica el live. El buffer
predeterminado es 8 GiB (`RECORDER_SPOOL_MAX_BYTES`). Al llenarse, deja fallo observable y no publica
una grabación completa. Los checkpoints y archivos se escriben con fsync y rename; el aviso conserva
su ID y bytes al reintentar. Tras confirmar cierre y ACK, elimina los archivos y conserva tombstone.

La imagen MediaMTX se compila desde 1.21.1/commit fijado en ADR-022 con una adaptación pequeña y
comprobada: inventario sincrónico de archivos creados/completos y sello al drenar el grabador.
Media entrega el binding sesión/generación en la autorización privada, ligado al publisher;
el motor lo consume antes de crear el grabador. No se persiste la clave
de emisión. La entrada actual H264/AAC conserva una calidad real sin recodificar, incluido el
tramo final. El prefijo PREPARING se conserva en video; el timeline coloca el cero de Chat en LIVE.
El snapshot privado incluye `initialSourceGeneration`: la captura conserva ese límite y la última
generación en el checkpoint, sin asumir que una nueva sesión empieza en 1 ni omitir generaciones faltantes.
El buffer nativo tiene otra cota de 8 GiB (`MEDIA_RECORDING_MAX_BYTES`, MediaMTX); su error afecta
la grabación, no el lector live. El capturador copia/fsync antes de retirar cada archivo nativo.

`RECORDER_CAPTURE_MODE=native` es el predeterminado y `RECORDER_NATIVE_DIR=/data/native` contiene
inventarios/bytes de Media. Al recibir ENDED espera hasta 30 s por el sello real; el plazo no crea
un cierre. Un archivo pendiente, falta de generación o SIGKILL sin sello produce FAILED con
`CAPTURE_FINAL_INDEX_UNAVAILABLE`. Un reinicio interno del grabador marca fallo explícito.
Al cerrar/ACK se limpia el buffer y se conserva el tombstone. La alternativa `hls` exige maestro
real, `PROGRAM-DATE-TIME`, rendiciones alineadas y ENDLIST de cada generación; no basta su ventana.

**Dependencia P2-08:** OBS → cuatro calidades reales → selección/reconexión necesita la entrega
multicalidad de Media. La captura nativa de una calidad no anuncia ni fabrica otras variantes.

El motor requiere `MEDIA_NATIVE_RECORDING=true` y `MTX_PATHDEFAULTS_RECORD=true` con MPEG-TS,
partes de 200 ms, archivos de 2 s y sin caducidad automática. El adaptador Rust usa
`MEDIA_RECORDING_ENABLED=true` para emitir las dos cabeceras de `P2MediaRecordingBinding`.
Si faltan o son inválidas, el motor omite la grabación y mantiene independiente el live.
Un410 confirmado por VOD persiste un marcador de retirada en Media: el lector de grabación se
detiene al siguiente flush, drena y permite limpiar el buffer sin desconectar al publisher.
El marcador se conserva para no recrear archivos en una reconexión tardía. Un fallo de limpieza
mantiene referencias/checkpoint y reintenta; no descarta su inventario antes de retirar los archivos.

## Configuración y S3 externo

Las variables se inyectan por Compose; no se guardan secretos en el repositorio.

- `VOD_DATABASE_URL`: PostgreSQL con TLS verificado. Migraciones embebidas en orden, checksum y lock.
- `VOD_CORE_URL`, `VOD_CORE_TOKEN`: contexto privado Core con credencial exclusiva VOD.
- `VOD_CAPTURE_TOKEN`, `VOD_CHAT_TOKEN`, `VOD_GATEWAY_TOKEN`: identidades distintas del listener 8099.
- `VOD_SIGNING_SECRET`: al menos 32 bytes; conservar para permisos/cursores. Permisos de 60 s.
- `VOD_MEDIA_URL`, `MEDIA_VOD_DELIVERY_TOKEN`: entrega privada Media con credencial exclusiva.
- `VOD_S3_ENDPOINT`, `VOD_S3_BUCKET`, `VOD_S3_PREFIX`, `VOD_S3_REGION`: endpoint HTTPS/bucket privados.
- `VOD_S3_ACCESS_KEY`, `VOD_S3_SECRET_KEY`: escritor/limpieza; `MEDIA_S3_ACCESS_KEY`,
  `MEDIA_S3_SECRET_KEY`: lector separado. Valores explícitamente vacíos usan la cadena IAM del SDK;
  para roles, declarar también los secretos vacíos en un overlay Compose.
- `VOD_BROKER_URL`/`RECORDER_BROKER_URL`: AMQPS con roles VOD/Streaming de ADR-016; CA confiable.
- `VOD_PUBLIC_ORIGIN`: origen exacto de mutaciones cookie, doble CSRF. Bearer nativo conserva la
  revocación Core. `VOD_DEVELOPMENT_HTTP` solo permite fixtures explícitos sin TLS.

Local usa bucket `streaming-vod`, prefijo `vod/objects`. El inicializador crea usuarios `vod`
(PUT/GET/DELETE) y `media-reader` (GET), distintos del root de MinIO; bucket sin acceso anónimo.
Para S3 externo, provisionar bucket/roles equivalentes y configurar ambos procesos. El servicio
no genera ni expone URLs S3. Los bytes siempre pasan por Media y el inventario autorizado VOD.
No se ejecutaron pruebas contra S3 externo en esta entrega.

## Salud y recuperación

- `/healthz` comprueba proceso; `/readyz` comprueba SQL/S3 en un presupuesto de 3 s. En Media,
  `/healthz` comprueba S3. El endpoint de servicio `/metrics` exige identidad Gateway y expone
  `vod_jobs_pending`, `vod_outbox_pending`, `vod_objects_unconfirmed`.
- `GET /api/vods/operations/{id}` exige propietario o administrador solicitante. COMPLETED acredita
  limpieza local; `replayCleanup=DELEGATED_TO_CHAT` conserva visible la dependencia de Chat.
- Las pistas huérfanas sin asociación se detectan a los 30 minutos y se limpian con jobs; se
  revalida la asociación bajo lock antes de borrar. Un reintento conserva identidad y puede
  restaurar una carga cuya asociación nunca se confirmó.
- Los jobs se reclaman con SKIP LOCKED y lease de 2 min; FAILED reintenta a los 30 s. Las operaciones
  de objetos se serializan con cargas en curso. Reiniciar VOD recupera SQL/inbox/outbox/jobs.
- Los consumidores ACK tras commit, publican retries1/5/30 y DLQ con confirmación; reconectan si
  falla una suscripción. Redrive y métricas del broker: [runbook](../../infra/broker/README.md).
- Respaldar SQL y S3 de forma coordinada; conservar el spool si hay cargas sin confirmar. No
  truncar inventario ni borrar tombstones para resolver un job fallido. Revisar código de fallo,
  permisos, CA y presencia/hash de objetos antes de reintentar. Los logs no muestran credenciales.

## Verificación reproducible

Desde la raíz, con Docker, el runner crea PostgreSQL/MinIO/RabbitMQ aislados, sin puertos
publicados, y elimina exclusivamente sus propios contenedores/volúmenes etiquetados:

```sh
python3 tests/integration/p2-vod/run.py
```

Este fixture usa HTTP/AMQP en su red desechable. No acredita TLS extremo a extremo ni modifica
la instalación local del usuario. El despliegue normal exige TLS y credenciales independientes.

Con Go1.26, FFmpeg/ffprobe, una base **aislada cuyo nombre contenga `vod_test`** y un MinIO de
fixture (credenciales ficticias de `integration_test.go`):

```sh
cd services/vod
go test -count=1 -v ./...
# Con VOD_TEST_DATABASE_URL y VOD_TEST_S3_ENDPOINT definidos, ejecuta también integración real.
# Sin esas variables, integración se omite explícitamente.
```

`Dockerfile.test` proporciona Go/FFmpeg. La integración limpia únicamente la base de fixture y un
bucket aleatorio; nunca apuntarla a una instalación con datos. Los tests usan la referencia de
Media HTTP real y no sustituyen MinIO/PostgreSQL por mocks; Core se sustituye por un puerto de
prueba, y sus permisos reales se comprueban aparte con `VodAccessIT` en Core.

Evidencia ejecutada en esta entrega: Go VOD con PostgreSQL18/MinIO/RabbitMQ 4.3.7 y segmentos H264 TS/fMP4 generados por
FFmpeg de una/cuatro dimensiones; índice final con eventos de segmentos omitidos, compactación,
Range206 sin llamadas Core por segmento, suspensión local, borradores, versiones/idempotencia,
conflicto de bytes, reapertura de SQL, objeto faltante, WebVTT real y limpieza fallida/reanudada.
Son medios de fixture; no una emisión OBS multicalidad ni una prueba S3 externa. Las suites Gateway,
Core, contratos y Web se documentan en sus respectivos componentes. Los CA permanecen abiertos
hasta completar la evidencia audiovisual y las dependencias de Media/Chat.

El runner también ejecuta RTMP real H264/AAC contra MediaMTX fijado: dos generaciones, inventario
nativo completo, reinicio del capturador antes de subir, confirmación HTTP/S3/SQL/AMQP, validación
y HLS VOD con ENDLIST/discontinuidad. Se comprobaron seis archivos y aproximadamente10s de medio,
el cero LIVE conservando el prefijo PREPARING, la retirada de captura con publisher aún conectado
y limpieza, y SIGKILL sin sello dejando FAILED. VOD permanece ausente durante las emisiones de
esta fase. La autorización Core/Streaming y los snapshots/ACK son puertos simulados; el adaptador
Rust comprueba por separado las cabeceras privadas. La red de este fixture usa RTMP/HTTP/AMQP,
no acredita RTMPS/HTTPS extremo a extremo ni replay real de Chat. El detector de carreras se
ejecutó sobre las pruebas del capturador; la integración nativa se ejecutó con la infraestructura real.

También se ejecutó un smoke de arranque nativo TLS: PostgreSQL con verify-full, bucket MinIO
privado inicializado con escritor/lector distintos, y salud HTTPS de VOD y Media. Core, Streaming
y broker estuvieron ausentes en ese smoke; no acredita el recorrido completo TLS. Se validaron
Compose/Caddy con certificados de fixture sin iniciar ni modificar el stack del usuario.
