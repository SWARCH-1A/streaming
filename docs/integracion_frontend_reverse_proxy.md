# Frontend, reverse proxy y repositorios

**Estado:** convención de integración para el monorepo modular P1.

## Objetivo

Permitir que cada equipo de módulo implemente y despliegue su parte sin romper la navegación, contratos
ni arranque de los demás. El frontend es una aplicación web con un shell dueño del layout, rutas
globales, navegación común y tratamiento de errores; los módulos aportan rutas/vistas acordadas.

## Estructura del repositorio

El repositorio STREAMING es el monorepo modular de trabajo. Las carpetas expresan ownership lógico;
no fijan por sí solas la cantidad de procesos o contenedores.

```text
streaming/
  AGENTS.md
  apps/web/shell/
  apps/web/accessibility/
  apps/web/modules/<domain>/
  services/identity/       # ubicación lógica, no framework impuesto
  services/profile/
  services/channels/
  services/streaming/
  services/chat/
  services/taxonomy/
  services/discovery/
  contracts/generated/
  infra/reverse-proxy/
  infra/local/
  infra/media/
  tests/contracts/
  tests/integration/
  tests/e2e/
  docs/                     # requisitos, SPEC, contratos, decisiones y ADR
```

Las rutas completas y reglas de asignación están en el README raíz y AGENTS.md. Si el equipo decide
separar repositorios en el futuro, deberá registrar la decisión, versionar contratos y conservar una
fuente de configuración compatible para shell, proxy y despliegue.

## Contrato de frontend

- Solo el shell registra rutas top-level, navegación, autenticación compartida, tokens visuales,
  telemetría y límite de error por módulo.
- Una ruta de módulo se agrega mediante acuerdo y revisión del shell; un módulo no debe cambiar
  enrutamiento global, dependencias de otro módulo ni estilos globales sin ADR/revisión.
- Cada vista implementa estados loading, vacío, error, permiso denegado y datos; accesibilidad se
  verifica según [SPEC-08](spec-p1/spec_08_a11y.md).
- Componentes reutilizables publican API estable y evitan compartir stores internos. Propiedades y
  eventos frontend usan tipos de contrato/versionados, no clases de backend compartidas.
- Una caída de Chat no debe ocultar video; una caída de Discovery no debe bloquear visita directa al
  canal; renderizar fallback por frontera.
- La selección de microfrontend vs build integrado queda abierta; Integration documenta alternativas
  y registra la decisión en un ADR antes de implementarla.

## Rutas web visibles

- `/` muestra browse/discovery; `/search?q=...` busca canales/títulos.
- `/register` y `/login` abren Identity; el registro PENDING muestra estado reintentable y nunca sesión.
- `/channels/{handle}` es una URL por handle canónico de Identity; shell resuelve Identity→ownerUserId→Channels/Profile/Streaming. Si Identity confirma ACTIVE pero Channels aún responde 404 por retraso de proyección, el shell reintenta a los 100/250/500/1000 ms dentro de un deadline total de 2 s; agotado el plazo muestra estado transitorio y acción manual de reintento, no una página 404. Profile sirve fallback con handle para usuarios ACTIVE.
- `/watch/{streamId}` carga bootstrap del stream, player, metadata y Chat por sessionId. Durante RECONNECTING muestra estado sin inventar una reproducción disponible.

## Tabla de rutas de reverse proxy

Prefijos y puertos son placeholders para que el equipo los complete en conjunto y mantenga una sola
fuente de configuración. Preferir un mismo origen HTTPS para simplificar cookies/CORS.

| Host/path público | Upstream lógico | Tipo | Requisito |
| --- | --- | --- | --- |
| `/` | `web-shell:<WEB_PORT>` | HTTP | fallback solo a rutas SPA válidas; no interceptar rutas API faltantes como HTML |
| `/api/identity/*` | `identity:<PORT>` | REST/HTTP | conservar `Authorization`/cookie acordado y request ID |
| `/api/profile/*` | `profile:<PORT>` | REST/HTTP | límites de carga de avatar, timeouts |
| `/api/channels/*/streams` | `streaming:<PORT>` | REST/HTTP | ruta específica con precedencia sobre `/api/channels/*`; crea/lee StreamConfig, no Channel |
| `/api/channels/*` | `channels:<PORT>` | REST/HTTP | GET por owner y PATCH parcial por channelId; handle no es propiedad de Channels; provisión queda fuera de rutas públicas |
| `/api/streams/*` | `streaming:<PORT>` | REST/HTTP | lectura stream/sesión, metadata propietaria y leases/heartbeat de viewers |
| `/api/chat/sessions/*/messages` | `chat:<PORT>` | REST/HTTP | historial por sessionId, máximo 50 mensajes recientes y orden ascendente; ruta REST necesaria además de WebSocket |
| `/api/taxonomy/*` | `taxonomy:<PORT>` | REST/HTTP | valores cacheables con invalidación/versionado |
| `/api/discovery/graphql` | `discovery:<PORT>` | GraphQL sobre HTTP/JSON | queries `streams` y `channels`, un categoryId/tagId, limit/cursor/freshness; límites de complejidad/rate de contrato |
| `/realtime/chat/sessions/{sessionId}` | `chat:<WS_PORT>` | WebSocket Upgrade | ruta canónica; conserva Upgrade/auth/Origin configurado; lectura anónima, escritura autenticada |
| `/hls/{sessionId}/*` | `media-server:<MEDIA_PORT>` | HTTP | playlist/segmentos HLS por sessionId; content types/rango/cache; URL solo si availability=PLAYABLE |
| listener RTMP | entrada multimedia | RTMP/TCP | listener dedicado; no fingir que es una ruta HTTP |
| `POST /internal/streaming/ingest/authorize` | media adapter → Streaming privado | HTTPS/TLS interno autenticado | autorizar streamKey, reservar slot y obtener sessionId/sourceGeneration; no publicar en el proxy web |
| `POST /internal/streaming/sessions/{sessionId}/source-connected` | media adapter → Streaming privado | HTTPS/TLS interno autenticado | callback durable e idempotente; no se enruta por el proxy web |
| `POST /internal/streaming/sessions/{sessionId}/playback-ready` | media adapter → Streaming privado | HTTPS/TLS interno autenticado | callback durable e idempotente con playbackPath validada; no se enruta por el proxy web |
| `POST /internal/streaming/sessions/{sessionId}/source-lost` | media adapter → Streaming privado | HTTPS/TLS interno autenticado | callback durable e idempotente; no se enruta por el proxy web |
| `POST /internal/channels/provision` | Identity → Channels privados | HTTPS/TLS interno autenticado | provisión vinculada a registrationId; no se enruta por el proxy web |
| `GET/DELETE /internal/channels/provisions/{registrationId}` | Identity → Channels privados | HTTPS/TLS interno autenticado | reconciliación/compensación idempotente por registrationId; no se enruta por el proxy web |
| `/internal/identity/*`, `/internal/channels/*`, `/internal/taxonomy/*`, `/internal/streaming/*` | servicios internos | HTTPS/TLS interno autenticado | tráfico de servicio a servicio; reverse proxy público responde 404/deny para cualquier `/internal/*`, nunca enruta rutas privadas |

Las reglas se evalúan por ruta más específica antes que por prefijo genérico: `/api/channels/*/streams`
siempre llega a Streaming aunque comparta el prefijo `/api/channels/*` con Channels. Los paths
`/internal/*` se bloquea en el listener público; la red privada no sustituye HTTPS/TLS para mover
cookies, claves RTMP u otras credenciales entre servicios. La cabecera de IP reenviada que recibe
Discovery la sobrescribe el proxy con la IP observada del cliente; valores `X-Forwarded-For` y
`X-Real-IP` aportados directamente por el cliente no se conservan. El path definitivo se sincroniza con los contratos
y el router del shell. Ningún navegador accede a puertos internos de servicio o de base de datos.

## Puertos de desarrollo y configuración

Cada módulo registra en su README: puerto local, comando de arranque, health path, variables no
secretas, dependencias, paths consumidos/producidos, build y test commands, y configuración del
reverse proxy. Una tabla central evita duplicados:

| Proceso/módulo | Puerto interno | Health | Comando | Responsable |
| --- | --- | --- | --- | --- |
| Shell web | Pendiente | Pendiente | Pendiente | Pendiente |
| APIs de dominio | Pendiente por módulo | Pendiente | Pendiente | Pendiente |
| Chat tiempo real | Pendiente | Pendiente | Pendiente | Pendiente |
| Media ingest/delivery | Pendiente | Pendiente | Pendiente | Pendiente |
| SQL y NoSQL | Solo red interna | Pendiente | Perfil de contenedores | Pendiente |

No duplicar nombres de variables, credenciales o puertos en cada servicio; definir `.env.example`
central sin valores secretos y configuración por entorno. Los secretos no se versionan.

## Reglas de cambio seguro

1. Abrir PR en la organización acordada; revisión por dueño de módulo/carpeta.
2. Cambiar primero contrato/schema y agregar ejemplo; avisar consumidores ante cambio incompatible.
3. Evitar actualización mayor de dependencia base, router global o estilo compartido junto con feature
   de módulo sin revisión explícita.
4. No consultar ni escribir la base de otro dominio. Compartir identificadores y contrato.
5. Mantener scripts de build/desarrollo reproducibles, lockfiles, health checks y ejemplos de datos.
6. Cambio de host/path/protocolo o puerto implica actualizar proxy, shell, README y prueba consumidor.
7. `docker compose` es un candidato cómodo, no requisito tecnológico. El ADR de despliegue debe
   justificar la herramienta que el equipo elija.

## Revisión de topología

El monorepo modular está seleccionado para P1. Una futura separación en varios repositorios requiere
una decisión explícita que considere frecuencia de release, aislamiento de permisos, herramientas,
dueños, CI y entrega del prototipo completo; deberá preservar contratos publicados.
