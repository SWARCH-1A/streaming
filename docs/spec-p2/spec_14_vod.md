# SPEC-14 — Biblioteca VOD

**Prioridad:** P2. **Responsable:** VOD. **Estado:** diseño para desarrollo.
**RF:** RF-013, 056…062, 074…075, 080…082. **RNF:** RNF-022…030, 040…051.

## 1. Contexto y problema

El propietario conserva sus emisiones y ofrece una biblioteca consultable y reproducible.
La publicación puede ser automática o manual. [Alcance](../alcance_p2.md).

## 2. Definición del componente

VOD tiene contenedor, API y persistencia privados. Posee biblioteca, metadatos, variantes,
manifiestos, estado de publicación y referencias S3. El runtime y motor de datos están pendientes.
La grabación y el servicio VOD no están implementados por esta definición.

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
para cambiar de calidad y consultar Chat. S3 externo es el almacén de medios; local usa una
implementación compatible configurable. No se fija un proveedor ni motor de metadatos nuevo.

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
asignan empaquetado, escritor/transferencia de segmentos, esquema de datos, mapping y controles
de disponibilidad a sus responsables. Los contratos proponen Core como ubicación autoritativa de la preferencia de canal; esa firma
está marcada en revisión P2-09 y requiere ratificación con VOD.

## 10. Verificación

| RF/RNF | Evidencia requerida |
| --- | --- |
| RF-056/057/059/062; RNF-022/050 | Grabación real, variantes, duración, playback y reinicio |
| RF-080/081; RNF-024/027/028 | Preferencia copiada, publicación automática/manual y permisos |
| RF-013/058/060/061/074/075 | Catálogo, búsqueda/filtros, edición, retiro y limpieza |
| RF-082; RNF-041/042/046/047 | Video y replay con mapping acordado, sin tablas compartidas |
| RNF-025…030/043…045/048/049/051 | Acceso directo restringido, fallos, datos/logs, recuperación y trabajo asíncrono |

Las pruebas deben distinguir S3 local de entrega externa y de una simulación de almacenamiento.

## 11. Esfuerzo, riesgos y consecuencias

La publicación depende de integridad de medios y mapping. La biblioteca conserva sus datos
cuando cae Streaming; las credenciales S3 y el proveedor de medios permanecen en servicios.
El borrado físico es recuperable y su atraso no restablece acceso público.
