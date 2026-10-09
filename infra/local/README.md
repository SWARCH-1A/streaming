# Entorno local con Docker

`compose.core.yaml` inicia Core y PostgreSQL 18. Core incluye Catálogo en el mismo ejecutable;
Chat, Web y proxy se integran conforme a sus SPEC/ADR. El stack propio [Streaming/Media](../../services/streaming/README.md) tiene tres contenedores (PostgreSQL, Streaming con adaptador y MediaMTX), según ADR-011; se arranca desde services/streaming y su conexión con Core se configura como se indica más abajo. Se necesita Git, PowerShell
y Docker Desktop iniciado con contenedores Linux. JDK 25 y Maven 3.9.11 se ejecutan dentro de Docker.
Definición y variables de proceso: [Core](../../services/core/README.md).
`compose.chat.yaml` añade Chat y su Redis con AOF. Definición y variables de Chat:
[Chat](../../services/chat/README.md).

## Preparar y arrancar

En un checkout nuevo en Windows, conservar LF en el wrapper Linux:

```powershell
git -c core.autocrlf=false clone --branch develop https://github.com/SWARCH-1A/streaming.git streaming
Set-Location streaming
```

Desde la raíz del checkout existente:

S3 es el proveedor predeterminado. Después de crear `.env`, completa un bucket privado existente,
su región y las credenciales del proveedor antes de iniciar Core. Si ya tienes un `.env` de una versión
anterior, cambia `CORE_IMAGE_STORAGE_PROVIDER=filesystem` a `CORE_IMAGE_STORAGE_PROVIDER=s3`;
`init-env.ps1` conserva la configuración existente.

```powershell
.\infra\local\init-env.ps1
# Completa infra/local/.env con la configuración del bucket y del proveedor S3.
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml up --build -d
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8081/api/taxonomy
```

`init-env.ps1` prepara `.env` a partir de `.env.example` y genera los secretos locales sin mostrarlos;
repetirlo conserva la configuración existente. El primer arranque aplica Flyway y la semilla de siete
categorías/ocho etiquetas, con `catalogVersion=1`. Después de editar código o migraciones, repetir
`up --build -d` recompila y reemplaza Core. Un reinicio sin rebuild sigue usando la imagen anterior.

Core escucha en `localhost:8081`, PostgreSQL en `localhost:5440`. Cambiar `CORE_PORT`/`CORE_DB_PORT`
evita conflictos; adaptar las URL de comprobación cuando cambie `CORE_PORT`. Salud adicional:
`/actuator/health/readiness` y `/actuator/health/liveness`. Flyway conserva migraciones aplicadas y agrega V3/V4;
no se requiere borrar volúmenes para incorporar Catálogo.

## Configuración local

Solo `infra/local/.env` configura este Compose. Está ignorado por Git; no crear un secreto o archivo
de configuración por módulo Core. Los nombres exactos del ejemplo son:

| Variable | Valor o uso |
| --- | --- |
| `CORE_DB_PASSWORD` | Secreto local obligatorio, generado por el inicializador. |
| `CORE_RATE_LIMIT_HMAC_SECRET` | Secreto local obligatorio, al menos 32 bytes; conservar entre reinicios. |
| `CORE_DB_PORT` | `5440`, puerto PostgreSQL publicado en localhost. |
| `CORE_PORT` | `8081`, puerto Core publicado en localhost. |
| `CORE_SECURE_COOKIE` | `false` para este desarrollo HTTP; `true` bajo HTTPS. |
| `WEB_ORIGIN` | `http://localhost:3000`, origen reservado para Web. |

Compose inyecta además `CORE_DB_URL=jdbc:postgresql://postgres:5432/core`, `CORE_DB_USER=core`,
`CORE_IMAGE_STORAGE_PROVIDER=s3` por defecto, `PROFILE_AVATAR_STORAGE=/data/avatars`,
`PROFILE_AVATAR_PUBLIC_BASE=/api/profile/avatars`, `CHANNELS_BANNER_STORAGE=/data/banners` y
`CHANNELS_BANNER_PUBLIC_BASE=/api/channels/banners`. Los directorios y volúmenes de imágenes solo
se usan si se selecciona explícitamente `filesystem`.
El puerto interno Core sigue siendo 8081; `PORT` configura el proceso Java y no es `CORE_PORT`.
La aplicación Java no carga un `.env` automáticamente: este flujo lo entrega explícitamente a Compose.
Catálogo no añade variables ni credenciales. Web aún no tiene framework ni `.env` definidos.

### S3 para avatares y portadas

El Compose local usa S3 por defecto. Antes de iniciar Core, crea o elige un bucket privado existente
(puede estar vacío) y configura en `infra/local/.env` `CORE_IMAGE_S3_BUCKET` y `CORE_IMAGE_S3_REGION`.
Para proveedores compatibles, configura también `CORE_IMAGE_S3_ENDPOINT` y activa
`CORE_IMAGE_S3_PATH_STYLE_ACCESS=true` si lo requiere el proveedor. La aplicación usa la cadena de
credenciales del AWS SDK: en Compose puedes proporcionar credenciales temporales mediante
`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` y `AWS_SESSION_TOKEN`; en despliegue usa un rol IAM
limitado al bucket y prefijos. No se requiere hacer público el bucket. El contenedor falla al iniciar
si el proveedor predeterminado S3 no tiene un nombre de bucket válido.

Para iniciar sin S3, cambia explícitamente `CORE_IMAGE_STORAGE_PROVIDER=filesystem` en `.env`; el
almacenamiento local usa los volúmenes `/data/avatars` y `/data/banners`.

Las respuestas siguen publicando `/api/profile/avatars/{key}` y `/api/channels/banners/{key}`;
Core lee el objeto privado y responde la imagen. La base actual está vacía, así que se puede usar un
bucket sin objetos; no hay claves ni archivos referenciados que trasladar. Consulta el
[ADR-009](../../docs/adr/ADR-009-s3-image-storage.md) para la decisión y configuración.

## Pruebas dentro de Docker

### Conexión privada Core–Streaming

`init-env.ps1` agrega los tokens Streaming/Chat si faltan
o están vacíos, y conserva los secretos existentes, incluidos archivos LF/CRLF. Son secretos distintos:
el primero permite los POST owner-context y catalog-values; el segundo solo catalog-values y es
opcional para Core. Ambos pertenecen a la relación entre procesos. El listener
privado está deshabilitado en Compose básico. Para desarrollo aislado se habilita explícitamente:

```powershell
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml -f infra/local/compose.core-private-dev.yaml up --build -d
```

Streaming debe unirse a `streaming-core_default`, usar `STREAMING_CORE_BASE_URL=http://core:8082`,
`STREAMING_SESSION_COOKIE_NAME=stream_session` y recibir en `STREAMING_CORE_SERVICE_TOKEN` el mismo
valor privado de `CORE_STREAMING_SERVICE_TOKEN`. No copiarlo a ejemplos ni al chat. El puerto 8082
no se publica en el host; 8081 rechaza `/internal/*` incluso con token válido.

El cliente Rust actual conserva el token principal porque necesita ambas rutas. No sustituirlo
por el token limitado al catálogo: los comandos de propietario recibirían 401. La credencial
opcional vacía queda deshabilitada; un valor inválido o igual al principal impide arrancar la
entrada privada. El secreto Chat también es opcional hasta conectar Chat; cuando se configura,
debe ser válido y distinto de los dos Streaming. Solo permite las rutas privadas Chat.
Los ejemplos versionados mantienen los secretos vacíos.

Para canal y contexto Chat, configurar CORE_STREAMING_BASE_URL con el listener privado Streaming
y CORE_STREAMING_CONSUMER_TOKEN con el mismo valor de STREAMING_CORE_CONSUMER_TOKEN. Core
usa HTTPS por defecto; el overlay private-dev habilita HTTP explícitamente. Timeout de sesión
200 ms incluyendo cuerpo, de canal 1 s; cuerpo máximo 64 KiB. Una falla de Streaming devuelve
UNKNOWN en el canal y rechaza el contexto nuevo con STREAMING_UNAVAILABLE.

Para TLS, reemplazar el último overlay por `infra/local/compose.core-private-tls.yaml` y añadir
al `.env` local `CORE_INTERNAL_TLS_KEYSTORE` (ruta absoluta al PKCS12 legible por UID 10001) y
`CORE_INTERNAL_TLS_KEYSTORE_PASSWORD`. El certificado debe corresponder al hostname privado y
su CA debe ser confiable para el consumidor; usar `https://<hostname-privado>:8082`. No usar ambos
overlays juntos ni publicar el conector privado a Internet. La suite genera un certificado de
prueba efímero y verifica HTTPS; no instala certificados personales.

Compatibilidad Rust y recuperación persistente: `./tests/contracts/test-streaming-core.ps1`.
Ese runner usa el proyecto aislado `taxonomy-contracts`, puertos locales 18081/15440 y datos ficticios;
no accede a los volúmenes de `streaming-core`.

Desde la raíz, o invocando el script por su ruta absoluta desde otro directorio:

```powershell
.\infra\local\test-core.ps1 -Mode Unit
.\infra\local\test-core.ps1
```

El modo predeterminado `Integration` ejecuta `sh ./mvnw -B verify -P integration`; incluye unitarias y
Testcontainers con PostgreSQL 18 efímero. `Unit` ejecuta `sh ./mvnw -B test`. Ambos usan
`eclipse-temurin:25-jdk` y cache Maven en el volumen `streaming-core-maven-cache`. No requieren arrancar
Compose ni cargar `.env`; las pruebas inyectan configuración propia y no acceden a la base local.
El script propaga el código de salida Docker/Maven y falla si las pruebas fallan.

Para integración se monta `/var/run/docker.sock`, se usa `DOCKER_HOST=unix:///var/run/docker.sock`
y `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`: PostgreSQL corre como contenedor hermano
en Docker Desktop. No se levanta otro daemon ni se desactiva la limpieza de Testcontainers.
El checkout se monta como `/workspace`; los tests no deben usar rutas Windows para montar fixtures
en contenedores secundarios. Referencia: [patrón oficial de Testcontainers](https://java.testcontainers.org/supported_docker_environment/continuous_integration/dind_patterns/).

El Dockerfile usa `package -DskipTests`; construir o arrancar Core no ejecuta estas pruebas.
Los resultados quedan bajo `services/core/target/surefire-reports` y `failsafe-reports` y no se
versionan. Solo una ejecución exitosa acredita los checks; el perfil integrado P1 de SPEC-13 se
verifica aparte cuando estén sus consumidores.

## Detener conservando datos

```powershell
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml down
```

Chat usa el listener privado `core:8082` en la misma red. `init-env.ps1` genera
CHAT_CORE_SERVICE_TOKEN y CHAT_SESSION_EVENTS_TOKEN distintos; Core recibe el primero y Streaming
el segundo. Ejemplo aislado HTTP (en despliegue usar TLS y CA confiables):

```sh
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml -f infra/local/compose.core-private-dev.yaml -f infra/local/compose.chat.yaml -f infra/local/compose.chat-private-dev.yaml up --build -d
curl http://localhost:8085/readyz
```

Chat publica 8085 (historial/WS) y 8086 (`/internal/chat/session-events`) solo en localhost. Redis
no se publica. El volumen `chat-redis` conserva mensajes con ACK ante reinicios. Las salas expiran
5 minutos después de terminar la sesión.
Los volúmenes SQL, avatares y portadas son separados; los dos últimos solo contienen datos cuando
`CORE_IMAGE_STORAGE_PROVIDER=filesystem`. `down` conserva los volúmenes; no añadir `-v` para detener
una instalación con datos que deban conservarse. Con S3, respaldar SQL y objetos del bucket juntos.
El traslado de prototipos anteriores se describe en el runbook Core y no se ejecuta automáticamente.
