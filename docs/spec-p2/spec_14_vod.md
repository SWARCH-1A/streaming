# SPEC-14 — Biblioteca VOD

**Prioridad:** P2. **Responsable:** VOD. **Estado:** implementación VOD disponible; aceptación integrada Media/Chat pendiente.
**RF:** RF-013, 056…062, 074…075, 080…082. **RNF:** RNF-022…030, 040…051.

## 1. Contexto y problema

El propietario conserva sus emisiones y ofrece una biblioteca consultable y reproducible.
La publicación puede ser automática o manual. [Alcance](../alcance_p2.md).

## 2. Definición del componente

VOD tiene contenedor, API y persistencia privados. Posee biblioteca, metadatos, variantes,
manifiestos, estado de publicación y referencias S3. Usa Go 1.26 y PostgreSQL propio; local usa
MinIO, con endpoint S3 externo configurable. [ADR-022](../adr/ADR-022-vod-go-postgresql-minio.md)
fija almacenamiento, escritor, mapping y autorización. Código y operación en [services/vod](../../services/vod/README.md).

## 3. Historia de usuario

Como propietario, quiero conservar todas las calidades emitidas y publicar automáticamente
o revisar la grabación antes de hacerla visible. Como espectador, quiero encontrarla y reproducirla.

## 4. Alcance

Grabación automática, todas las variantes recibidas, asociación al canal/sesión, catálogo,
búsqueda/filtros, duración, edición, publicación, reproducción y eliminación manual. Integra
replay por contrato con Chat. Las políticas de subtítulos corresponden a su responsable.

## 5. Requisitos funcionales

- RF-013/058/074/075: catálogo de contenido publicado/disponible, búsqueda y filtros.
- RF-056/057/062: conservar la sesión y todas sus variantes, origen y duración comprobable.
- RF-059/060/061: reproducir, editar por propietario y eliminar manualmente.
- RF-080: preferencia de autopublicación por canal, activada por defecto y copiada al iniciar sesión.
- RF-081: grabación validada sin autopublicación queda pendiente de publicación manual.
- RF-082: conservar vínculo y mapping para obtener replay de Chat, sin copiar su propiedad.
- Suspensión del propietario o retirada administrativa impide acceso directo y desde catálogo.

## 6. Criterios de aceptación

- [ ] CA-01: una sesión con varias variantes genera un VOD asociado al mismo canal/sesión;
  una reconexión dentro de la gracia conserva esa identidad.
- [ ] CA-02: cada aviso de segmento referencia una carga confirmada en S3; reentrega/desorden
  no duplica objetos lógicos ni reemplaza una generación vigente por otra antigua.
- [ ] CA-03: todas las calidades recibidas quedan reproducibles en VOD, con selección manual y
  automática; no se ofrece una calidad que el emisor no produjo.
- [ ] CA-04: autopublicación por defecto publica solo después de finalizar y validar. Desactivarla
  conserva la grabación pendiente; cambiar la preferencia no altera la copia de una sesión iniciada.
- [ ] CA-05: el propietario edita/publica/elimina; un tercero no. Contenido pendiente no aparece
  en búsquedas públicas ni se reproduce mediante URL directa.
- [ ] CA-06: catálogo y filtros devuelven resultados paginados de VOD disponibles, con título,
  categoría/etiquetas y duración; no hay consulta a tablas privadas ajenas.
- [ ] CA-07: eliminar retira el acceso y dispara limpieza recuperable de variantes/pistas/replay;
  reintentar la limpieza no recupera publicación ni elimina otro VOD.
- [ ] CA-08: suspensión oculta canal y recursos VOD también por enlace directo. Reactivar
  respeta el estado de publicación y las retiradas previas.
- [ ] CA-09: reinicios conservan biblioteca, referencias y trabajos pendientes. Segmentos faltantes
  impiden presentar una grabación completa; el fallo no revierte el live.

## 7. Diseño técnico y datos

Identidad mínima: `vodId`, `channelId`, `ownerUserId`, `streamId`, `sessionId`, generaciones,
variantes, referencias a objetos, duración, metadatos, preferencia copiada y versión. Estado
de procesamiento, estado de publicación y restricción de acceso son conceptos separados;
suspender no convierte un borrador en publicado al reactivar.

El vínculo sesión/generación es único; DELETED conserva un tombstone y no se recrea por un
cierre atrasado. El cierre incluye índice final de objetos confirmados; VOD lo coteja y recupera
referencias ausentes, sin inferir completitud de ENDED. Ver [medio/S3/timeline](../contratos_p2_media.md).

El manifiesto conserva el origen temporal de cada variante y las discontinuidades necesarias
para cambiar de calidad y consultar Chat. VOD escribe objetos privados y Media entrega sus bytes por HTTP privado con identidad S3 de solo
lectura. El índice recupera avisos faltantes y confirma inventario antes de READY. Los huecos
conocidos se compactan; Chat coloca mensajes de esos huecos en la unión, manteniendo secuencia.
Cada manifiesto consulta Core; segmentos validan permiso firmado de 60 segundos y disponibilidad
local. Web renueva a los 45 segundos sin reiniciar HLS.

La API debe cubrir lectura propia/pública, catálogo paginado, búsqueda/filtros, edición,
publicación, eliminación y acceso a medios. Los métodos/rutas/schemas `p2.vod.*` están en [contratos HTTP](../contratos_p2_http.md)
y [contracts/p2.json](../../contracts/p2.json). El Gateway compone esos contratos sin consultar
la base VOD. Las operaciones asíncronas devuelven statusUri para consultar su resultado.

## 8. Dependencias y contratos de integración

| Dependencia | Contrato | Fallo |
| --- | --- | --- |
| Streaming / Media | Sesión, variantes, segmentos confirmados, fin y mapping | Trabajo reintentable; ausencia de segmento no implica grabación completa |
| Core | Propiedad, estado de cuenta/canal, autorización y preferencia | Rechazar operación/acceso que no pueda validarse |
| Chat | Vínculo temporal y consulta replay, moderación, limpieza | Video y panel de chat presentan fallos separados |
| S3 | Objetos y manifiestos privados con identidad estable | Reintento y estado observable |
| Gateway | Agregados de canal, reproducción y búsqueda | Indisponibilidad de sección sin falso catálogo vacío |

## 9. Decisiones y preguntas abiertas

Independencia de VOD, S3, variantes completas, autopublicación configurable, publicación manual
y eliminación manual están definidas. [P2-08…10 y P2-17](../decisiones_pendientes_p2.md)
mantienen pendientes el empaquetado/cierre real multicalidad de Media y la persistencia/replay
durable de Chat. P2-09 queda resuelta en ADR-022; Core es autoridad de la preferencia y Streaming
conserva su copia/version por sesión. P2-10 y P2-17 quedan resueltas para mapping y acceso VOD;
la aceptación transversal continúa por sus responsables.

## 10. Verificación

| RF/RNF | Evidencia requerida |
| --- | --- |
| RF-056/057/059/062; RNF-022/050 | Grabación real, variantes, duración, playback y reinicio |
| RF-080/081; RNF-024/027/028 | Preferencia copiada, publicación automática/manual y permisos |
| RF-013/058/060/061/074/075 | Catálogo, búsqueda/filtros, edición, retiro y limpieza |
| RF-082; RNF-041/042/046/047 | Video y replay con mapping acordado, sin tablas compartidas |
| RNF-025…030/043…045/048/049/051 | Acceso directo restringido, fallos, datos/logs, recuperación y trabajo asíncrono |

Las pruebas distinguen PostgreSQL/MinIO reales, medios H264 generados por FFmpeg, autoridades
simuladas y servicios Core reales. La suite VOD ejecutada comprueba cuatro rendiciones, índices
con avisos omitidos, compactación, Range206 sin Core por segmento, permisos, idempotencia, WebVTT,
reinicio SQL, objeto faltante y limpieza fallida/reanudada; `VodAccessIT` comprueba el contexto
Core privado. El runner nativo añade RTMP real H264/AAC, dos generaciones, reinicio de captura,
seis archivos/~10s confirmados y HLS final; retirar captura mantiene el publisher y limpia el
buffer, mientras SIGKILL sin sello permanece FAILED. Core/Streaming son puertos de prueba en ese
recorrido. Son evidencias parciales, no aceptación OBS ni S3 externo.

El grabador mantiene buffer durable y exige cierre final por generación. La entrada actual de
una calidad usa el inventario nativo de MediaMTX de ADR-022: confirma cada archivo y sella al
drenar el último. ENDED/SIGKILL sin sello no publica; `CAPTURE_FINAL_INDEX_UNAVAILABLE` conserva
el fallo. El prefijo PREPARING se conserva sin desplazar el reloj Chat del timeline público.
P2-08 debe entregar las cuatro calidades reales. CA-01…CA-09 permanecen abiertos
hasta las verificaciones integradas; Chat debe acreditar replay, orden en la unión y limpieza.
La selección Web, renovación programada y controles de pistas requieren además prueba audiovisual
larga, teclado/lector y las plataformas reales. Ver comandos en [VOD](../../services/vod/README.md).

## 11. Esfuerzo, riesgos y consecuencias

La publicación depende de integridad de medios y mapping. La biblioteca conserva sus datos
cuando cae Streaming; las credenciales S3 y el proveedor de medios permanecen en servicios.
El borrado físico es recuperable y su atraso no restablece acceso público.
