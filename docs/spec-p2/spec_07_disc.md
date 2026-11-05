# SPEC-07 — Discovery, proyecciones y búsqueda compuesta

**Prioridad:** P2. **Responsable:** Core / Discovery. **Estado:** implementado en Core, Gateway y Web; verificado en un stack local aislado y completo con emisiones reales y grabaciones VOD reales. Queda abierto CA-04 (el presupuesto de 5 s frente al sondeo de 10 s de la vista).
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

- [x] CA-01: duplicados/versiones viejas no repiten efectos ni revierten ENDED a LIVE. (`BrokerEventIngestIT`: duplicados, versiones iguales o antiguas, redelivery concurrente y una generación ENDED que no vuelve a LIVE; `DiscoveryIngestIT`.)
- [x] CA-02: reinicio durante inbox/aplicación recupera el snapshot sin estado parcial. (Un fallo al aplicar revierte inbox y efecto juntos y la reentrega se aplica, `DiscoveryIngestIT`/`BrokerEventIngestIT`; un consumidor reiniciado reconstruye toda la proyección desde el corte, `DiscoveryReconcileIT`.)
- [x] CA-03: snapshot completo con watermark y eventos concurrentes reconstruye sin perder cambios posteriores. (`DiscoveryReconcileIT`: eventos recibidos durante el corte no se pierden ni se degradan, y una fila ausente solo se retira si es anterior al watermark.)
- [ ] CA-04: de commit a estado visible en cliente transcurren como máximo cinco segundos nominales, incluyendo broker/Gateway/polling. (Medido en el stack real con RabbitMQ: de PLAYABLE en la lectura pública de Streaming a la lista de Discovery pasan entre 76 ms y 1,1 s, y una emisión que cae sale de la lista en 412 ms. Sigue abierto porque la vista refresca cada 10 s según [SPEC-07 P1](../spec-p1/spec_07_disc.md), más que el presupuesto de 5 s: requiere decidir la cadencia de sondeo o excluirla del presupuesto.)
- [x] CA-05: fallo de broker o proyección vencida informa frescura real; no inventa OFFLINE ni mantiene PLAYABLE indefinidamente. (Stack real: con Streaming detenido la emisión deja de listarse a los 3,8 s y el canal pasa a `UNKNOWN` con `statusFresh=false`, nunca a `OFFLINE`; con el broker detenido una emisión nueva aparece igualmente por el corte de reconstrucción. `DiscoveryIT`: `aStaleStatusBecomesUnknownNeverOffline` y la ventana exacta de 5 s.)
- [x] CA-06: búsqueda VOD filtra categoría/etiqueta/título en VOD; caída conserva la sección live y señala error. (Stack real con grabaciones reales de las emisiones capturadas: el título filtra por subcadena sin distinguir mayúsculas, la categoría y la etiqueta comparan IDs exactos y se combinan con AND, otra categoría o etiqueta y un título inexistente devuelven vacío, y un filtro inválido es 4xx y no un fallo parcial. La vista «Grabaciones» usa `/api/screens/explore` con su propio cursor; con VOD detenido `explore` responde 200 parcial con `VOD_UNAVAILABLE`, la sección señala el error con reintento y las transmisiones en vivo conservan sus resultados. Pruebas del Gateway y de la vista.)
- [x] CA-07: las consultas públicas no exponen credenciales, correo o claves privadas. (`DiscoveryIT`: `aStreamExposesOnlyTheContractFields` e `internalOrPrivateFieldsCannotBeRequestedAtAll`; en el stack real, `/api/screens/explore` y las lecturas de salas no contienen correo, hashes ni credenciales.)

## 7. Diseño técnico y datos

El evento usa `DiscoverySnapshot` canónico de P1 dentro del nuevo envelope tipado. No cambia
el significado de projectionVersion, discoveryPosition ni watermark. El transporte ya no llama
los endpoints HTTPS de entrega de eventos P1. Inbox y actualizaciones se confirman antes de ACK.

En Core, BrokerEventIngestService valida y coordina una sola transacción. BrokerEventStore y
su adaptador JdbcBrokerEventStore poseen el SQL del inbox P2 y la barrera de ciclo; ProjectionStore
posee la proyección y los conflictos. Ambos repositorios participan en esa misma transacción,
incluido el bloqueo de la fila de ciclo, sin commits independientes ni SQL en el servicio de aplicación.
Los conflictos de proyección conservan los motivos CHANNEL_MISMATCH/SAME_VERSION_DIFFERENT_CONTENT;
los de la barrera de ciclo se registran como LIFECYCLE_CONFLICT. Un redrive conserva el resultado
de conflicto y no se convierte en duplicado aceptado ni en un error transitorio SQL.

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

Evidencia ejecutada el 2026-11-01 sobre `develop`:

- Core, suite completa con PostgreSQL 18 desechable: 283 pruebas unitarias y 243 de integración, 0 fallos (incluye `BrokerEventIngestIT`, `DiscoveryIngestIT`, `DiscoveryReconcileIT` y `DiscoveryIT`).
- Gateway Go: `go vet` y `go test` sin fallos, incluida `TestExploreScreen` (error parcial, consulta ajena y cursores).
- Web: 17 pruebas del módulo Discovery, entre ellas las de la sección de grabaciones y las de la vista.
- Stack local aislado y completo (los 19 servicios, con VOD, Chat y Notificaciones) con cuatro emisiones RTMPS reales y navegador: la lista de Discovery refleja PLAYABLE entre 76 ms y 1,1 s después de la lectura pública de Streaming, una caída sale en 412 ms, sin Streaming la proyección vence en 3,8 s y el canal queda `UNKNOWN`, con el broker detenido el corte de reconstrucción mantiene la frescura, y con VOD detenido la vista señala el error solo en su sección. Con grabaciones reales se probaron los filtros de título, categoría y etiqueta por el Gateway y por la interfaz.
- Suites de integración del repositorio contra ese stack: Gateway (SPEC-16, incluye `explore`), SSR con navegador, seguimiento y notificaciones, replay de chat con VOD, borde de Caddy y administración: todas pasan.

No se ha medido el perfil de carga de SPEC-13; el criterio abierto no se considera aprobado.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo M. Usar la recepción como hora de observación o mezclar versiones de ciclo/proyección
puede producir canales falsamente LIVE; las reglas de versión deben verificarse integradas.
