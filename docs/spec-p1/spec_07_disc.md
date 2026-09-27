# SPEC-07 Descubrimiento P1

- **Módulo:** discovery
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-070…RF-073: encontrar canales y emisiones actuales. Separa búsqueda de canal público de búsqueda/filtros de streams LIVE para que OFFLINE no desaparezca de la búsqueda de canales.

## 2. Estado del sistema y brecha

El catálogo y el registro de decisiones fijan el alcance P1. La semántica del API y los datos consultados están en los contratos del proyecto.

## 3. Historia de usuario

Como espectador, quiero descubrir streams activos por popularidad, título, categoría o etiquetas y localizar canales públicos aunque estén offline.

## 4. Alcance

### Dentro de P1

- Listar transmisiones LIVE reproducibles ordenadas por espectadores concurrentes descendentes. Los empates ordenan por inicio más reciente y luego streamId ascendente; OFFLINE/RECONNECTING no aparecen como reproducibles.

- Buscar canales por coincidencia parcial, sin distinguir mayúsculas/minúsculas, en handle o nombre visible; incluir canales LIVE y OFFLINE y mostrar estado actual.

- Buscar por coincidencia parcial de título de stream; solo LIVE reproducible.

- Filtrar streams LIVE reproducibles por ID exacto de categoría o tag controlado. La búsqueda de título usa coincidencia parcial normalizada; la identidad de tags/categorías no usa subcadenas. P1 acepta como máximo un categoryId y un tagId por consulta; cuando ambos se especifican se combinan con AND. No hay selección simultánea de varios tagIds en P1.

- Datos de consulta reflejan cambios de título/categoría/etiquetas y conteo de espectadores dentro de los límites de frescura aceptados.

### Fuera de P1

- Búsqueda VOD, historial de canales, recomendaciones personalizadas, relevancia ML, filtros de idioma/ubicación, selección simultánea de varios tagIds y ordenamientos alternativos.

### Supuestos acordados

- El visitante anónimo puede consultar Discovery.

- “Parcial” significa subcadena sobre el texto normalizado; especificar orden de resultados cuando no sea el listado de popularidad en el contrato.

- La interfaz pública de Discovery usa GraphQL sobre HTTP/JSON según el contrato P1. El lenguaje, framework y runtime se eligen y justifican mediante ADR; otros dominios no quedan obligados a usar GraphQL.

## 5. Requisitos funcionales

- **RF-070:** listar streams LIVE reproducibles por conteo de espectadores descendente.

- **RF-071:** buscar canales públicos LIVE/OFFLINE por coincidencia parcial en handle/nombre visible y devolver estado.

- **RF-072:** buscar streams LIVE por coincidencia parcial en título.

- **RF-073:** filtrar streams LIVE por categoría y/o etiqueta.

## 6. Criterios de aceptación

- **CA-01:** un stream reproducible con más espectadores antecede a otro con menos; empate de viewerCount ordena `startedAtUtc DESC`, luego `streamId ASC`. La paginación conserva esta clave total y declara snapshot/cursor.

- **CA-02:** consulta de canales devuelve resultados LIVE y OFFLINE y estado sin exponer email u otros datos privados.

- **CA-03:** búsqueda de canal y título coincide con subcadena en cualquier posición y no distingue mayúsculas.

- **CA-04:** búsqueda de título/filtro no incluye OFFLINE, RECONNECTING, ENDED ni VOD; un fixture de LIVE con categoría/tag coincide solo cuando su ID iguala el filtro y los LIVE con otro valor quedan excluidos.

- **CA-05:** categoría/tag desconocido o inactivo produce un error del campo `streams` con `extensions.code=INVALID_FILTER` y `extensions.httpStatus=422`; no se interpreta como texto libre. Si la consulta incluye una categoría y un tag, ambos filtros se aplican con AND.

- **CA-06:** las consultas de lista/búsqueda aplican RNF-011: p95 ≤ 2 s bajo la carga objetivo del prototipo; la respuesta está paginada y no devuelve resultados ilimitados.
- **CA-07:** si una categoría/tag asociado a una configuración existente se vuelve inactivo, Discovery conserva y reconstruye su label para esa metadata; no incluye ese valor en el catálogo de filtros activos ni acepta ese ID como filtro.
- **CA-08:** GraphQL rechaza antes de ejecutar aliases, fragments, introspection, más de 50 filas por conexión, costo agregado mayor a 100 o body mayor a 16 KiB; rate limit acepta 600 requests/IP/60 s con burst 20 y reporta 429/Retry-After. El perfil de carga nominal de 3 consultas/s por una misma IP queda por debajo del límite.
- **CA-09:** `ChannelProvisioned` por sí solo nunca crea un resultado público ni permite que un canal PENDING/EXPIRED aparezca en `channels`. Solo después de `IdentityPublicChanged` para una identidad ACTIVE, Discovery confirma userId/handle con Identity, obtiene el canal y perfil públicos y crea el documento de canal aunque esté OFFLINE. Si llegan primero cambios/versiones del canal, se reconcilian sin hacer visible el canal antes de la activación; reordenar provisión, activación y cambios no crea duplicados ni expone una cuenta pendiente.
- **CA-10:** Discovery recibe `ViewerCountChanged` con eventId, streamId, sessionId, streamGeneration, countVersion, viewerCount y observedAtUtc desde `aggregateId=viewer-count:{sessionId}`. Solo aplica countVersion mayor a la sesión/generación vigente; recuento cambia en la consulta dentro de 5 s bajo operación normal, se conserva el último valor para ranking con `viewerCountFresh=false` después de 5 s sin snapshot, y un evento de sesión ENDED no la resucita. Al reconstruir, carga el snapshot de la sesión en Streaming.

## 7. Diseño técnico y datos

- Discovery es dueño de índice/proyección de consulta, no de canales, streams, conteo ni taxonomía de origen.

- Mantener documentos de canal por cada canal ACTIVE aunque esté OFFLINE, separados de documentos de emisión LIVE/PLAYABLE por sesión. La metadata/título/categoría/tags autoritativos viven en StreamConfig/streamId y se preservan entre sesiones. `channelVersion` monotónica proviene de Channels; metadataVersion y sessionVersion provienen de Streaming.

- Usar `IdentityPublicChanged` como única puerta de publicación de una identidad y sus recursos. Solo después del evento, consultar `GET /api/identity/public/users/{userId}`, Channels y Profile para crear el documento público de canal; `ChannelProvisioned` es interno y no se consume como señal de publicación. Channels aporta channelId; Profile displayName/avatar; Streaming sesión/metadata/viewers; Taxonomy labels por lookup interno, incluido tombstone inactivo si ya está asociado. Ninguno expone email ni información privada.

- Aplicar `ChannelChanged` solo a un documento cuya identidad ya fue activada; un cambio recibido antes de la activación puede quedar pendiente de reconciliación, pero no debe crear una fila pública.
- Contrato GraphQL sobre HTTP/JSON usa `POST /api/discovery/graphql`, con consultas `streams` y `channels`; el schema, variables, normalización, paginación, filtros combinables, campos, frescura y errores están definidos en `contratos_modelo_datos.md`.
- Aplicar el límite de forma uniforme; el reverse proxy confía solo en su propia IP reenviada y elimina headers de forwarding suministrados por navegador.

- Evitar saltos de páginas inestables cuando cambie viewerCount; el responsable selecciona cursor/snapshot y registra trade-off en ADR.

## 8. Dependencias y contratos de integración

- Identity aporta userId/handle canónico al activar la cuenta y para reconciliación; Channels aporta channelId y Profile displayName/avatar. Ninguno expone información privada.

- Streaming aporta sesiones LIVE, título, categoría, tags, fecha inicio y viewerCount, con señal autoritativa de reproducibilidad.
- Streaming publica snapshots de conteo al cambiar (coalescidos a máximo uno por segundo), al iniciar PLAYABLE y cada 5 s; Discovery versiona por countVersion y lleva frescura separada de statusFresh.

- Taxonomy valida/describe categorías y etiquetas.

- Frontend y reverse proxy comparten ruta API, parámetros URL y comportamiento de error/empty/loading.

- Si una dependencia está atrasada/no disponible, indicar la frescura y degradar la consulta según política del contrato; no presentar OFFLINE como LIVE.

## 9. Decisiones y preguntas abiertas

**Acordado:** listado reproducible por viewerCount DESC, tie-break startedAtUtc DESC y streamId ASC; búsqueda parcial de handle/displayName y título; canales LIVE/OFFLINE/RECONNECTING con estado; títulos/filtros solo sobre sesiones PLAYABLE; VOD excluido de P1. Categoría+un tag se combinan por AND y no hay selección simultánea de varios tags. El documento de canal solo se publica tras activación ACTIVE en Identity; `ChannelProvisioned` no basta. Cursor/snapshot e índice se eligen por ADR sin alterar este orden lógico.

## 10. Verificación

- Datos de fixture con LIVE/OFFLINE, categorías/tags, títulos con subcadenas y contadores con empates.

- Pruebas de comportamiento anónimo, normalización, combinaciones de filtros, estado actualizado y exclusión de VOD.

- Validar el schema GraphQL, límites/errores, paginación, empates y payload sin campos privados.

- Medición bajo perfil P1 de SPEC-13: 5 streams y 100 viewers; comprobar p95 ≤2 s y matching exacto del conjunto esperado.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. **Riesgos:** ranking obsoleto, proyección inconsistente, búsqueda de canal que omita offline y páginas que cambien bajo orden dinámico. **Consecuencia:** no hay descubrimiento VOD ni personalización en P1.
