# SPEC-06 Catálogo de categorías y etiquetas P1

- **Módulo:** taxonomy
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-066…RF-069: vocabulario controlado para clasificar emisiones y permitir consultas consistentes de streams LIVE. La selección de taxonomía es compartida con Streaming y Discovery.

## 2. Definición del componente

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

- Emisiones es autoridad de las asociaciones de categoría/etiquetas de StreamConfig; Taxonomía administra definiciones/vocabulario y expone IDs/valores.

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

Implementación Core: Spring/JDBC/Flyway existentes; V3 incorpora semilla, versión automática,
protección de IDs y tombstones sin modificar V1/V2. GET usa snapshot SQL único, orden NFKC/minúsculas
e ID, `Cache-Control: no-cache`, sin ETag. [ADR-006](../adr/ADR-006-taxonomia-en-core.md)
propone las decisiones de persistencia y seguridad para revisión del equipo.

Catálogo es módulo Core, SQL con IDs/labels/activo/catalogVersion, API pública GET /api/taxonomy e interfaz local CatalogValues.find para tombstones. Catálogo escribe vocabulario Core; Streaming escribe asociaciones en su SQL privado usando validación tipada y snapshots de labels. No hay FK entre bases. Desactivar no borra IDs/labels referenciados; IDs enviados explícitamente deben estar activos, omitidos se preservan. Añadir datos servidor no requiere rebuild de Web. Catálogo no tiene proceso/DB propio. Publica contexto privado y resolución de tombstones para Streaming mediante Core.

## 8. Dependencias y contratos de integración

Streaming solicita contexto Core para validar IDs explícitos activos y del tipo correcto; campos omitidos preservan asociaciones/último label. Discovery combina catálogo local y proyección Streaming por SQL revisado. Frontend obtiene opciones por API, sin hardcode de etiquetas. Fallo SQL/Core falla explícitamente; no disponibilidad ficticia de Taxonomy independiente.

## 9. Decisiones y preguntas abiertas

**Acordado:** semilla listada arriba, una categoría, cero a cinco tags por stream, filtros solo para transmisiones reproducibles LIVE, sin subtítulos P1; el contrato permite sumar valores en backend sin reconstruir el cliente. P1 acepta un categoryId y un tagId como máximo por consulta; cuando ambos se especifican se combinan con AND. Seleccionar varios tags simultáneamente queda fuera de P1.

## 10. Verificación

Pruebas del proveedor: `infra/local/test-core.ps1`, Java 25/PostgreSQL 18 en Docker; incluyen HTTPS
privado, token/puerto público, sesión revocada/owner, IDs tipados, campos omitidos, tombstones y
fallo SQL correlacionado. El contrato con Rust se comprueba con el runner de
[tests/contracts](../../tests/contracts/README.md); persistencia con el smoke allí documentado.
Esto no cierra SPEC-06: Streaming debe demostrar asociaciones/edición LIVE (CA-02/03/05/07),
Discovery filtros exactos/AND/PLAYABLE y frescura (CA-04/05/07), y Web opciones nuevas sin rebuild
y accesibilidad (CA-06/SPEC-08). SPEC-08 espera la implementación Web.

Semilla siete categorías/ocho tags; 0/5/6 tags, categorías ausentes/inactivas y dedupe; filtros PLAYABLE exactos y AND, edición LIVE y tombstones; nueva opción servidor sin rebuild Web. Revisar propiedad local de Catálogo y contrato Core–Streaming, sin FK entre bases ni servicio Taxonomy separado.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** S/M. **Riesgos:** vocabulario divergente por hardcode duplicado, IDs inestables y discrepancias entre resultado filtrado y metadatos editados. **Consecuencia:** P1 no incluye administración dinámica, subtítulos ni indexación VOD.
