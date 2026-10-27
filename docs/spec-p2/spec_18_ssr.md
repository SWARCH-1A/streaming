# SPEC-18 — Web React Router SSR

**Prioridad:** P2. **Responsable:** Web. **Estado:** implementado en [apps/web](../../apps/web/README.md); loaders leen las APIs públicas de Core y Streaming mientras no exista el Gateway (SPEC-16).
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
- [ ] CA-04: login/logout/CSRF y redirecciones funcionan por dominio HTTPS; 401 atrasado no elimina una sesión nueva.
- [ ] CA-05: cambiar de sesión/ruta cierra WS y lease anterior y descarta respuestas tardías.
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

`/watch/{streamId}` entrega emisión, sesión y canal en el HTML inicial. El loader tiene un
plazo total de tres segundos y obtiene sesión/canal concurrentemente tras resolver la emisión.
Verifica identidad de stream, sesión, canal y generación; una sesión incoherente o no disponible
da 503, y una emisión inexistente da 404. Un fallo del canal conserva la emisión y el player.
Las lecturas públicas usan `WEB_STREAMING_UPSTREAM` sin cookies; Core usa `WEB_API_UPSTREAM`
con las cookies permitidas de esa petición. Ambos destinos son configuración de servidor.
Si hay una entrada común de API, omitir `WEB_STREAMING_UPSTREAM` usa `WEB_API_UPSTREAM`.
Hidratación reutiliza los datos de la emisión y el canal; el polling de emisión/conteo arranca
a los cuatro segundos. El maestro HLS y las opciones de calidad se descubren en el navegador,
sin inicializar motores, conexiones WS ni leases durante SSR.

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
(canal cada 4 s, bandeja cada 5 s). Los comandos siguen llamando la API de dominio desde el navegador.
CA-04/05/07/08 conservan el comportamiento P1 y quedan por verificar con el Gateway desplegado.

## 10. Verificación

Inspeccionar HTML sin JS, hidratar con consola limpia, navegar con y sin sesión y verificar
conexiones/solicitudes reales. Ejecutar dos usuarios en paralelo, dependencias caídas y pruebas
de teclado. Medir estado visible desde el commit, incluyendo intervalo de revalidación.
`pnpm check` verifica el HTML de producción de canal/live/offline con APIs ficticias, 404/503,
destinos separados, ausencia de cookies en Streaming y conservación del player si falla canal.
Las pruebas de React verifican hidratación del player sin errores recuperables, un único motor,
selección de calidad después del maestro y ausencia de leases antes de un frame. Esta evidencia
no sustituye las verificaciones pendientes de Gateway ni la ingesta multicalidad real de P2-08.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. La dependencia de window en clientes actuales y la separación de código servidor/
navegador son riesgos principales. El despliegue necesita runtime SSR y no solo hosting estático.
