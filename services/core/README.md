# Core

Un ejecutable Java 25 / Spring Boot 4.1.1, Maven Wrapper, PostgreSQL 18, JDBC y Flyway.
Seguridad, CSRF, configuración y gestor de transacciones compartidos. Decisiones:
[ADR-001](../../docs/adr/ADR-001-identity-plataforma-y-seguridad.md),
[ADR-002](../../docs/adr/ADR-002-profile-persistencia-y-avatar.md) y
[ADR-005](../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md) y
[ADR-004](../../docs/adr/ADR-004-canales-en-core.md).
La persistencia y validación de Catálogo se documentan en
[ADR-006 (propuesta para revisión)](../../docs/adr/ADR-006-taxonomia-en-core.md) y las de Descubrimiento en
[ADR-008](../../docs/adr/ADR-008-descubrimiento-en-core.md).

## Módulos e implementación

Código bajo `src/main/java/streaming/core`:

- `accounts/identity`: registro, credenciales, sesiones, idempotencia y cuotas SQL.
- `accounts/profile`: perfil, edición propia y archivos/permisos de avatar. Valida sesión localmente.
- `channels`: creación inicial transaccional, edición propia, portadas y bootstrap público por handle/owner, con vistas SQL públicas de Cuentas.
- `taxonomy`: catálogo público activo/versionado, semilla SQL, tombstones y validación local de IDs.
- `discovery`: GraphQL público (`streams`, `channels`), inbox/proyección SQL de los snapshots públicos de Streaming y reconstrucción desde su corte consistente ([ADR-008](../../docs/adr/ADR-008-descubrimiento-en-core.md)).
- `streaming`: documentación de frontera; control de emisiones en el [servicio Rust](../streaming/README.md). Los contextos privados owner/catálogo están implementados en Core; la composición de estado/timeline Streaming sigue pendiente.
- `security`, `api`: infraestructura común, sin orquestación de casos de uso de dominio.

El registro pertenece a Cuentas: abre una transacción y llama interfaces locales de inicialización
de perfil/canal; cada repositorio escribe sus tablas. Confirma cuenta ACTIVE, perfil versión 0,
canal versión 0 y resultado idempotente juntos. No inicia sesión. Las FK y UNIQUE protegen relaciones
y unicidad. Un reintento recupera los mismos IDs; un fallo revierte todas las escrituras de negocio.
Las cuotas se guardan aparte para limitar intentos fallidos.

No hay HTTP entre estos módulos, worker de provisión, reservas PENDING ni outbox de identidad/perfil.
La consulta pública compone canal/handle/perfil en una sentencia SQL y devuelve `stream:null`
para el canal inicial sin configuración. Edición/portada usa la sesión y CSRF de Core; los PATCH
parciales bloquean la fila y solo incrementan channelVersion por cambios efectivos. La composición
de Streaming en el canal y el contexto Chat aún no se implementan; RF-011/RF-012
y el cumplimiento completo de SPEC-03/P1 siguen pendientes. El contexto privado de propietario
está implementado; la composición de emisión en Canales sigue pendiente.

## Configuración

| Variable | Uso |
| --- | --- |
| PORT | Puerto Core; default 8081 |
| CORE_DB_URL | JDBC; default jdbc:postgresql://localhost:5432/core |
| CORE_DB_USER | Usuario SQL; default core |
| CORE_DB_PASSWORD | Obligatoria; contraseña SQL |
| CORE_RATE_LIMIT_HMAC_SECRET | Obligatoria, al menos 32 bytes; fingerprint y cuotas |
| CORE_SECURE_COOKIE | true bajo HTTPS; false solo para desarrollo HTTP |
| WEB_ORIGIN | Origen CORS permitido; default http://localhost:3000 |
| PROFILE_AVATAR_STORAGE | Obligatoria; directorio persistente escribible por el proceso |
| PROFILE_AVATAR_PUBLIC_BASE | URI de avatar; default /api/profile/avatars |
| CHANNELS_BANNER_STORAGE | Obligatoria; directorio persistente de portadas, separado de avatares |
| CHANNELS_BANNER_PUBLIC_BASE | URI de portada; default /api/channels/banners |
| CORE_STREAMING_BASE_URL | URL base del listener privado de Streaming usado para el corte de reconstrucción de Descubrimiento. Vacío deshabilita la reconstrucción: Core sigue aplicando eventos, pero un canal sin snapshot queda `UNKNOWN` en vez de `OFFLINE` |
| CORE_STREAMING_CONSUMER_TOKEN | Secreto de 32+ caracteres enviado como `Authorization: Bearer` al corte; mismo valor que `STREAMING_CORE_CONSUMER_TOKEN` en Streaming. Sin él no hay reconstrucción. Nunca se registra |
| DISCOVERY_RECONCILE_INTERVAL | Duración ISO-8601 entre cortes completos; default `PT4S`. Streaming conserva cada corte un día: súbela si el volumen preocupa, sabiendo que la ausencia de una configuración solo se confirma con un corte de menos de 5 s |
| CORE_TRUSTED_PROXIES | CIDR separados por coma de los reverse proxies cuyo `X-Forwarded-For` se acepta (de derecha a izquierda). Vacío (default) usa siempre la IP del socket; una lista mal formada impide arrancar |

Una réplica Core. Varias requieren almacenamiento de imágenes compartido consistente o nuevo adaptador
por ADR. El contenedor usa UID 10001 y volúmenes `/data/avatars` y `/data/banners`. Persistir PostgreSQL y objetos juntos;
el HMAC se conserva entre reinicios para reintentos/cuotas. Rotarlo requiere una estrategia compatible
con la retención de treinta días, no cambiarlo accidentalmente en cada arranque.

El listener directo ignora `Forwarded`/`X-Forwarded-*`; cuotas usan la IP del socket. La integración
de reverse proxy debe configurar exclusivamente sus IP/redes confiables (`CORE_TRUSTED_PROXIES`, solo lo usa
el límite de Descubrimiento) y verificar las cuotas antes de habilitar tráfico tras proxy. Health: `GET /actuator/health`, readiness y liveness bajo
`/actuator/health/{readiness,liveness}`. Las rutas privadas de contexto solo se habilitan en el conector privado autenticado.

## Ejecución local

[Compose](../../infra/local/README.md) inicia Core y PostgreSQL con volúmenes. En Windows, desde
la raíz del repositorio:

```powershell
.\infra\local\init-env.ps1
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml up --build -d
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8081/api/taxonomy
```

El build usa JDK 25 y Maven Wrapper 3.9.11 en Docker; no requiere Java/Maven en el host.
El paquete ejecutable es `target/core.jar` y el runtime es JRE 25. Tras editar código, repetir
`up --build -d`; reiniciar un contenedor no recompila. `infra/local/.env` lo carga Compose
explícitamente, no Spring ni Maven. Catálogo reutiliza la configuración Core sin variables propias.

## Pruebas

### Contexto privado para Streaming

POST `/internal/core/streaming/owner-context` pertenece a Canales y compone validación local de
sesión/propiedad/catálogo. POST `/internal/core/streaming/catalog-values` pertenece a Taxonomía.
Ambas rutas están bloqueadas en el puerto público incluso con un token válido. En el privado
exigen `X-Service-Name: streaming` y `X-Service-Token`; owner-context requiere además
`X-Session-Credential`. No registrar esos headers ni devolverlos en errores.

| Variable | Uso |
| --- | --- |
| `CORE_INTERNAL_ENABLED` | `false` por defecto; habilita el segundo conector. |
| `CORE_INTERNAL_PORT` | Puerto privado, 8082; distinto del público, sin publicación al host en Compose. |
| `CORE_STREAMING_SERVICE_TOKEN` | Secreto aleatorio de 32–256 caracteres URL-safe, obligatorio al habilitar; mismo valor en `STREAMING_CORE_SERVICE_TOKEN`. |
| `CORE_STREAMING_CATALOG_SERVICE_TOKEN` | Opcional, 32–256 caracteres URL-safe, distinto del anterior. Solo permite POST catalog-values; owner-context devuelve 401. Vacío deshabilita esta credencial. |
| `CORE_INTERNAL_DEVELOPMENT_HTTP` | `false`; solo el overlay de desarrollo aislado habilita HTTP explícitamente. |
| `CORE_INTERNAL_TLS_KEYSTORE` | Ruta dentro del proceso al PKCS12 con certificado válido para el hostname privado. |
| `CORE_INTERNAL_TLS_KEYSTORE_PASSWORD` | Contraseña privada del PKCS12, nunca versionada. |

Sin TLS/configuración válida el listener habilitado impide arrancar. El consumidor usa
`STREAMING_CORE_BASE_URL=https://<hostname-privado>:8082` y confía en su CA;
`STREAMING_SESSION_COOKIE_NAME=stream_session`. La entrada privada también sirve las lecturas
Core usadas por el cliente Rust, con la seguridad normal de cada ruta. CSRF solo se excluye
en `/internal/**`, que exige servicio/puerto; sigue vigente en mutaciones públicas.
No se usa `X-Forwarded-Port`/`Host` para decidir si una petición es privada.

### Descubrimiento (SPEC-07)

`POST /api/discovery/graphql` es público, anónimo y de solo lectura: `streams` (solo `LIVE` + `PLAYABLE` con
observación de menos de 5 s) y `channels` (cuenta, perfil y canal siempre completos; estado `LIVE`/`OFFLINE`/`UNKNOWN`).
Reglas, errores y cursores en [SPEC-07](../../docs/spec-p1/spec_07_disc.md), el
[contrato](../../docs/contratos_modelo_datos.md) y [ADR-008](../../docs/adr/ADR-008-descubrimiento-en-core.md).
En resumen: cuerpo de hasta 16 KiB y `Content-Type: application/json` (415 si no); una sola `query` con las raíces
`streams`/`channels` una vez, sin alias, fragments ni introspección, hasta 50 filas por conexión y 100 por solicitud
(422 `QUERY_LIMIT_EXCEEDED`); JSON, sintaxis o variables inválidos son 400; los errores de campo `INVALID_FILTER`,
`INVALID_LIMIT` e `INVALID_CURSOR` responden HTTP 200 con `extensions.httpStatus=422` y datos parciales;
429 con `Retry-After` por IP (ráfaga de 20 que recarga 10/s y tope de 600 por 60 s, en memoria: una réplica).
Toda respuesta lleva `Cache-Control: no-store` y `extensions.requestId`.

`POST /internal/core/discovery/stream-events` solo existe en el conector privado y exige la misma credencial
completa de Streaming (`X-Service-Name: streaming` + `X-Service-Token`); la credencial solo-catálogo recibe 401.
Recibe cada `StreamDiscoverySnapshot`: 202 aplicado o ignorado por versión, 200 reentrega idéntica, 409 evento
o versión contradictorios (se registran, la proyección no cambia), 422 inválido, 400 JSON mal formado. El
registro de recepción y la proyección se confirman en una transacción y una fila solo se reemplaza por una
`projectionVersion` mayor. Core conserva solo identidad y hash de cada evento durante una hora, nunca el payload.

Cada `DISCOVERY_RECONCILE_INTERVAL` Core lee el corte consistente privado de Streaming
(`POST /internal/streaming/discovery/snapshots`, `Bearer CORE_STREAMING_CONSUMER_TOKEN`, páginas de 50, reinicio ante
410), valida todas las páginas y las aplica en una sola transacción: sube lo más nuevo y retira las filas ausentes solo
si se confirmaron en o antes del watermark del corte. Si falla, la proyección anterior se conserva con su edad real.
Las tablas están en el schema `discovery` (`V5__discovery.sql`); Core solo escribe en ellas y lee `channels.public_channels`,
`identity.public_accounts`, `profile.public_profiles` y la validación de catálogo ya publicada, sin SQL privado de
Streaming ni HTTP por fila.

Los [overlays locales](../../infra/local/README.md) y el
[runner con Rust](../../tests/contracts/README.md) definen arranque y evidencia reproducibles.

Desde la raíz, todas las herramientas se ejecutan dentro de Docker:

```powershell
.\infra\local\test-core.ps1 -Mode Unit
.\infra\local\test-core.ps1
```

El modo predeterminado ejecuta `verify -P integration`; `Unit` ejecuta `test` con el mismo wrapper.
El Dockerfile usa `-DskipTests`: build y smoke de salud no sustituyen estas pruebas.
`test` ejecuta reglas de dominio, perfil, catálogo y validación real de imágenes. `integration` requiere Docker
y usa PostgreSQL 18 desechable con Testcontainers; inicia Core en un puerto aleatorio. No utiliza
CORE_DB_URL ni tablas existentes del desarrollador. Incluye rollback en cada escritura dependiente,
concurrencia/idempotencia/unicidad, sesión/expiry/logout, privacidad, perfil parcial/versiones,
CSRF/CORS, edición/concurrencia de canal, uploads multipart y permisos de avatar/banner
(owner, un uso y caducidad), y conservación del archivo anterior ante rollback SQL o posterior a la escritura.

Catálogo verifica semilla/versiones, snapshot público, IDs inválidos, deduplicación y límites,
tombstones, actualización controlada, bloqueo de borrado/cambio de ID y migración desde V2.
Las asociaciones de StreamConfig y la edición LIVE se verifican con Emisiones. Las pruebas del proveedor no cierran
esa aceptación integrada.

Descubrimiento tiene pruebas unitarias (normalización NFKC, cursores, frescura, limitador, proxies confiables,
validación de snapshots, reglas de versión, reconstrucción y guardia de consultas) y de integración con PostgreSQL 18
real y un Streaming simulado que sigue el contrato del corte: ranking y empates, paginación estable, filtros AND y
`INVALID_FILTER`, tombstones, frescura con reloj controlado, reentregas/desorden/conflictos/concurrencia, atomicidad
del inbox, reconstrucción con eventos concurrentes y corte expirado, límites HTTP, 429 con proxies confiables,
p95 con 5 transmisiones y 100 canales, y la migración V4→V5 con datos. No sustituyen la prueba con el servicio
Streaming real.

## Base nueva e historiales anteriores

`db/migration/V1__core.sql` es el baseline de una **base nueva**. El historial Flyway se ubica en
schema `core`; las tablas tienen schemas `identity`, `profile`, `channels` y `taxonomy`.
`V2__channel_editing_and_banners.sql` amplía la descripción a 500 caracteres y agrega clave/permisos
de portada; se aplica tanto a bases nuevas como a una base Core con V1 sin modificar su checksum.
`V5__discovery.sql` agrega el schema `discovery` (proyección, recepción, conflictos, estado de reconciliación y
snapshots de ranking) y la vista `channels.public_channels` (solo canal, propietario y versión); se aplica a una base Core con V4 sin
modificar sus checksums. `V3__taxonomy_catalog.sql` agrega categorías, etiquetas, versión y vistas públicas, con IDs semilla estables;
se aplica a bases nuevas o Core con V2. `V4__taxonomy_disjoint_ids.sql` reserva IDs/tipos en un registro
interno único, incluidos tombstones, sin cambiar datos ni catalogVersion. Una colisión preexistente
detiene la migración completa y requiere reconciliación revisada; no ejecutar repair/clean ni
renombrar datos automáticamente. La semilla no se recrea en cada arranque. El vocabulario se
evoluciona mediante una migración nueva revisada; no modificar migraciones aplicadas ni borrar IDs.
Las migraciones V1 de
los ejecutables anteriores no se concatenan ni se cambian sobre una base aplicada. Flyway debe
rechazar schemas no vacíos sin historial Core; no activar baseline-on-migrate ni ejecutar clean.

Si existen datos de los prototipos anteriores, el traslado se hace como una migración de datos
explícita antes del cambio de despliegue:

1. Detener escrituras y respaldar las bases Identity/Profile/Channels, sus historiales y los volúmenes de avatares/portadas.
2. Resolver las operaciones PENDING/EXPIRED con el prototipo anterior y sus canales externos;
   no convertir automáticamente pendientes en ACTIVE ni descartar reservas/objetos.
3. Crear Core en una base nueva y preparar un import por columnas explícitas, preservando userId,
   channelId, hashes de contraseña/sesión, fechas, perfiles/objetos y resultados ACTIVE retenidos.
   Crear el perfil default solo donde una cuenta activa anterior no lo tenía. Usar el mismo secreto
   HMAC para preservar fingerprints; validar el canal/propietario contra las fuentes anteriores.
   Importar descripción (null se transforma en cadena vacía), banner_key, banner_uri, channelVersion
   y fechas sin regenerar IDs; copiar objetos publicados a CHANNELS_BANNER_STORAGE y preservar URI
   /api/channels/banners/{key}. No importar cercas, proyecciones, eventos ni outboxes de Canales.
   Los permisos temporales vigentes solo se trasladan junto con el archivo y el vínculo owner/canal.
4. Validar cuenta/perfil/canal 1:1, unicidad, FK, referencias/checksums de avatares/portadas y reintentos con
   los IDs anteriores; comprobar login/logout en una copia. No importar outboxes de proyección local
   como autorización ni crear nuevos canales con IDs distintos para registros retenidos.
5. Cambiar rutas a Core tras comprobar la copia, conservando las fuentes para rollback. No iniciar
   simultáneamente prototipos antiguos escribiendo en las bases trasladadas.

Este cambio no ejecuta un import ni modifica bases existentes. Los scripts e historiales originales
permanecen recuperables en Git; el import depende de las fuentes reales y se valida antes de aplicarlo.
