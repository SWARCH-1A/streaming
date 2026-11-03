# SPEC-18 — Web React Router SSR

**Prioridad:** P2. **Responsable:** Web. **Estado:** implementado en [apps/web](../../apps/web/README.md); loaders consumen agregados Gateway en el perfil local P2; el adaptador Core/Streaming conserva el smoke aislado.
**RF:** Todos los RF web salvo RF-038…045. **RNF:** RNF-002, 011…014, 019…020, 023, 025…039, 046.

## 1. Contexto y problema

La navegación inicial debe entregar HTML con contenido de la plataforma. Reproductor, chat,
formularios y actualizaciones posteriores siguen siendo interactivos en el navegador.

## 2. Definición del componente

Una aplicación React/TypeScript con React Router en modo framework y runtime SSR propio.
Reutiliza módulos de apps/web; separa loaders/acciones de servidor de efectos que requieren DOM.

## 3. Historia de usuario

Como visitante, quiero recibir el canal con contenido inicial y después reproducir video,
conversar o navegar sin perder el contexto de sesión.

## 4. Alcance

Rutas de exploración, autenticación, canal, live, VOD, seguimiento, notificaciones y superficies
propietario/administración según sus permisos. Build de servidor y cliente, hidratación,
revalidación, límites de error y accesibilidad. No se exige renderizar video o una conexión WS
en el servidor.

## 5. Requisitos funcionales

- El loader obtiene un agregado de pantalla del Gateway; los comandos llaman su API de dominio.
- SSR no usa window/document/localStorage ni clientes globales con sesión mutable.
- El navegador monta HLS y WS después de hidratar; cancelar navegación elimina efectos anteriores.
- Estado de canal/conteo se revalida dentro del presupuesto de cinco segundos extremo a extremo.
- Los formularios conservan valores y muestran validación/errores sin recurrir a datos demo.

## 6. Criterios de aceptación

- [x] CA-01: HTML de `/channels/{handle}` contiene canal y contenido real sin ejecutar JavaScript; no solo un contenedor vacío.
- [x] CA-02: SSR e hidratación coinciden y no repiten carga inicial, leases ni conexiones WS.
- [x] CA-03: dos solicitudes con usuarios distintos no cruzan cookies, estado, HTML privado o errores.
- [x] CA-04: login/logout/CSRF y redirecciones funcionan por dominio HTTPS; 401 atrasado no elimina una sesión nueva.
- [x] CA-05: cambiar de sesión/ruta cierra WS y lease anterior y descarta respuestas tardías.
- [x] CA-06: fallar VOD o Chat conserva canal/player con estado de error por sección.
- [ ] CA-07: live → reconexión → fin y canal offline → live se reflejan dentro del objetivo nominal sin recargar la página.
- [ ] CA-08: teclado, foco, semántica, controles HLS/chat y navegación funcionan en Chrome/Firefox del perfil probado.

## 7. Diseño técnico y datos

El cliente HTTP recibe base URL y contexto por solicitud. Loaders trasladan cookie/identidad
únicamente al Gateway de confianza; nunca a una URL aportada por formulario o evento. El estado
serializado al navegador excluye credenciales, endpoints privados y objetos internos.

Los efectos montan player/hls.js y Chat con identidad de sesión estable. WS se abre antes de
historial y se fusiona por secuencia. Replay consulta ventanas temporales al seek y aplica
supresiones. La revalidación cancela respuestas obsoletas y mantiene datos con frescura explícita.
El HTML dependiente de identidad usa no-store; redirects y códigos HTTP conservan su significado.

`/watch/{streamId}` recibe el agregado `P2WatchScreen` del Gateway con un plazo total de
 tres segundos. El Gateway comprueba stream, sesión, canal y generación; una sesión incoherente
 da 503 y un canal ausente o suspendido oculta la pantalla con 404. Una sección no esencial
 no disponible se muestra como fallo parcial. El loader traslada únicamente las cookies permitidas
 de esa petición al upstream fijo `WEB_GATEWAY_UPSTREAM`, nunca a una URL del contenido.
 La hidratación reutiliza esos datos y revalida el agregado cada dos segundos con solicitudes
 acotadas a 2,5 s y sin solapamiento. Una denegación desmonta el player. El maestro, WS y leases
 se abren solo en el navegador.

`/` y `/search` renderizan las secciones Discovery y VOD del agregado `P2ExploreScreen`.
Los filtros de título/categoría/etiqueta viajan al Gateway; `liveCursor`, `channelCursor` y el
cursor VOD son independientes. La búsqueda de canales ejecuta la operación generada `Channels`
en paralelo a `LiveStreams`. La hidratación conserva las secciones iniciales y sus fallos
parciales; las páginas nuevas descartan resultados de los filtros anteriores.

Para el smoke aislado de watch sin Gateway se conservan `WEB_API_UPSTREAM` y
`WEB_STREAMING_UPSTREAM`: las lecturas públicas de Streaming no reciben cookies. Un canal
confirmado ausente da 404, y una dependencia de metadatos caída conserva el marco de emisión.

El build debe producir entrada de servidor, assets y comandos de arranque reproducibles.
La migración se acepta cuando las rutas existentes funcionan mediante SSR; un servidor que solo
sirve el dist estático anterior no cumple RNF-002.

## 8. Dependencias y contratos de integración

Gateway: `p2.gateway.*` y operaciones de dominio de [contratos HTTP](../contratos_p2_http.md).
Chat usa WS y frame schemas; reproducción usa manifiestos multicalidad y leases actuales.
Móvil comparte contratos, no las cookies del servidor SSR. SPEC-16 define fallos de agregación.

## 9. Decisiones y preguntas abiertas

Framework SSR seleccionado: React Router. Decisiones del responsable: runtime Node.js 24 propio
(`server.mjs` con `createRequestListener` de `@react-router/node`, HTTPS y assets estáticos), rutas en
`src/app/routes.ts` que envuelven las páginas existentes, loaders solo de lectura con cookies de
sesión/CSRF reenviadas a un upstream fijo (`WEB_API_UPSTREAM`) y revalidación en el navegador
(canal, watch y exploración cada 2 s; bandeja cada 5 s). Los comandos siguen llamando la API de dominio desde el navegador.
Los agregados se verifican con el Gateway desplegado; la aceptación completa de reconexión y Firefox conserva sus comprobaciones pendientes.

## 10. Verificación

Inspeccionar HTML sin JS, hidratar con consola limpia, navegar con y sin sesión y verificar
conexiones/solicitudes reales. Ejecutar dos usuarios en paralelo, dependencias caídas y pruebas
de teclado. Medir estado visible desde el commit, incluyendo intervalo de revalidación.
`pnpm check` verifica el HTML de producción de canal/live/offline con APIs ficticias, 404/503,
destinos separados, ausencia de cookies en Streaming y conservación del player si falla canal.
Las pruebas de React verifican hidratación del player sin errores recuperables, un único motor,
selección de calidad después del maestro y ausencia de leases antes de un frame. Esta evidencia
se complementa con Gateway desplegado y la prueba real Enhanced RTMPS → cuatro calidades
live/VOD de SPEC-04; no sustituye la cobertura Firefox ni la observación humana.

Evidencia ejecutada el 2026-11-03: `tests/integration/p2-ssr-browser/run.py` por HTTPS local,
Gateway real y Chromium: login/logout/CSRF, HTML con identidad, foco/teclado, hidratación sin
errores, un solo WS y cierre al salir; offline→live en 4,4 s y fin→offline en 2,3 s sin recargar.
El HTML inicial de inicio/búsqueda contiene los datos reales del agregado. `pnpm check` pasa
215 pruebas de 42 archivos, tipos, lint, formato y build SSR. Las pruebas del consumidor
comprueban descarte de respuestas tardías y liberación de player/lease. CA-07 conserva pendiente
la observación de reconexión completa en ese recorrido; CA-08 tiene evidencia Chromium, pero
Firefox no pudo arrancar por un fallo del perfil del navegador de pruebas.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. La dependencia de window en clientes actuales y la separación de código servidor/
navegador son riesgos principales. El despliegue necesita runtime SSR y no solo hosting estático.
