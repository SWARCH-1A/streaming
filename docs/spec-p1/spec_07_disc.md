# SPEC-07 Descubrimiento P1

- **Módulo:** discovery
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-070…RF-073: encontrar canales y emisiones actuales. Separa búsqueda de canal público de búsqueda/filtros de streams LIVE para que OFFLINE no desaparezca de la búsqueda de canales.

## 2. Definición del componente

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

- CA-01: ranking viewerCount DESC, startedAtUtc DESC, streamId ASC; paginación con snapshot/cursor estable.
- CA-02: canales activos públicos LIVE/OFFLINE/RECONNECTING, sin datos privados.
- CA-03: coincidencia parcial NFKC/case-insensitive preserva acentos.
- CA-04: título/filtros solo PLAYABLE, excluyen OFFLINE/gracia/ENDED/VOD; IDs exactos.
- CA-05: ID desconocido/inactivo INVALID_FILTER/422 como error de campo; categoría y un tag AND.
- CA-06: p95 <=2 s en perfil P1, resultados paginados.
- CA-07: tombstone conserva label de asociación existente; no seleccionable como filtro.
- CA-08: rechazar forma/costo GraphQL (aliases/fragments/introspection, >50 filas/conexión, >100 agregado, >16 KiB), 600 req/IP/60 s burst20, 429/Retry-After. IP confiable de proxy.
- CA-09: commit de registro es puerta de visibilidad; cuenta/perfil/canal aparecen juntos, nunca PENDING/parcial. Lectura local de cuentas/canales desde SQL Core; publicación del canal no depende de eventos Streaming.
- CA-10: estado/conteo/metadata provienen de la proyección pública Streaming aplicada en SQL Core. Cambio <=5 s con presupuesto observación/publicación <=2 s y entrega/aplicación <=3 s. viewerCountFresh=false si observación >5 s; estado no confirmado/fuera de frescura se excluye de streams y se muestra UNKNOWN en canales. ENDED no es PLAYABLE; inbox/versiones y snapshot con watermark permiten recuperación comprobable.

## 7. Diseño técnico y datos

Discovery es módulo de consultas Core, sin autoridad de escritura de negocio. Posee inbox y tablas SQL de proyección pública de Streaming en PostgreSQL Core. GraphQL se conserva en /api/discovery/graphql; resolvers usan SQL/vistas/read models revisados, sin N+1 de red. DTO públicos no proyectan credenciales. Consultas paginadas y acotadas, cursor atado a filtro/snapshot; si se materializa snapshot de ranking local, declarar retención/expiry. Discovery combina la proyección Streaming con Cuentas/Canales/Catálogo locales; no consulta bases privadas ni HTTP por fila. Outbox/inbox, frescura y reconstrucción consistente se definen en contratos. Índices SQL se eligen con medición; un índice especializado futuro exige ADR y reconstrucción completa con watermark.

## 8. Dependencias y contratos de integración

Lecturas locales de Cuentas/Canales/Catálogo y proyección recibida de Streaming Rust. Estado Media y conteo de leases son autoridad Streaming; solo datos públicos llegan por eventos. Web consume GraphQL; proxy sobrescribe IP. Excepción de consulta afecta endpoint; caída de Core afecta todas sus APIs, se documenta ese alcance.

## 9. Decisiones y preguntas abiertas

GraphQL, búsqueda, filtros, ranking, frescura y límites según el contrato. Canal visible desde commit local; datos de emisión mediante snapshots versionados de Streaming. Discovery permanece dentro de Core según ADR-005.

**Decisiones técnicas ([ADR-008](../adr/ADR-008-descubrimiento-en-core.md), aceptada):** `graphql-java` con controlador propio para controlar códigos HTTP y límites; los snapshots de Streaming se reciben y aplican en una sola transacción y solo una `projectionVersion` mayor reemplaza la fila; una reconstrucción periódica con watermark repara la proyección y acredita la ausencia de configuración; el ranking de `streams` pagina sobre un snapshot materializado de 5 min; un limitador por IP en memoria aplica 600/60 s con burst de 20 (una réplica en P1). Los errores de campo responden HTTP 200 con `extensions.httpStatus=422`; forma o costo excedidos, 422.

**Preguntas abiertas no bloqueantes:** carga del corte periódico sobre Streaming si crece el número de configuraciones; reloj compartido entre Streaming y Core para la frescura; limitador compartido si Core se replica.

## 10. Verificación

- Datos de fixture con LIVE/OFFLINE, categorías/tags, títulos con subcadenas y contadores con empates.

- Pruebas de comportamiento anónimo, normalización, combinaciones de filtros, estado actualizado y exclusión de VOD; duplicados/desorden/conflictos, caída del productor/consumidor, atraso y reconstrucción concurrente de proyección con watermark.

- Validar el schema GraphQL, límites/errores, paginación, empates y payload sin campos privados.

- Medición bajo perfil P1 de SPEC-13: 5 streams y 100 viewers; comprobar p95 ≤2 s y matching exacto del conjunto esperado.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. **Riesgos:** ranking obsoleto, proyección inconsistente, búsqueda de canal que omita offline y páginas que cambien bajo orden dinámico. **Consecuencia:** no hay descubrimiento VOD ni personalización en P1.
