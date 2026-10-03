# SPEC-12 Integración del shell web y reverse proxy P1

- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Define la integración del frontend web de los módulos P1 con el shell común y reverse proxy, de modo que cada módulo pueda contribuir sin romper navegación, auth, paths o playback. Hijo de SPEC-09.

## 2. Definición del componente

El monorepo modular y las carpetas propietarias ya están definidos. Los puertos y la selección técnica del shell/proxy se completan en ADR sin alterar las rutas y reglas de integración especificadas aquí.

## 3. Historia de usuario

Como usuario final, quiero recorrer registro, canal, emisión, chat y búsqueda bajo una aplicación coherente; como desarrollador, quiero conectar y probar vistas aportadas por varios módulos.

## 4. Alcance

### Dentro de P1

- Contrato del shell: rutas top-level, navegación, layout, carga de módulos, sesión/principal, tokens visuales y estados de carga/error/vacío.

- Rutas públicas de canal por handle; vista de stream/player con metadata; chat; búsqueda/listado; formularios de identidad/perfil.

- Tabla de reverse proxy host/path/upstream/puerto; reglas de cookies/auth headers, CORS cuando aplique, timeouts, request ID y fallback.

- WebSocket Upgrade para Chat y rutas de entrega HLS por HTTP. Listener RTMP aparte, no presentado como HTTP.

- Configuración dev reproducible por unidad y una Web integrada; no microfrontend por módulo.

### Fuera de P1

- Aplicación móvil, multi-tenant, deploy global multi-región, CDN productivo y microfrontend obligatorio.

### Supuestos acordados

- Un shell registra rutas globales; los módulos no cambian router/config compartido unilateralmente.

- Mismo origen HTTPS para CORS/cookies; Cuentas define sesión y CSRF según ADR-001.

## 5. Contrato de interfaz web

- Rutas web P1: `/`, `/register`, `/login`, `/channels/{handle}`, `/search?q=...` y `/watch/{streamId}`. Canal consume `GET /api/channels/by-handle/{handle}`: Core compone canal, handle, perfil y estado de emisión localmente. Watch usa el snapshot público de stream/sesión; ambos montan Chat desde sessionId.

- Shell consume interfaces UI/versionadas; módulo publica ruta/entry, estados y dependencias, sin compartir store privado.

- Proxy distingue APIs, chat realtime, HLS, assets SPA y entrada RTMP por listener separado.

- Fallback SPA nunca convierte error API en index.html; conservar Upgrade, Host/path base, forwarded proto y correlación.

- Links directos/refresh cargan rutas profundas; auth/401/403/expiración se comunican sin pantalla negra.

## 6. Criterios de aceptación

- **CA-01:** tabla única de rutas, puertos de desarrollo, comando, upstream, owner y health no tiene conflictos.

- **CA-02:** URL directa/refresh carga canal, stream, perfil y discovery sin 404 del servidor ni fallback SPA incorrecto.

- **CA-03:** shell integra vistas de identidad, perfil, canal, player, chat y búsqueda manteniendo navegación y boundary de error por módulo.

- **CA-04:** WS acepta Upgrade solo en `/realtime/chat/sessions/{sessionId}` y verifica Origin web configurado para todos los clientes. Admite lectura anónima; cada envío requiere contexto Core con credencial vigente. Heartbeat/timeout y error de Chat no detienen HLS.

- **CA-05:** playlist/segmentos bajo `/hls/{sessionId}/*` solo se ofrecen como playback cuando availability=PLAYABLE; content types/rango/cache se documentan; solicitud→primer frame cumple máximo 5 s bajo perfil P1 de SPEC-13.

- **CA-06:** API path faltante responde status/error JSON sin HTML; headers secretos no se registran.

- **CA-07:** rutas integradas pasan criterios de teclado, foco, mensajes y semántica de SPEC-08.

- **CA-08:** Web integrada ejecutable en modo local; muestra fallback por vista y unidad upstream real si cae.
- **CA-09:** canal por handle usa bootstrap local Core; ACTIVE ya tiene canal/perfil desde commit, sin reintentos por atraso de proyección. Inexistente/no activo 404 uniforme y Core no disponible error explícito, nunca página “activándose” por una frontera eliminada.

## 7. Diseño técnico y configuración

Una Web y un build; código en apps/web/src, módulos en src/modules/{accounts,channels,streaming,chat,taxonomy,discovery}, shell en src/shell y utilidades compartidas en src/accessibility. Shell registra rutas/globales, componentes/tokens y errores por vista. Canal por handle consume un bootstrap Core compuesto; player y chat se montan desde sessionId. Proxy enruta por prefijos a Core/Chat/Media, sin auth de negocio ni saga; bloquea /internal y sobrescribe forwarding. Paths API no caen al fallback SPA. TLS, CSRF, límite multipart, WS Upgrade/Origin y HLS range/cache definidos en documento frontend.

## 8. Dependencias y contratos de integración

Web consume APIs Core y Chat/HLS; módulos UI no requieren procesos propios. Proxy tabla única coherente con contratos; Media listener RTMP separado de HTTP. Backend protege sesiones y propiedad; frontend nunca decide owner ni estado LIVE.

## 9. Decisiones y preguntas abiertas

Web integrada y mismo origen HTTPS. Stacks y librerías Web se seleccionan mediante ADR. La ruta por handle compone datos públicos dentro de Core.

## 10. Verificación

- Smoke de rutas directas y navegación end-to-end.

- Pruebas REST, fallback, WS Upgrade/ping, HLS playlist/segmento y RTMP ingest con fuente de prueba.

- Ejecutar módulo caído y verificar fallback; refresh en ruta profunda.

- Inspeccionar forwarding/CORS y confirmar cookies/token nunca se registran.

- Prueba teclado/semántica de rutas integradas según SPEC-08.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M/L. **Riesgos:** route drift, API fallback como HTML, WS sin upgrade, cookie insegura, store global acoplado y proxy con lógica de negocio. **Consecuencia:** móvil, edge global y CDN productivo se aplazan.
