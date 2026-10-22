# SPEC-07 — Discovery, proyecciones y búsqueda compuesta

**Prioridad:** P2. **Responsable:** Core / Discovery. **Estado:** diseño para desarrollo.
**RF:** RF-010…012, 068…075. **RNF:** RNF-011, 014, 023, 025, 032, 038…042, 046…050.

## 1. Contexto y problema

La búsqueda live debe conservar proyecciones consistentes con Streaming, mientras VOD mantiene
su catálogo propio. La entrada compuesta debe indicar qué parte está desactualizada o caída.

## 2. Definición del componente

Módulo SQL/GraphQL de Core. Mantiene inbox y proyección pública Streaming; recibe por broker y
reconcilia por snapshots HTTP. Gateway compone sus resultados con búsqueda de VOD.

## 3. Historia de usuario

Como espectador, quiero buscar canales, emisiones y grabaciones disponibles con filtros claros.

## 4. Alcance

Búsqueda live/canal y filtros base, recepción broker, reconstrucción, frescura y composición VOD.
No añade tablas VOD a Core ni consultas entre bases o un motor de búsqueda seleccionado.

## 5. Requisitos funcionales

- Mantener los filtros/paginación/orden de [SPEC-07 base](../spec-p1/spec_07_disc.md).
- Aplicar snapshots públicos completos por versión, con inbox en la misma transacción.
- Excluir streams sin PLAYABLE confirmado y mostrar UNKNOWN cuando el estado no es fresco.
- Buscar VOD por su API paginada; su fallo no elimina resultados live válidos.
- No autorizar mensajes, edición o acceso restringido usando la proyección pública.

## 6. Criterios de aceptación

- [ ] CA-01: duplicados/versiones viejas no repiten efectos ni revierten ENDED a LIVE.
- [ ] CA-02: reinicio durante inbox/aplicación recupera el snapshot sin estado parcial.
- [ ] CA-03: snapshot completo con watermark y eventos concurrentes reconstruye sin perder cambios posteriores.
- [ ] CA-04: de commit a estado visible en cliente transcurren como máximo cinco segundos nominales, incluyendo broker/Gateway/polling.
- [ ] CA-05: fallo de broker o proyección vencida informa frescura real; no inventa OFFLINE ni mantiene PLAYABLE indefinidamente.
- [ ] CA-06: búsqueda VOD filtra categoría/etiqueta/título en VOD; caída conserva la sección live y señala error.
- [ ] CA-07: las consultas públicas no exponen credenciales, correo o claves privadas.

## 7. Diseño técnico y datos

El evento usa `DiscoverySnapshot` canónico de P1 dentro del nuevo envelope tipado. No cambia
el significado de projectionVersion, discoveryPosition ni watermark. El transporte ya no llama
los endpoints HTTPS de entrega de eventos P1. Inbox y actualizaciones se confirman antes de ACK.

Un evento de ciclo de sesión puede invalidar elegibilidad vieja por sessionVersion/generación,
pero no reemplaza un snapshot más nuevo con un contador anterior. La reconstrucción sigue
recibiendo eventos y aplica solo versiones pertinentes tras el corte consistente.

El presupuesto completo no se satisface con cinco segundos de backend más cinco del cliente.
La edad se calcula desde observación del productor, sin rejuvenecerla al consultar el agregado.
El API VOD tiene su cursor/filtros; no se mezclan cursores de almacenes distintos.

## 8. Dependencias y contratos de integración

GraphQL base `/api/discovery/graphql`; `stream.discovery.snapshot.v1`, `core.discovery` y
`core.lifecycle`; snapshot privado Streaming; `p2.vod.list` y `p2.gateway.explore`.
Ver [eventos P2](../contratos_p2_eventos.md) y [HTTP](../contratos_p2_http.md).

## 9. Decisiones y preguntas abiertas

No se selecciona nuevo índice. El producto del broker y cadencias se cierran con P2-07/06
manteniendo el presupuesto completo. VOD y Gateway definen integración visual sin trasladar
propiedad de metadatos a Core.

## 10. Verificación

Probar desorden entre ciclo/snapshot, estado vencido, ausencia de configuración, reconstrucción
con cambios simultáneos y caída de VOD. Medir frescura desde el commit y repetir filtros/cursores
base contra datos reales.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo M. Usar la recepción como hora de observación o mezclar versiones de ciclo/proyección
puede producir canales falsamente LIVE; las reglas de versión deben verificarse integradas.
