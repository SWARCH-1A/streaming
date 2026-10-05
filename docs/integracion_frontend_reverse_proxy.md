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
case-insensitive se canonicaliza con 308. /watch/{streamId} obtiene bootstrap y monta HLS + Chat por
sessionId; RECONNECTING informa pérdida temporal sin anunciar playback confirmado.

## Tabla única de rutas

| Ruta pública | Unidad | Reglas |
| --- | --- | --- |
| / y rutas SPA válidas | Web | Fallback solo para navegación, nunca /api ni /internal |
| /api/identity/* | Core | Cookie/CSRF/idempotencia/correlación; registro transaccional |
| /api/profile/* | Core | Perfil self/público, multipart <=10 MB + overhead, avatar público |
| /api/channels/{channelId}/streams (POST/GET) | Streaming | Regla de path exacto previa al prefijo Canales; contexto owner Core |
| /api/channels/* | Core | Canal/por handle/por owner, edición/banner/CSRF |
| /api/streams/* | Streaming | Configuración/sesión/metadata/leases y stop; secretos excluidos de lectura pública |
| /api/taxonomy | Core | Catálogo público/versionado |
| /api/watch-parties/* | Core | Sesiones de visualización conjunta (SPEC-14); cookie/CSRF; el código de acceso no se registra en logs |
| /api/discovery/graphql | Core | GraphQL SQL local con proyección pública Streaming, límites de costo/cuerpo/rate |
| /api/chat/sessions/*/messages | Chat | Historial 1–50, anónimo, orden y snapshotSequence |
| /realtime/chat/sessions/{sessionId} | Chat | Upgrade, cookie, Origin permitido incluso para anónimos; errores WS correctos |
| /hls/{sessionId}/* | Media | Playlist/segmentos, content type/range/cache; manifest publicado solo al confirmar PLAYABLE |
| /internal/* | Bloqueada (404/deny) | Ninguna ruta privada se expone por el listener web |
| Listener RTMP | Media | Puerto TCP dedicado, no fingir ruta HTTP |

Los mensajes HTTP privados Core–Streaming/Chat y Streaming–Chat/Media viajan en TLS y credenciales distintas por consumidor/
operación. Proxy no autentica usuario ni interpreta reglas de dominio. Sobrescribir X-Forwarded-For/
X-Real-IP del cliente con IP observada; backend confía solo en proxy configurado. No imprimir body,
cookie, X-Session-Credential, streamKey, token de lease o Idempotency-Key en logs.

## Organización del repositorio

services/core contiene Cuentas, Canales, Catálogo y Discovery, con build/seguridad comunes y proyección pública SQL. services/streaming contiene el control Rust con PostgreSQL privado y pooling. services/chat contiene Chat; infra/media configura MediaMTX y el adaptador Rust.
apps/web tiene un build y código en src/: modules/accounts, channels, streaming, chat, taxonomy y
discovery; shell compone rutas y accessibility contiene utilidades compartidas. No crear aplicaciones
por módulo. El framework/entry/build Web se concreta por ADR. contracts/generated contiene artefactos generados;
infra mantiene configuración y tests/ la evidencia compartida. El mapa_sdd_p1 define la propiedad.

## Puertos y configuración

Core usa 8081, Streaming 8080, Chat reserva 8085 y Web reserva 3000. Los listeners HLS/RTMP y la configuración MediaMTX se fijan antes del despliegue conforme a ADR-005.
Bases en red privada y volumen de imágenes persistente. El runbook de cada unidad declara variables,
comando y health; las reservas de componentes pendientes se concretan al implementarlos.

Una configuración/env de ejemplo central por unidad desplegable; no secreto por módulo Core ni
cliente HTTP a localhost para comunicar módulos locales. Core comparte security/CSRF y sesión opaca.
Streaming recibe la misma cookie host-only de Cuentas y valida comandos mediante contexto Core; exige Origin permitido y defensa CSRF según ADR-001. Chat valida sesión por contexto Core, que consulta estado/timeline actual Streaming. El proxy elimina headers privados X-Service-Name/X-Service-Token/X-Session-Credential provenientes del público; backends autentican siempre las rutas privadas.

Core ejecutable usa CORE_DB_URL/USER/PASSWORD, CORE_RATE_LIMIT_HMAC_SECRET, CORE_SECURE_COOKIE,
WEB_ORIGIN, PROFILE_AVATAR_STORAGE/PUBLIC_BASE y CHANNELS_BANNER_STORAGE/PUBLIC_BASE. El [runbook Core](../services/core/README.md)
y [Compose local](../infra/local/README.md) contienen comandos, health y volúmenes. El backend directo
ignora headers forwarded; al implementar el proxy se configurará confianza únicamente en sus
IP/redes y se verificará la cuota por IP antes de habilitar ese despliegue.

## Reglas de evolución

Cambiar path/schema/auth exige actualizar inventario, SPEC consumidores y tabla de proxy. Cambios
incompatibles tienen transición explícita; no esconder excepciones en gateway. La futura extracción
de una API de Core debe cumplir la puerta de fases_futuras, incluyendo costo/consistencia/migración.
Un motor de despliegue inicia procesos, no coordina casos de uso de negocio.
