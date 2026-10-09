# Web, reverse proxy y organización — arquitectura

**Arquitectura:** ADR-005.

## Web como una aplicación

Un shell, un build y módulos internos de UI; no microfrontends por SPEC. Shell posee rutas, layout,
componentes/tokens, sesión común, foco y límites de error. Vistas aportan loading/empty/error/denegado
sin ocultar el player por caída de Chat. Campos internos de backend no se comparten como stores/ORM.
Accesibilidad se aplica según SPEC-08 en cada vista, sin servicio “Accessibility”.

/ y /search consultan GraphQL Discovery de Core. /register y /login conservan formularios y CSRF;
registro confirma cuenta/canal o error, sin polling PENDING nuevo. /channels/{handle}
consume un bootstrap Core con cuenta/perfil/canal locales y un batch Streaming; fallo Streaming conserva el canal con estado UNKNOWN. Handle
case-insensitive se canonicaliza con history replace en la SPA; bootstrap API conserva 200. /watch/{streamId} obtiene bootstrap y monta HLS + Chat por
sessionId; RECONNECTING informa pérdida temporal sin anunciar playback confirmado.

## Tabla única de rutas

| Ruta pública | Unidad | Reglas |
| --- | --- | --- |
| / y rutas SPA válidas | Web | Fallback solo para navegación, nunca /api ni /internal |
| /api/identity/* | Core | Cookie/CSRF/idempotencia/correlación; registro transaccional |
| /api/profile/* | Core | Perfil self/público, multipart <=10 MB + overhead, lectura pública de avatar desde Core |
| /api/channels/{channelId}/streams (POST/GET) | Streaming | Regla de path exacto previa al prefijo Canales; contexto owner Core |
| /api/channels/* | Core | Bootstrap por ID/handle/owner, edición/banner/CSRF |
| /api/streams/* | Streaming | Configuración/sesión/metadata/leases y stop; secretos excluidos de lectura pública |
| /api/taxonomy | Core | Catálogo público/versionado |
| /api/discovery/graphql | Core | GraphQL SQL local con proyección pública Streaming, límites de costo/cuerpo/rate |
| /api/chat/sessions/*/messages | Chat | Historial 1–50, anónimo, orden y snapshotSequence |
| /realtime/chat/sessions/{sessionId} | Chat | Upgrade, cookie, Origin permitido incluso para anónimos; errores WS correctos |
| /hls/{sessionId}/* | Streaming:8888 (adaptador Media) | Playlist/segmentos, content type/range/cache; manifest publicado solo al confirmar PLAYABLE |
| /internal/* | Bloqueada (404/deny) | Ninguna ruta privada se expone por el listener web |
| Listener RTMP | Media | Puerto TCP dedicado, no fingir ruta HTTP |

Los mensajes HTTP privados entre contenedores Core–Streaming/Chat y Streaming–Chat viajan en TLS y credenciales distintas por consumidor/operación. Autorización/callbacks entre control Streaming y su adaptador Media admiten HTTP loopback autenticado en desarrollo sin TLS según ADR-011; con TLS habilitado usan HTTPS y verificación de CA/hostname también dentro del mismo contenedor. Proxy no autentica usuario ni interpreta reglas de dominio. Sobrescribir X-Forwarded-For/
X-Real-IP del cliente con IP observada; backend confía solo en proxy configurado. No imprimir body,
cookie, X-Session-Credential, streamKey, token de lease o Idempotency-Key en logs.

## Organización del repositorio

services/core contiene Cuentas, Canales, Catálogo y Discovery, con build/seguridad comunes y proyección pública SQL. services/streaming contiene el control Rust y el adaptador técnico Media en el mismo proceso P1, con bases/roles separados y pooling según ADR-011. services/chat contiene Chat; infra/media documenta MediaMTX; configuración del motor y código del adaptador están en services/streaming.
apps/web tiene un build y código en src/: modules/accounts, channels, streaming, chat, taxonomy y
discovery; shell compone rutas y accessibility contiene utilidades compartidas. No crear aplicaciones
por módulo. ADR-007 selecciona React/TypeScript/SWC y pnpm; `src/main.tsx` inicia la SPA. ADR-013 conecta HTTP/GraphQL/WS/HLS reales y selecciona Caddy/hls.js. Las muestras visuales explícitas no acreditan integración ni reemplazan respuestas fallidas. contracts/generated contiene artefactos generados;
infra mantiene configuración y tests/ la evidencia compartida. El mapa_sdd_p1 define la propiedad.

## Puertos y configuración

Core usa 8081, Streaming 8080, Chat usa 8085 y Web/proxy usan 3000. P1 usa HLS en Streaming:8888 y RTMP en MediaMTX:1935; autorización Media en Streaming:8090 y contrato interno en Streaming:8091, sin publicar estos últimos. ADR-011 fija la composición de tres contenedores. Son puertos de desarrollo; el perfil persistente [ADR-014](adr/ADR-014-perfil-integrado-tls-p1.md) usa TLS nativo en los mismos listeners HTTP, RTMPS en MediaMTX:1936 y la [instalación local de equipo](../README.md) publica HTTPS localhost:3445 y RTMPS localhost:11938, con Web:3443 como upstream TLS privado del proxy. Los fixtures de aceptación conservan HTTPS3443/RTMPS11936 y Web/proxy juntos; ver [runbook de aceptación](../infra/p1/README.md).
Bases en red privada y bucket S3 privado predeterminado para imágenes; filesystem, si se selecciona
explícitamente, requiere volumen persistente.
Las URLs públicas de avatar/portada permanecen bajo Core; el proxy no expone el endpoint S3. El runbook de cada unidad declara variables,
comando y health; Web ejecuta `pnpm dev` en apps/web, en 127.0.0.1:3000; `pnpm build` genera dist y
`pnpm preview` sirve esa salida en el mismo puerto. Caddy sirve dist en 3000 en el perfil HTTP local explícito; el perfil HTTPS pertenece a SPEC-13.

Una configuración/env de ejemplo central por unidad desplegable; no secreto por módulo Core ni
cliente HTTP a localhost para comunicar módulos locales. Core comparte security/CSRF y sesión opaca.
Streaming recibe la misma cookie host-only de Cuentas y valida comandos mediante contexto Core; exige Origin permitido y defensa CSRF según ADR-001. Chat valida sesión por contexto Core, que consulta estado/timeline actual Streaming. El proxy elimina headers privados X-Service-Name/X-Service-Token/X-Session-Credential provenientes del público; backends autentican siempre las rutas privadas.

Core ejecutable usa CORE_DB_URL/USER/PASSWORD, CORE_RATE_LIMIT_HMAC_SECRET, CORE_SECURE_COOKIE,
WEB_ORIGIN, CORE_IMAGE_STORAGE_PROVIDER, CORE_IMAGE_S3_BUCKET/REGION/ENDPOINT/PATH_STYLE_ACCESS,
AWS_* (credenciales temporales si no se usa un rol) y PROFILE_AVATAR_PUBLIC_BASE/CHANNELS_BANNER_PUBLIC_BASE;
los directorios PROFILE_AVATAR_STORAGE y CHANNELS_BANNER_STORAGE se usan en modo filesystem. El [runbook Core](../services/core/README.md)
y [Compose local](../infra/local/README.md) contienen comandos, health y volúmenes. El backend directo ignora forwarded de peers no confiables; CORE_TRUSTED_PROXIES enumera solo la IP/red del proxy. Accounts y Discovery comparten resolución de IP para sus cuotas. El fixture obtiene la IP efectiva de Caddy y recrea Core con esa confianza; si cambia la IP se reconfigura Core. No confiar en una red compartida completa por comodidad.

## Reglas de evolución

Cambiar path/schema/auth exige actualizar inventario, SPEC consumidores y tabla de proxy. Cambios
incompatibles tienen transición explícita; no esconder excepciones en gateway. La futura extracción
de una API de Core debe cumplir la puerta de fases_futuras, incluyendo costo/consistencia/migración.
Un motor de despliegue inicia procesos, no coordina casos de uso de negocio.

## Reproducción, historial y desarrollo

Las vistas usan cookies same-origin y schemas/tipos/queries generados. Sesión se restaura desde
Profile self e identidad pública; las rutas protegidas esperan restauración. Errores preservan
formularios y no activan datos demo. Un cambio de recurso aborta lecturas y descarta respuestas tardías.

HLS solo se carga para LIVE/PLAYABLE. HLS nativo o hls.js diferido usa controles HTML; el primer frame
decodificado abre lease, heartbeat cada 10 s y cierre en pausa, pagehide, salida, error o cambio de
sesión. La expiración de 30 s limita cierres/creaciones sin respuesta. Un 401 de lease no revoca la
sesión Accounts. El backend conserva autoridad de availability, conteo y permisos.

Chat abre WS antes de historial, fusiona por secuencia y repara huecos dentro de los 50 mensajes
retenidos; avisa cuando ya no son recuperables. Un envío pendiente conserva clientMessageId y texto
hasta ACK o descarte explícito. Reconecta con backoff hasta 15 s; cleanup cierra conexiones anteriores.
Ocultar Chat no afecta HLS. Autoscroll puede pausarse y no roba foco.

La [configuración Caddy](../infra/reverse-proxy/README.md) define rutas y ejecución. No hay access
logs con payloads/credenciales; playlists y segmentos conservan types/ranges/cache del adaptador
Media (playlist no-cache, segmentos inmutables según su respuesta). No cachear errores o inventar LIVE.
Vite puede activar proxy HTTP local con VITE_P1_PROXY=1; sin él devuelve errores JSON explícitos de
configuración para APIs. Solo Caddy es el proxy integrado y Vite preview no acredita despliegue.
