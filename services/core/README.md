# Core

Core reúne cuentas, perfiles, canales, catálogo y descubrimiento en un ejecutable Java 25
con Spring Boot 4.1.1. Usa Maven Wrapper, PostgreSQL 18, JDBC y Flyway. Los módulos comparten
seguridad, CSRF, configuración y gestor de transacciones.

Las decisiones técnicas están en:
[ADR-001](../../docs/adr/ADR-001-identity-plataforma-y-seguridad.md),
[ADR-002](../../docs/adr/ADR-002-profile-persistencia-y-avatar.md),
[ADR-004](../../docs/adr/ADR-004-canales-en-core.md),
[ADR-005](../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md) y
[ADR-009](../../docs/adr/ADR-009-s3-image-storage.md).
La persistencia y validación de Catálogo se documentan en
[ADR-006 (propuesta para revisión)](../../docs/adr/ADR-006-taxonomia-en-core.md) y las de Descubrimiento en
[ADR-008](../../docs/adr/ADR-008-descubrimiento-en-core.md).

## Módulos e implementación

Código bajo `src/main/java/streaming/core`:

- `accounts/identity`: registro, credenciales, sesiones, recuperación de contraseña, idempotencia, cuotas SQL y outbox de correo.
- `accounts/profile`: perfil, edición propia y archivos/permisos de avatar. Valida sesión localmente.
- `channels`: creación inicial transaccional, edición propia, portadas y bootstrap público por handle/owner, con vistas SQL públicas de Cuentas.
- `taxonomy`: catálogo público activo/versionado, semilla SQL, tombstones y validación local de IDs.
- `discovery`: GraphQL público (`streams`, `channels`), inbox/proyección SQL de los snapshots públicos de Streaming y reconstrucción desde su corte consistente ([ADR-008](../../docs/adr/ADR-008-descubrimiento-en-core.md)).
- `streaming`: documentación de frontera; control de emisiones en el [servicio Rust](../streaming/README.md). Los contextos privados owner/catálogo están implementados en Core; el contexto Chat compone identidad/perfil locales con estado/timeline actual Streaming.
- `security`, `api`: infraestructura común, sin orquestación de casos de uso de dominio.

El registro pertenece a Cuentas: abre una transacción y llama interfaces locales de inicialización
de perfil/canal; cada repositorio escribe sus tablas. Confirma cuenta ACTIVE, perfil versión 0,
canal versión 0 y resultado idempotente juntos. No inicia sesión. Las FK y UNIQUE protegen relaciones
y unicidad. Un reintento recupera los mismos IDs; un fallo revierte todas las escrituras de negocio.
Las cuotas se guardan aparte para limitar intentos fallidos.

Los módulos se comunican mediante interfaces locales. La provisión de cuenta/perfil/canal no
necesita un worker ni un outbox. El outbox de recuperación guarda exclusivamente la intención
de entrega de correo a otro proceso, conforme a ADR-017 y al broker de ADR-016.
La consulta pública compone canal/handle/perfil en una sentencia SQL y un batch autoritativo
Streaming. La ausencia confirmada devuelve `stream:null`, `streamStatusFresh=true` y OFFLINE;
una dependencia fallida devuelve UNKNOWN/false preservando los datos locales. Edición/portada usa la sesión y CSRF de Core; los PATCH
parciales bloquean la fila y solo incrementan channelVersion por cambios efectivos. El contexto Chat autentica cada mensaje y toma un snapshot nuevo de autor/estado/timeline; no
replica permisos en Discovery. Las pruebas entre proveedores están en
[la suite P1](../../tests/integration/p1-domains/README.md); Web y carga se verifican por separado.

## Configuración

### Recuperación de contraseña

`V9__credential_recovery.sql` añade el estado de tokens, idempotencia y outbox conservando
las cuentas y sesiones existentes. La política vigente está en
[ADR-017](../../docs/adr/ADR-017-recuperacion-de-credenciales.md).

| Variable | Uso |
| --- | --- |
| CORE_RECOVERY_ENABLED | `true` habilita solicitud, confirmación y publicación de recuperación; default `false` en Core aislado |
| CORE_RECOVERY_ENCRYPTION_KEY | Base64 de 32 bytes aleatorios; clave AES-256-GCM independiente y persistente |
| CORE_RECOVERY_SERVICE_TOKEN | Secreto privado de entrega para Notificaciones, identidad lógica `recovery-email`, distinto de Streaming/Catálogo/Chat |
| WEB_ORIGIN | Origen HTTPS usado para el enlace; HTTP se admite únicamente en loopback para desarrollo |

Las dos APIs públicas requieren CSRF e Idempotency-Key UUID. `/internal/core/recovery-deliveries/*`
solo está disponible en el listener privado mediante `X-Service-Name: recovery-email` y su
`X-Service-Token`, con la pareja recoveryId/deliveryId e intento monotónico 1..2147483647. Nunca admite una sesión de
usuario como credencial de servicio. El consumidor de Notificaciones usará claim/result y no
leerá las tablas Core; el nombre lógico de la credencial no selecciona su tecnología.

Solicitud, confirmación y contratos privados están implementados. El publicador usa el puerto
`RecoveryPublisher`: solo confirma el outbox después del ACK durable; un fallo recupera la misma
identidad con lease de 60 s y backoff 1/2/5/10 s. `RabbitRecoveryPublisher` publica el cuerpo
original con confirmación y detección de mensajes sin ruta, hacia `notifications.recovery.email`
del broker compartido (ADR-016). El consumidor, almacenamiento de intentos y SMTP están pendientes
del responsable de Notificaciones; el [contrato de integración](../../docs/contratos_p2_eventos.md#integración-de-recuperación-entre-cuentas-y-notificaciones)
define lo que debe consumir. Publicar el trabajo no acredita entrega de correo. En instalaciones existentes,
configurar la clave sin sustituir secretos ni borrar `.state-local` o bases de datos.

`RecoveryIT` verifica uniformidad, cuotas, consumo concurrente, revocación, rollback, aislamiento
del listener, reintentos privados y recuperación del outbox sobre PostgreSQL desechable.

### Variables comunes

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
| CORE_STREAMING_BASE_URL | URL base del listener privado Streaming para canal, contexto Chat y corte Discovery. HTTPS por defecto; vacía deshabilita las lecturas: canal UNKNOWN, nuevo contexto 503 y reconstrucción deshabilitada |
| CORE_STREAMING_CONSUMER_TOKEN | Secreto de 32+ caracteres enviado como `Authorization: Bearer` a las lecturas privadas; mismo valor que `STREAMING_CORE_CONSUMER_TOKEN` en Streaming. Sin él no hay reconstrucción. Nunca se registra |
| CORE_STREAMING_DEVELOPMENT_HTTP | `false`; `true` permite HTTP solo en desarrollo aislado |
| CHAT_CORE_SERVICE_TOKEN | Credencial distinta de las dos Streaming; habilita solo message-context y snapshot Chat en el listener privado |
| DISCOVERY_RECONCILE_INTERVAL | Duración ISO-8601 de inicio a inicio entre cortes completos; default `PT4S`. Streaming conserva cada corte un día: súbela si el volumen preocupa, sabiendo que la ausencia de una configuración solo se confirma con un corte de menos de 5 s (continua mientras un corte tarde menos de un segundo; si no, esos canales quedan `UNKNOWN` en parte del ciclo) |
| CORE_TRUSTED_PROXIES | CIDR separados por coma de los reverse proxies cuyo `X-Forwarded-For` se acepta (de derecha a izquierda). Vacío (default) usa siempre la IP del socket; una lista mal formada impide arrancar |

El proveedor S3 usa el credential provider chain del AWS SDK: en despliegue se recomienda un rol IAM
de solo lectura/escritura limitado al bucket y prefijos; localmente se pueden inyectar credenciales
temporales por `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` y `AWS_SESSION_TOKEN`. No guardar claves
en el repositorio. El bucket es privado: los objetos se leen por los endpoints públicos estables de
Core, no por una ACL pública ni una URL directa del bucket. Los prefijos de avatar y portada deben
ser distintos. El bucket debe existir y estar configurado antes de iniciar Core. Para un proveedor
compatible con S3, configura también su endpoint y path-style según lo requiera. Ver
[ADR-009](../../docs/adr/ADR-009-s3-image-storage.md) para configurar el proveedor.

Para el proveedor `filesystem`, el contenedor usa UID 10001 y volúmenes `/data/avatars` y
`/data/banners`; una réplica Core requiere almacenamiento compartido consistente al escalar. Con
`s3`, las réplicas acceden al mismo bucket y no requieren compartir esos volúmenes. Persistir
PostgreSQL y objetos como conjunto recuperable;
el HMAC se conserva entre reinicios para reintentos/cuotas. Rotarlo requiere una estrategia compatible
con la retención de treinta días, no cambiarlo accidentalmente en cada arranque.

Si el par directo no es un proxy confiable, Core usa la IP del socket para las cuotas.
Configura en `CORE_TRUSTED_PROXIES` únicamente las IP o redes de los proxies permitidos;
Cuentas y Discovery comparten esa resolución de IP. Comprueba las cuotas antes de habilitar
tráfico mediante el proxy.

La salud se consulta en `GET /actuator/health`, y readiness y liveness en
`/actuator/health/{readiness,liveness}`. Las rutas privadas de contexto solo están disponibles
en el conector privado autenticado.

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

### Descubrimiento (SPEC-07)

`POST /api/discovery/graphql` es público, anónimo y de solo lectura: `streams` (solo `LIVE` + `PLAYABLE` con
observación de menos de 5 s) y `channels` (cuenta, perfil y canal siempre completos; estado `LIVE`/`OFFLINE`/`UNKNOWN`).
Reglas, errores y cursores en [SPEC-07](../../docs/spec-p1/spec_07_disc.md), el
[contrato](../../docs/contratos_modelo_datos.md) y [ADR-008](../../docs/adr/ADR-008-descubrimiento-en-core.md).
La API admite un cuerpo de hasta 16 KiB y `Content-Type: application/json` (415 si no); una sola `query` con las raíces
`streams`/`channels` una vez, sin alias, fragments ni introspección, hasta 50 filas por conexión y 100 por solicitud
(422 `QUERY_LIMIT_EXCEEDED`); JSON, sintaxis o variables inválidos son 400; los errores de campo `INVALID_FILTER`,
`INVALID_LIMIT` e `INVALID_CURSOR` responden HTTP 200 con `extensions.httpStatus=422` y datos parciales;
429 con `Retry-After` por IP (ráfaga de 20 que recarga 10/s y tope de 600 por 60 s, compartidos en SQL Core entre réplicas).
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

La base actual está vacía: se inicializa con Flyway V1 y no requiere importar datos ni archivos de
prototipos anteriores. La secuencia anterior solo describe evolución del esquema Core, no una
migración de datos.

## Transporte P2 por RabbitMQ

CORE_EVENTS_TRANSPORT=http (por defecto) conserva recepción HTTP P1. Con amqp, consume core.lifecycle/core.discovery mediante Spring AMQP y deshabilita la entrega HTTP de eventos. El inbox P2 es SQL local sin TTL automático; la barrera de ciclo no reemplaza metadatos/contadores. Configurar host/puerto/vhost/usuario/password spring.rabbitmq y truststore JVM con CA/hostname verificados.

BrokerEventIngestService escribe mediante los puertos Discovery BrokerEventStore y ProjectionStore.
JdbcBrokerEventStore posee el SQL del inbox P2 y la barrera de ciclo; JdbcProjectionStore posee
el de proyección y conflictos. La aplicación mantiene una sola TransactionTemplate para ambos
repositorios; la confirmación AMQP ocurre después del commit. No hay transacciones independientes
por repositorio ni cambios en los envelopes del contrato.

BrokerRepositoryBoundaryTest comprueba la dependencia de esos puertos. BrokerEventIngestIT usa
PostgreSQL desechable para verificar dedupe, versiones, conflictos, concurrencia y rollback de inbox
y efectos ante un fallo SQL posterior a insertar o actualizar ciclo/proyección. Se ejecutan con
las suites Unit/Integration anteriores.

V8__broker_lifecycle_conflicts.sql amplía los motivos de conflicto de Discovery con
LIFECYCLE_CONFLICT, conservando los de P1. Se aplica sobre V7 sin modificar migraciones anteriores;
la prueba de actualización verifica conservación del inbox, ciclo y checksums existentes.

Ver [perfil compartido](../../infra/broker/README.md) y [ADR-016](../../docs/adr/ADR-016-rabbitmq-eventos-p2.md).

## Administración P2

Crear primero una cuenta normal desde Web. El operador ejecuta el script sobre PostgreSQL Core
con el ID de esa cuenta ACTIVE (nunca una contraseña). Desde la raíz del repositorio, en WSL:

```sh
docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml exec -T core-db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v target_user="$1"' sh usr_ejemplo < services/core/scripts/provision-admin.sql
```

Sustituir `usr_ejemplo` por el ID de la cuenta destinada a operar. El script requiere acceso de
operador a la base; no se expone por HTTP. Valida la cuenta, provisiona ADMIN, audita y revoca
sesiones anteriores. Volver a iniciar sesión y abrir `/admin` desde el origen público.
Si se repite no duplica la auditoría de provisión; vuelve a revocar sesiones existentes.

V11 incorpora seguimiento y autopublicación de canal; V10 queda reservada a Administración.
Si una instalación experimental aplicó `V10__channel_follows.sql`, detener la actualización y
conservar el volumen y un respaldo: ese historial necesita una transición explícita antes de
aplicar V10 de develop y V11. No usar `repair` para ocultar la colisión ni borrar datos.

V10 preserva identidades/perfiles/canales y asigna USER/ACTIVE/version 0 a cuentas previas.
V9 corresponde exclusivamente a recuperación de credenciales (ADR-017); Administración
usa V10 y ADR-019. Antes de actualizar una base de una revisión experimental de este PR,
consultar `SELECT version,description,checksum FROM flyway_schema_history ORDER BY installed_rank;`.
Si V9 indica `account administration`, detener la actualización y conservar el volumen:
su historial no corresponde a develop. No ejecutar `repair`, borrar volúmenes ni sustituir
checksums para ocultar esa colisión; acordar una transición respaldada de esa base aparte.
Las pruebas cubren actualización desde V8 y desde V9 de recuperación, además de instalación limpia.
No modifica migraciones aplicadas. El cambio de estado usa cookie, CSRF, versión e idempotencia.
Los datos administrativos solo se obtienen con rol válido. `CORE_EVENTS_TRANSPORT=amqp` activa
el outbox mediante el broker existente; el perfil HTTP P1 conserva pendientes los eventos.
No borrar registros pendientes para recuperar una caída. La retirada de contenido, consumidores
de suspensión y revocación de URLs/cachés de imágenes siguen pendientes de P2-17.

Pruebas: `./mvnw -P integration verify` (Java 25 y Docker); las clases AdministrationIT y
AdministrationInfrastructureIT usan proveedores desechables. Un resultado Core no acredita
corte de emisiones ni acceso revocable integrado en otros servicios.
