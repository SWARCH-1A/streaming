# ADR-013: Web integrada con Caddy y reproducción HLS en navegador

- Estado: aceptada
- Fecha: 2026-10-08
- Responsable: Web / integración
- SDD/contratos afectados: SPEC-01, SPEC-03, SPEC-04, SPEC-05, SPEC-06, SPEC-07, SPEC-08, SPEC-12/13

## Contexto

La base ADR-007 usa fixtures en memoria. P1 requiere una SPA que consuma servicios reales bajo un
origen, refresque rutas profundas y mantenga independientes Chat y reproducción. Watch recibe un
channelId de Streaming; necesita una consulta pública de Channels por ID sin escanear Discovery.

## Decisión

Web usa cookies HttpOnly del servidor, CSRF por operación Core y clientes same-origin. Los tipos
TypeScript y validaciones JSON provienen del contrato canónico generado; Ajv 2020 valida respuestas
antes de publicarlas a las vistas. Consultas independientes se ejecutan en paralelo; cambios de
recurso cancelan lecturas y descartan respuestas tardías. Las escrituras no se reintentan solas.

Caddy sirve el build estático y enruta APIs exactas, WS y HLS; no autoriza casos de negocio. No hay
fallback HTML para APIs, realtime, HLS ni rutas privadas. El socket observado reemplaza forwarded IP;
Core confía únicamente en el proxy configurado. No se registran cuerpos, queries ni headers secretos.
HTTP local es un perfil explícito de desarrollo; no acredita el despliegue HTTPS de SPEC-13.

El player usa HLS nativo cuando existe y carga hls.js dinámicamente en los demás navegadores con
MediaSource. Controles HTML nativos, autoplay muted opcional y gesto del usuario si el navegador lo
rechaza. Solo el frame decodificado abre un lease; pausa, error, cambio de sesión y salida lo cierran.
La expiración del servidor limita resultados de cierre/creación desconocidos. Tokens viven en memoria.
Chat abre WS antes de solicitar historial, fusiona por secuencia y conserva clientMessageId al
reintentar mensajes con resultado ambiguo. Una caída Chat no desmonta el player.

Channels publica GET /api/channels/{channelId} con el bootstrap existente, composición local y el
mismo batch Streaming/404/UNKNOWN. Bootstrap conserva HTTP 200; la SPA normaliza casing con history replace, sin atribuirle un status HTTP 308 que un router cliente no puede emitir. La consulta es pública para cuentas activas; no escribe ni agrega
campos de Accounts al dueño Streaming. Las mutaciones conservan su autorización y CSRF.

## Opciones consideradas

- Caddy: configuración breve, WS nativo, estáticos y TLS; seleccionado para una entrada P1.
- Nginx: alternativa válida, exige más configuración explícita para TLS/Upgrade; no añade valor aquí.
- Servir Vite en despliegue: reservado a desarrollo; preview no define la frontera de rutas/seguridad.
- HLS solo nativo: deja fuera navegadores con MediaSource; hls.js cubre ese caso sin otro backend.
- Reimplementar demux/player: complejidad innecesaria y riesgo de incompatibilidad; descartado.
- Buscar el canal recorriendo páginas GraphQL: no garantiza resolver un ID directo y multiplica tráfico.

## Consecuencias

Hay dependencias de navegador adicionales, cargadas separadamente de la vista inicial. Se conserva
el diseño existente y las muestras solo en pruebas y rutas explícitas de componentes/prototipo.
No se usan mocks como fallback. HTTP entre procesos en fixtures no demuestra TLS productivo, y
herramientas automáticas de accesibilidad complementan la verificación manual de SPEC-08.

## Verificación

Puertas: contratos sin drift, pnpm check, navegador con Core/Streaming/MediaMTX/Chat/Redis reales,
frame decodificado/lease, WS/historial/reintento, rutas profundas/errores JSON, uploads y teclado.
SPEC-13 conserva la aceptación de TLS, S3, carga y tiempo al primer frame bajo ese perfil.

## Revisión

Revisar por integración/Web si se requiere SSR, cambian protocolos media/Chat, se añaden proxies
confiables o las mediciones justifican otra estrategia de caché, consulta o reproducción.

Referencias técnicas: [Caddy reverse_proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy),
[Hls API](https://hlsjs.video-dev.org/api-docs/hls.js.hls),
[Ajv 2020-12](https://ajv.js.org/guide/schema-language).
