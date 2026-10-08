# Core

Un ejecutable Java 25 / Spring Boot 4.1.1, Maven Wrapper, PostgreSQL 18, JDBC y Flyway.
Seguridad, CSRF, configuración y gestor de transacciones compartidos. Decisiones:
[ADR-001](../../docs/adr/ADR-001-identity-plataforma-y-seguridad.md),
[ADR-002](../../docs/adr/ADR-002-profile-persistencia-y-avatar.md) y
[ADR-005](../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md) y
[ADR-004](../../docs/adr/ADR-004-canales-en-core.md).
La persistencia y validación de Catálogo se documentan en
[ADR-006 (propuesta para revisión)](../../docs/adr/ADR-006-taxonomia-en-core.md).

## Módulos e implementación

Código bajo `src/main/java/streaming/core`:

- `accounts/identity`: registro, credenciales, sesiones, idempotencia y cuotas SQL.
- `accounts/profile`: perfil, edición propia y archivos/permisos de avatar. Valida sesión localmente.
- `channels`: creación inicial transaccional, edición propia, portadas y bootstrap público por handle/owner, con vistas SQL públicas de Cuentas.
- `taxonomy`: catálogo público activo/versionado, semilla SQL, tombstones y validación local de IDs.
- `discovery`: proyección/consultas pendientes; Discovery recibirá proyección pública Streaming.
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
de Streaming en el canal, contexto Chat y proyección Discovery aún no se implementan; RF-011/RF-012
y el cumplimiento completo de SPEC-03/P1 siguen pendientes. El contexto privado de propietario
está implementado; proyección, inbox/outbox y composición de emisión siguen pendientes.

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
| CORE_IMAGE_STORAGE_PROVIDER | `s3` (predeterminado) o `filesystem` (selección explícita); elige el adaptador de avatares/portadas |
| CORE_IMAGE_S3_BUCKET | Bucket existente; obligatorio con el proveedor predeterminado `s3` |
| CORE_IMAGE_S3_REGION | Región para firmar llamadas S3; default `us-east-1` |
| CORE_IMAGE_S3_ENDPOINT | Endpoint opcional para S3 compatible; vacío usa AWS |
| CORE_IMAGE_S3_PATH_STYLE_ACCESS | `true` para endpoints compatibles que requieren path-style; default `false` |
| CORE_IMAGE_S3_AVATAR_PREFIX | Prefijo de objetos de avatar; default `avatars` |
| CORE_IMAGE_S3_BANNER_PREFIX | Prefijo de objetos de portada; default `banners` |
| PROFILE_AVATAR_STORAGE | Directorio persistente para el proveedor `filesystem` |
| PROFILE_AVATAR_PUBLIC_BASE | Prefijo API público (o CDN que proxifique Core); default /api/profile/avatars |
| CHANNELS_BANNER_STORAGE | Directorio persistente para el proveedor `filesystem` |
| CHANNELS_BANNER_PUBLIC_BASE | Prefijo API público (o CDN que proxifique Core); default /api/channels/banners |

El proveedor S3 usa el credential provider chain del AWS SDK: en despliegue se recomienda un rol IAM
de solo lectura/escritura limitado al bucket y prefijos; localmente se pueden inyectar credenciales
temporales por `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` y `AWS_SESSION_TOKEN`. No guardar claves
en el repositorio. El bucket es privado: los objetos se leen por los endpoints públicos estables de
Core, no por una ACL pública ni una URL directa del bucket. Los prefijos de avatar y portada deben
ser distintos. El bucket debe existir y estar configurado antes de iniciar Core. Para un proveedor
compatible con S3, configura también su endpoint y path-style según lo requiera. Ver
[ADR-008](../../docs/adr/ADR-008-s3-image-storage.md) para configurar el proveedor.

Para el proveedor `filesystem`, el contenedor usa UID 10001 y volúmenes `/data/avatars` y
`/data/banners`; una réplica Core requiere almacenamiento compartido consistente al escalar. Con
`s3`, las réplicas acceden al mismo bucket y no requieren compartir esos volúmenes. Persistir
PostgreSQL y objetos como conjunto recuperable;
el HMAC se conserva entre reinicios para reintentos/cuotas. Rotarlo requiere una estrategia compatible
con la retención de treinta días, no cambiarlo accidentalmente en cada arranque.

El listener directo ignora `Forwarded`/`X-Forwarded-*`; cuotas usan la IP del socket. La integración
de reverse proxy debe configurar exclusivamente sus IP/redes confiables y verificar las cuotas
antes de habilitar tráfico tras proxy. Health: `GET /actuator/health`, readiness y liveness bajo
`/actuator/health/{readiness,liveness}`. Las rutas privadas de contexto solo se habilitan en el conector privado autenticado.

## Ejecución local

[Compose](../../infra/local/README.md) inicia Core y PostgreSQL con volúmenes. S3 está seleccionado
por defecto: después de crear `.env`, configura un bucket privado existente y las credenciales del
proveedor antes de iniciar Core. Desde la raíz del repositorio, en Windows:

```powershell
.\infra\local\init-env.ps1
# Completa infra/local/.env con la configuración del bucket y del proveedor S3.
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
se aplica a bases nuevas o Core con V2. `V4__taxonomy_disjoint_ids.sql` reserva IDs/tipos en un registro
interno único, incluidos tombstones, sin cambiar datos ni catalogVersion. Una colisión preexistente
detiene la migración completa y requiere reconciliación revisada; no ejecutar repair/clean ni
renombrar datos automáticamente. La semilla no se recrea en cada arranque. El vocabulario se
evoluciona mediante una migración nueva revisada; no modificar migraciones aplicadas ni borrar IDs.
Las migraciones V1 de
los ejecutables anteriores no se concatenan ni se cambian sobre una base aplicada. Flyway debe
rechazar schemas no vacíos sin historial Core; no activar baseline-on-migrate ni ejecutar clean.

La base actual está vacía: se inicializa con Flyway V1 y no requiere importar datos ni archivos de
prototipos anteriores. La secuencia anterior solo describe evolución del esquema Core, no una
migración de datos.
