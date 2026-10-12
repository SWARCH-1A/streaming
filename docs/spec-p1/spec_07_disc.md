# SPEC-07 Descubrimiento P1

- **Módulo:** discovery
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Discovery permite encontrar canales y emisiones actuales, según RF-070…RF-073. Distinguimos dos
búsquedas: los canales públicos se pueden encontrar aunque estén OFFLINE, mientras que la búsqueda
y los filtros de emisiones ofrecen solo streams LIVE reproducibles.

## 2. Definición del componente

Discovery es un módulo Core con una API GraphQL. Consulta datos locales de cuentas, canales y
catálogo, junto con la proyección pública que recibe de Streaming. El catálogo y el documento de
alcance fijan qué consultas entran en P1; los contratos definen sus datos, filtros y errores.

## 3. Historia de usuario

Como espectador, quiero descubrir streams activos por popularidad, título, categoría o etiquetas y localizar canales públicos aunque estén offline.

## 4. Alcance

### Dentro de P1

- Listar transmisiones LIVE reproducibles ordenadas por espectadores concurrentes descendentes. Los empates ordenan por inicio más reciente y luego streamId ascendente; OFFLINE/RECONNECTING no aparecen como reproducibles.

- Buscar canales por coincidencia parcial, sin distinguir mayúsculas/minúsculas, en handle o nombre visible; incluir canales LIVE y OFFLINE y mostrar estado actual.

- Buscar por coincidencia parcial de título de stream; solo LIVE reproducible.

- Filtrar streams LIVE reproducibles por ID exacto de categoría o tag controlado. La búsqueda de título usa coincidencia parcial normalizada; la identidad de tags/categorías no usa subcadenas. P1 acepta como máximo un categoryId y un tagId por consulta; cuando ambos se especifican se combinan con AND. No hay selección simultánea de varios tagIds en P1.

- Datos de consulta reflejan cambios de título/categoría/etiquetas y conteo de espectadores dentro de los límites de frescura aceptados.

- Las tarjetas de emisiones LIVE muestran un fotograma real mediante `previewUri`, una ruta pública del mismo origen `/hls/{sessionId}/preview.jpg`. GraphQL entrega la URL sin consultar Media por fila; el JPEG se solicita por HTTP. Si aún no hay fotograma o falla su descarga, la tarjeta usa el fondo gráfico del sistema y vuelve a intentarlo; conserva título, canal y estado real. La vista actualiza el listado cada 10 s y las imágenes cada 15 s, con reintento de imagen a los 5 s tras error.

### Fuera de P1

- Búsqueda VOD, historial de canales, recomendaciones personalizadas, relevancia ML, filtros de idioma/ubicación, selección simultánea de varios tagIds y ordenamientos alternativos.

### Supuestos acordados

- El visitante anónimo puede consultar Discovery.

- “Parcial” significa subcadena sobre el texto normalizado; especificar orden de resultados cuando no sea el listado de popularidad en el contrato.

- La interfaz pública de Discovery usa GraphQL sobre HTTP/JSON según el contrato P1. ADR-008 justifica su implementación dentro de Core. Los demás dominios pueden conservar sus interfaces REST o WebSocket.

## 5. Requisitos funcionales

- **RF-070:** listar streams LIVE reproducibles por conteo de espectadores descendente.

- **RF-071:** buscar canales públicos LIVE/OFFLINE por coincidencia parcial en handle/nombre visible y devolver estado.

- **RF-072:** buscar streams LIVE por coincidencia parcial en título.

- **RF-073:** filtrar streams LIVE por categoría y/o etiqueta.

## 6. Criterios de aceptación

- CA-01: los resultados se ordenan por viewerCount DESC, startedAtUtc DESC y streamId ASC. La paginación usa un snapshot y cursor estables.
- CA-02: se devuelven los canales públicos de cuentas activas, incluidos LIVE, OFFLINE y RECONNECTING, sin datos privados.
- CA-03: la búsqueda encuentra subcadenas normalizadas a NFKC, sin distinguir mayúsculas y conservando las diferencias de acentos.
- CA-04: la búsqueda por título y los filtros devuelven solo PLAYABLE. Excluyen OFFLINE, la gracia, ENDED y VOD. Los filtros comparan IDs exactos.
- CA-05: un ID desconocido o inactivo produce el error de campo INVALID_FILTER/422. Si se envían una categoría y una etiqueta, se combinan con AND.
- CA-06: las consultas devuelven resultados paginados y cumplen p95 <=2 s bajo el perfil P1.
- CA-07: un tombstone conserva el nombre de una asociación existente, pero no puede seleccionarse como filtro.
- CA-08: se rechazan aliases, fragments, introspection y consultas de más de 50 filas por conexión, 100 en total o 16 KiB de cuerpo. La cuota admite 600 solicitudes por IP en 60 s, con burst de 20; al superarla devuelve 429 con Retry-After. Se usa la IP confiable del proxy.
- CA-09: commit de registro es puerta de visibilidad; cuenta/perfil/canal aparecen juntos, nunca PENDING/parcial. Lectura local de cuentas/canales desde SQL Core; publicación del canal no depende de eventos Streaming.
- CA-10: estado/conteo/metadata provienen de la proyección pública Streaming aplicada en SQL Core. Cambio <=5 s con presupuesto observación/publicación <=2 s y entrega/aplicación <=3 s. viewerCountFresh=false si observación >5 s; estado no confirmado/fuera de frescura se excluye de streams y se muestra UNKNOWN en canales. ENDED no es PLAYABLE; inbox/versiones y snapshot con watermark permiten recuperación comprobable.
- CA-11: un valor de búsqueda `q` que contenga NUL en `streams` o `channels` produce `INVALID_FILTER` con `fieldErrors.q=INVALID_TEXT` (HTTP 200 y `extensions.httpStatus=422`), sin ejecutar la consulta del campo ni impedir resultados de otros root fields independientes.

- CA-11: cada resultado LIVE expone `previewUri` derivada de sessionId, sin stream key, usuario, contraseña ni URI privada MediaMTX. La tarjeta muestra el JPEG real cuando está disponible; ausencia/error muestra el fondo gráfico sin simular una señal. Las consultas HTTP de imagen no cuentan como espectadores ni abren leases.

## 7. Diseño técnico y datos

Discovery es módulo de consultas Core, sin autoridad de escritura de negocio. Posee inbox y tablas SQL de proyección pública de Streaming en PostgreSQL Core. GraphQL se conserva en /api/discovery/graphql; resolvers usan SQL/vistas/read models revisados, sin N+1 de red. DTO públicos no proyectan credenciales. Consultas paginadas y acotadas, cursor atado a filtro/snapshot; si se materializa snapshot de ranking local, declarar retención/expiry. Discovery combina la proyección Streaming con Cuentas/Canales/Catálogo locales; no consulta bases privadas ni HTTP por fila. Outbox/inbox, frescura y reconstrucción consistente se definen en contratos. Índices SQL se eligen con medición; un índice especializado futuro exige ADR y reconstrucción completa con watermark.

## 8. Dependencias y contratos de integración

Lecturas locales de Cuentas/Canales/Catálogo y proyección recibida de Streaming Rust. Estado Media y conteo de leases son autoridad Streaming; solo datos públicos llegan por eventos. Web consume GraphQL; proxy sobrescribe IP. Excepción de consulta afecta endpoint; caída de Core afecta todas sus APIs, se documenta ese alcance.

## 9. Decisiones y preguntas abiertas

GraphQL, búsqueda, filtros, ranking, frescura y límites según el contrato. Canal visible desde commit local; datos de emisión mediante snapshots versionados de Streaming. Discovery permanece dentro de Core según ADR-005.

**Decisiones técnicas ([ADR-008](../adr/ADR-008-descubrimiento-en-core.md), aceptada):** `graphql-java` con controlador propio para controlar códigos HTTP y límites; los snapshots de Streaming se reciben y aplican en una sola transacción y solo una `projectionVersion` mayor reemplaza la fila; una reconstrucción periódica con watermark repara la proyección y acredita la ausencia de configuración; el ranking de `streams` pagina sobre un snapshot materializado de 5 min; el limitador por IP compartido en SQL Core aplica 600/60 s con burst de 20 en todas las réplicas, según ADR-014. Usa HMAC por contexto, bloqueo por bucket, tiempo SQL y ventana móvil; una falla del almacén devuelve error GraphQL 503 sin admitir una consulta sin cuota. Los errores de campo responden HTTP 200 con `extensions.httpStatus=422`; forma o costo excedidos, 422.

**Preguntas abiertas no bloqueantes:** carga del corte periódico sobre Streaming si crece el número de configuraciones; reloj compartido entre Streaming y Core para la frescura.

## 10. Verificación

- Datos de fixture con LIVE/OFFLINE, categorías/tags, títulos con subcadenas y contadores con empates.

- Pruebas de comportamiento anónimo, normalización, combinaciones de filtros, estado actualizado y exclusión de VOD; duplicados/desorden/conflictos, caída del productor/consumidor, atraso y reconstrucción concurrente de proyección con watermark.

- Validar el schema GraphQL, límites/errores, paginación, empates y payload sin campos privados.
- En `streams` y `channels`, enviar `q` con NUL al inicio, en medio y al final; verificar `INVALID_FILTER`, `httpStatus=422`, `fieldErrors.q=INVALID_TEXT`, que el root field afectado no tenga datos y que otros root fields independientes conserven sus datos.

- Medición bajo perfil P1 de SPEC-13: 5 streams y 100 viewers; comprobar p95 ≤2 s y matching exacto del conjunto esperado.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. Debemos cuidar la frescura del ranking, la consistencia de la proyección y la
estabilidad de las páginas cuando cambia el orden de popularidad. La búsqueda de canales debe
seguir incluyendo los que estén offline.

El descubrimiento de VOD y la personalización quedan fuera de P1.
