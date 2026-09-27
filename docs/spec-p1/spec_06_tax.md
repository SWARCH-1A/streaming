# SPEC-06 Catálogo de categorías y etiquetas P1

- **Módulo:** taxonomy
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-066…RF-069 (fuentes SRC-RF-65…SRC-RF-68): vocabulario controlado para clasificar emisiones y permitir consultas consistentes de streams LIVE. La selección de taxonomía es compartida con Streaming y Discovery.

## 2. Estado del sistema y brecha

La semilla y sus reglas aparecen completas en el catálogo local. Taxonomy ofrece los valores activos y las reglas de asociación que usan Streaming y Discovery.

## 3. Historia de usuario

Como streamer, quiero escoger una categoría y etiquetas reconocibles para mi emisión; como espectador, quiero explorar emisiones en directo con esos criterios.

## 4. Alcance

### Dentro de P1

- Semilla de categorías: Conversación, Videojuegos, Música, Arte, Educación, Ciencia y tecnología, Deportes.

- Semilla de etiquetas: Español, Inglés, Educativo, Competitivo, Casual, Principiantes, Programación, IRL.

- Una categoría activa y entre cero y cinco etiquetas activas por emisión; los IDs seleccionados provienen del catálogo controlado.

- Incorporar valores controlados nuevos en la configuración/datos del backend y devolverlos por la API sin cambiar ni reconstruir el código fuente del cliente; no se incluye UI CRUD de catálogo en P1.

- Consulta de streams LIVE por categoría/etiqueta. Cambios de metadatos hechos durante la emisión se reflejan en la consulta.

### Fuera de P1

- Subtítulos, CRUD de categorías/etiquetas por usuarios o administradores, vocabulario libre, selección simultánea de varios tags en una consulta, taxonomía de VOD y ranking personalizado.

### Supuestos acordados

- La semilla es estática/controlada en P1 y es compartida por todos los consumidores.

- Streaming es autoridad de la asociación actual entre sesión y metadatos; Taxonomía administra definiciones/vocabulario y expone IDs/valores.

## 5. Requisitos funcionales

- **RF-066:** asociar cada emisión con exactamente una categoría activa del catálogo.

- **RF-067:** asociar cero a cinco etiquetas activas, sin duplicados.

- **RF-068:** consultar emisiones LIVE por categoría.

- **RF-069:** consultar emisiones LIVE por etiqueta.

## 6. Criterios de aceptación

- **CA-01:** un cliente obtiene las siete categorías y ocho etiquetas semilla con identificadores estables para el entorno.

- **CA-02:** se rechaza categoría ausente/inactiva, etiqueta inexistente/inactiva y más de cinco etiquetas; cero etiquetas es válido.

- **CA-03:** una sesión tiene exactamente una categoría; repetir la misma etiqueta no duplica la asociación.

- **CA-04:** dado un fixture con streams LIVE coincidentes/no coincidentes, consulta por categoría devuelve exactamente los coincidentes y excluye OFFLINE, RECONNECTING y ENDED; consulta por etiqueta devuelve exactamente sesiones que contienen esa etiqueta. Etiquetas diferentes no se confunden por subcadenas.

- **CA-05:** tras confirmar una edición válida durante LIVE, la siguiente lectura pública muestra los valores nuevos; una edición inválida conserva la asociación previa.

- **CA-06:** al agregar una categoría/tag controlado del lado servidor y publicar una nueva versión de catálogo, un cliente sin cambio de código fuente obtiene el valor; no existe operación P1 de alta/edición de usuarios o administradores.
- **CA-07:** al desactivar un valor ya usado por una StreamConfig, desaparece de la lista activa y no se puede elegir en una nueva configuración/edición explícita, pero el ID y último label persisten como tombstone y Discovery reconstruye/muestra metadata existente sin perder el nombre.

## 7. Diseño técnico y datos

- Definir entidades Category(categoryId, name, active) y Tag(tagId, name, active), y la asignación vigente streamId→category/tags con integridad local. Los IDs no se reutilizan ni borran; al desactivar, conservar el último label como tombstone mientras sea referenciado.

- Un solo propietario por entidad; no permitir que Discovery o Streaming escriban el catálogo directamente. Streaming es autoridad de metadatos de emisión y Taxonomy autoridad de catálogo.

- Publicar un contrato de lectura para catálogo y criterios de filtrado; versión, normalización/case folding y formato de IDs quedan en contrato.

- `GET /api/taxonomy` devuelve ID estable, label, active=true y catalogVersion; `GET /internal/taxonomy/values/{valueId}` permite a Streaming/Discovery resolver también un valor inactivo ya referenciado. El owner actualiza catalogVersion de manera monotónica. No elegir almacén por anticipado: el owner selecciona seed/configuración o persistencia por ADR, pero la extensión server-side no requiere modificar el cliente.

## 8. Dependencias y contratos de integración

- Streaming valida y persiste asociaciones enviando IDs de catálogo; Taxonomy ofrece IDs y valores válidos.

- Discovery consume IDs y etiquetas legibles y pide resultados LIVE; no debe consultar tablas privadas de Streaming/Taxonomy.

- Frontend carga categorías/etiquetas del contrato de catálogo y muestra errores de valor inactivo o límite excedido.

- Si catálogo no responde, las ediciones no aceptan valores que no puedan validarse; una emisión ya activa conserva metadatos válidos previos.

## 9. Decisiones y preguntas abiertas

**Acordado:** semilla listada arriba, una categoría, cero a cinco tags por stream, filtros solo para transmisiones reproducibles LIVE, sin subtítulos P1; el contrato permite sumar valores en backend sin reconstruir el cliente. P1 acepta un categoryId y un tagId como máximo por consulta; cuando ambos se especifican se combinan con AND. Seleccionar varios tags simultáneamente queda fuera de P1.

## 10. Verificación

- Contrato de catálogo incluye exactamente semilla aprobada y IDs estables.

- Pruebas de cero, cinco y seis etiquetas, categoría única/ausente/inactiva y duplicados.

- Pruebas de filtros solo LIVE, cambio midstream y fallo del servicio de catálogo.

- Desactivación de valor referenciado: no selección nueva, metadata previa intacta y label disponible tras reconstruir Discovery.

- Pruebas de consumidor aseguran que Discovery y Streaming no dependen de tablas ajenas.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** S/M. **Riesgos:** vocabulario divergente por hardcode duplicado, IDs inestables y discrepancias entre resultado filtrado y metadatos editados. **Consecuencia:** P1 no incluye administración dinámica, subtítulos ni indexación VOD.
