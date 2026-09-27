# SPEC-12 Integración del shell web y reverse proxy P1

- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Define la integración del frontend web de los módulos P1 con el shell común y reverse proxy, de modo que cada módulo pueda contribuir sin romper navegación, auth, paths o playback. Hijo de SPEC-09.

## 2. Estado del sistema y brecha

El monorepo modular y las carpetas propietarias ya están definidos. Los puertos y la selección técnica del shell/proxy se completan en ADR sin alterar las rutas y reglas de integración especificadas aquí.

## 3. Historia de usuario

Como usuario final, quiero recorrer registro, canal, emisión, chat y búsqueda bajo una aplicación coherente; como desarrollador, quiero conectar y probar vistas aportadas por varios módulos.

## 4. Alcance

### Dentro de P1

- Contrato del shell: rutas top-level, navegación, layout, carga de módulos, sesión/principal, tokens visuales y estados de carga/error/vacío.

- Rutas públicas de canal por handle; vista de stream/player con metadata; chat; búsqueda/listado; formularios de identidad/perfil.

- Tabla de reverse proxy host/path/upstream/puerto; reglas de cookies/auth headers, CORS cuando aplique, timeouts, request ID y fallback.

- WebSocket Upgrade para Chat y rutas de entrega HLS por HTTP. Listener RTMP aparte, no presentado como HTTP.

- Configuración dev reproducible sin fijar framework ni estrategia de microfrontend.

### Fuera de P1

- Aplicación móvil, multi-tenant, deploy global multi-región, CDN productivo y microfrontend obligatorio.

### Supuestos acordados

- Un shell registra rutas globales; los módulos no cambian router/config compartido unilateralmente.

- Preferir mismo origen HTTPS para reducir complejidad de CORS/cookies; Identity decide sesión segura por ADR.

## 5. Contrato de interfaz web

- Rutas web P1: `/`, `/register`, `/login`, `/channels/{handle}`, `/search?q=...` y `/watch/{streamId}`. Shell resuelve el handle con Identity y compone Channels/Profile/Streaming; watch obtiene sessionId para Chat.

- Shell consume interfaces UI/versionadas; módulo publica ruta/entry, estados y dependencias, sin compartir store privado.

- Proxy distingue APIs, chat realtime, HLS, assets SPA y entrada RTMP por listener separado.

- Fallback SPA nunca convierte error API en index.html; conservar Upgrade, Host/path base, forwarded proto y correlación.

- Links directos/refresh cargan rutas profundas; auth/401/403/expiración se comunican sin pantalla negra.

## 6. Criterios de aceptación

- **CA-01:** tabla única de rutas, puertos de desarrollo, comando, upstream, owner y health no tiene conflictos.

- **CA-02:** URL directa/refresh carga canal, stream, perfil y discovery sin 404 del servidor ni fallback SPA incorrecto.

- **CA-03:** shell integra vistas de identidad, perfil, canal, player, chat y búsqueda manteniendo navegación y boundary de error por módulo.

- **CA-04:** WS acepta Upgrade solo en `/realtime/chat/sessions/{sessionId}`, verifica credencial vigente y Origin web configurado, admite lectura anónima/escritura autenticada y heartbeat/timeout; chat desconectado no detiene HLS.

- **CA-05:** playlist/segmentos bajo `/hls/{sessionId}/*` solo se ofrecen como playback cuando availability=PLAYABLE; content types/rango/cache se documentan; solicitud→primer frame cumple máximo 5 s bajo perfil P1 de SPEC-13.

- **CA-06:** API path faltante responde status/error JSON sin HTML; headers secretos no se registran.

- **CA-07:** rutas integradas pasan criterios de teclado, foco, mensajes y semántica de SPEC-08.

- **CA-08:** módulo ejecutable en modo local; shell muestra fallback identificable si upstream cae.
- **CA-09:** tras resolver un handle a identidad ACTIVE, 404 de Channels por proyección pendiente se reintenta a 100/250/500/1000 ms bajo deadline total de 2 s. Agotado, se muestra un estado de activación/reintento explícito, no 404 permanente. Para identidad no activa o desconocida se conserva el 404 indistinguible.

## 7. Diseño técnico y configuración

- Rutas de API y proxy quedan especificadas en `integracion_frontend_reverse_proxy.md`: `/api/streams/{streamId}`, `/api/streams/sessions/{sessionId}`, `/api/streams/sessions/{sessionId}/viewer-leases`, `/api/chat/sessions/{sessionId}/messages`, `/realtime/chat/sessions/{sessionId}` y `/hls/{sessionId}/*`. La tabla central es la fuente única y no hay dos rutas de chat con distinta semántica.

- Tabla inicial separa shell, APIs HTTP, WebSocket, HLS y RTMP. Hostnames/puertos quedan como parámetros hasta ADR.

- Config de route paths es fuente única reutilizada por proxy, shell y compose/env; actualizar juntas en cada cambio.

- Navegador usa la cookie opaca `HttpOnly; Secure; SameSite=Lax` definida por Identity; mutaciones pasan la protección CSRF definida en ADR. Handshake WS compara Origin con el origen web configurado; proxy no transforma identidad ni valida ownership.

- Frontend integrado, paquetes compartidos acotados y microfrontend son opciones; comparar independencia de build, fallos runtime y complejidad.

- Monorepo modular P1; carpetas por dominio y ownership explícito. Una futura separación multirepo necesita decisión explícita y contratos versionados.

## 8. Dependencias y contratos de integración

- Consume contratos SPEC-10 y flujos de auth/session/events SPEC-11.

- Identity publica login/session/logout; Channels/Profile canal/perfil; Streaming playback/status y callbacks internos no expuestos al browser; Taxonomy values; Discovery list; Chat WebSocket/history.

- Proxy/deploy consume tabla de procesos/health de SPEC-13.

- Registrar path/puerto con el owner antes de añadirlo a configuración compartida.

## 9. Decisiones y preguntas abiertas

**Acordado:** shell común, rutas directas, reverse proxy, mismo origen preferido, cookie de sesión, WS/media documentados y monorepo modular. **No bloqueante:** puertos, hostname, mecanismo CSRF y composición frontend se resuelven entre responsables por ADR.

## 10. Verificación

- Smoke de rutas directas y navegación end-to-end.

- Pruebas REST, fallback, WS Upgrade/ping, HLS playlist/segmento y RTMP ingest con fuente de prueba.

- Ejecutar módulo caído y verificar fallback; refresh en ruta profunda.

- Inspeccionar forwarding/CORS y confirmar cookies/token nunca se registran.

- Prueba teclado/semántica de rutas integradas según SPEC-08.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M/L. **Riesgos:** route drift, API fallback como HTML, WS sin upgrade, cookie insegura, store global acoplado y proxy con lógica de negocio. **Consecuencia:** móvil, edge global y CDN productivo se aplazan.
