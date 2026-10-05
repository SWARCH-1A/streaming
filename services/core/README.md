# Core

Un ejecutable Java 25 / Spring Boot 4.1.1, Maven Wrapper, PostgreSQL 18, JDBC y Flyway.
Seguridad, CSRF, configuración y gestor de transacciones compartidos. Decisiones:
[ADR-001](../../docs/adr/ADR-001-identity-plataforma-y-seguridad.md),
[ADR-002](../../docs/adr/ADR-002-profile-persistencia-y-avatar.md) y
[ADR-003](../../docs/adr/ADR-003-servicios-cohesivos.md) y
[ADR-004](../../docs/adr/ADR-004-canales-en-core.md).
La persistencia y validación de Catálogo se documentan en
[ADR-006 (propuesta para revisión)](../../docs/adr/ADR-006-taxonomia-en-core.md).

## Módulos e implementación

Código bajo `src/main/java/streaming/core`:

- `accounts/identity`: registro, credenciales, sesiones, idempotencia y cuotas SQL.
- `accounts/profile`: perfil, edición propia y archivos/permisos de avatar. Valida sesión localmente.
- `channels`: creación inicial transaccional, edición propia, portadas y bootstrap público por handle/owner, con vistas SQL públicas de Cuentas.
- `taxonomy`: catálogo público activo/versionado, semilla SQL, tombstones y validación local de IDs.
- `discovery`: proyección/consultas pendientes. Streaming vive en su servicio Rust (PR #7).
- `security`, `api`: infraestructura común, sin orquestación de casos de uso de dominio.

El registro pertenece a Cuentas: abre una transacción y llama interfaces locales de inicialización
de perfil/canal; cada repositorio escribe sus tablas. Confirma cuenta ACTIVE, perfil versión 0,
canal versión 0 y resultado idempotente juntos. No inicia sesión. Las FK y UNIQUE protegen relaciones
y unicidad. Un reintento recupera los mismos IDs; un fallo revierte todas las escrituras de negocio.
Las cuotas se guardan aparte para limitar intentos fallidos.

No hay HTTP entre estos módulos, worker de provisión, reservas PENDING ni outbox de identidad/perfil.
La consulta pública compone canal/handle/perfil en una sentencia SQL y devuelve `stream:null`
para el canal inicial sin configuración. Edición/portada usa la sesión y CSRF de Core; los PATCH
parciales bloquean la fila y solo incrementan channelVersion por cambios efectivos. Emisiones, su
composición en el canal y contexto Chat aún no se implementan; RF-011/RF-012 y el cumplimiento
completo de SPEC-03/P1 siguen pendientes. El contexto privado de propietario está implementado; proyección, inbox/outbox y composición de emisión siguen pendientes.

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

Una réplica Core. Varias requieren almacenamiento de imágenes compartido consistente o nuevo adaptador
por ADR. El contenedor usa UID 10001 y volúmenes `/data/avatars` y `/data/banners`. Persistir PostgreSQL y objetos juntos;
el HMAC se conserva entre reinicios para reintentos/cuotas. Rotarlo requiere una estrategia compatible
con la retención de treinta días, no cambiarlo accidentalmente en cada arranque.

El listener directo ignora `Forwarded`/`X-Forwarded-*`; cuotas usan la IP del socket. La integración
de reverse proxy debe configurar exclusivamente sus IP/redes confiables y verificar las cuotas
antes de habilitar tráfico tras proxy. Health: `GET /actuator/health`, readiness y liveness bajo
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
| `CORE_INTERNAL_DEVELOPMENT_HTTP` | `false`; solo el overlay de desarrollo aislado habilita HTTP explícitamente. |
| `CORE_INTERNAL_TLS_KEYSTORE` | Ruta dentro del proceso al PKCS12 con certificado válido para el hostname privado. |
| `CORE_INTERNAL_TLS_KEYSTORE_PASSWORD` | Contraseña privada del PKCS12, nunca versionada. |

Sin TLS/configuración válida el listener habilitado impide arrancar. El consumidor usa
`STREAMING_CORE_BASE_URL=https://<hostname-privado>:8082` y confía en su CA;
`STREAMING_SESSION_COOKIE_NAME=stream_session`. La entrada privada también sirve las lecturas
Core usadas por el cliente Rust, con la seguridad normal de cada ruta. CSRF solo se excluye
en `/internal/**`, que exige servicio/puerto; sigue vigente en mutaciones públicas.
No se usa `X-Forwarded-Port`/`Host` para decidir si una petición es privada.

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
Las asociaciones de StreamConfig, filtros PLAYABLE/AND y edición LIVE se verifican con Emisiones
y Discovery cuando se implementen. Las pruebas del proveedor no cierran esa aceptación integrada.

## Base nueva e historiales anteriores

`db/migration/V1__core.sql` es el baseline de una **base nueva**. El historial Flyway se ubica en
schema `core`; las tablas tienen schemas `identity`, `profile`, `channels` y `taxonomy`.
`V2__channel_editing_and_banners.sql` amplía la descripción a 500 caracteres y agrega clave/permisos
de portada; se aplica tanto a bases nuevas como a una base Core con V1 sin modificar su checksum.
`V3__taxonomy_catalog.sql` agrega categorías, etiquetas, versión y vistas públicas, con IDs semilla estables;
se aplica a bases nuevas o Core con V2. La semilla no se recrea en cada arranque. El vocabulario se
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
