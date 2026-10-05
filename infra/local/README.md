# Entorno local con Docker

`compose.core.yaml` inicia Core y PostgreSQL 18. Core incluye Catálogo en el mismo ejecutable;
Chat, Media, Web y proxy se incorporan al implementar sus SPEC/ADR. Se necesita Git, PowerShell
y Docker Desktop iniciado con contenedores Linux. JDK 25 y Maven 3.9.11 se ejecutan dentro de Docker.
Definición y variables de proceso: [Core](../../services/core/README.md).

## Preparar y arrancar

En un checkout nuevo en Windows, conservar LF en el wrapper Linux:

```powershell
git -c core.autocrlf=false clone --branch develop https://github.com/SWARCH-1A/streaming.git streaming
Set-Location streaming
```

Desde la raíz del checkout existente:

```powershell
.\infra\local\init-env.ps1
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
`/actuator/health/readiness` y `/actuator/health/liveness`. Flyway conserva V1/V2 aplicadas y agrega V3;
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
`PROFILE_AVATAR_STORAGE=/data/avatars`, `PROFILE_AVATAR_PUBLIC_BASE=/api/profile/avatars`,
`CHANNELS_BANNER_STORAGE=/data/banners` y `CHANNELS_BANNER_PUBLIC_BASE=/api/channels/banners`.
El puerto interno Core sigue siendo 8081; `PORT` configura el proceso Java y no es `CORE_PORT`.
La aplicación Java no carga un `.env` automáticamente: este flujo lo entrega explícitamente a Compose.
Catálogo no añade variables ni credenciales. Web aún no tiene framework ni `.env` definidos.

## Pruebas dentro de Docker

### Conexión privada Core–Streaming

`init-env.ps1` agrega `CORE_STREAMING_SERVICE_TOKEN` si falta y conserva los secretos existentes.
Catálogo sigue usando SQL/Core; este secreto autentica la relación entre procesos. El listener
privado está deshabilitado en Compose básico. Para desarrollo aislado se habilita explícitamente:

```powershell
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml -f infra/local/compose.core-private-dev.yaml up --build -d
```

Streaming debe unirse a `streaming-core_default`, usar `STREAMING_CORE_BASE_URL=http://core:8082`,
`STREAMING_SESSION_COOKIE_NAME=stream_session` y recibir en `STREAMING_CORE_SERVICE_TOKEN` el mismo
valor privado de `CORE_STREAMING_SERVICE_TOKEN`. No copiarlo a ejemplos ni al chat. El puerto 8082
no se publica en el host; 8081 rechaza `/internal/*` incluso con token válido.

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

## Plane mediante MCP

Plane organiza trabajo y no participa en el arranque o pruebas de Core. El servidor oficial usa
`uvx plane-mcp-server stdio`; su registro local en la configuración del cliente MCP contiene:

```toml
[mcp_servers.plane]
command = "uvx"
args = ["plane-mcp-server", "stdio"]
enabled = false
env_vars = ["PLANE_API_KEY"]

[mcp_servers.plane.env]
PLANE_BASE_URL = "https://plane.ivant.dev"
PLANE_WORKSPACE_SLUG = "sw-architecture-2026-2"
```

En Codex esta configuración vive en el archivo local del usuario `.codex/config.toml`; si `uvx`
no está en su PATH, `command` debe usar la ruta absoluta de la instalación local. `PLANE_API_KEY`
pertenece al entorno privado del cliente MCP, nunca al repositorio ni a `infra/local/.env`.
El servidor no carga ese `.env` automáticamente. Habilitar el registro solo cuando la credencial
esté disponible en el proceso cliente; reiniciar el cliente tras modificar sus variables de usuario.
La ausencia de credencial mantiene Plane deshabilitado y permite continuar con desarrollo/pruebas.
Usar exclusivamente MCP y el proyecto STREAMING; conservar responsables y relaciones existentes.

## Detener conservando datos

```powershell
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml down
```

Los volúmenes SQL, avatares y portadas son separados. `down` conserva los tres; no añadir `-v`
para detener una instalación con datos que deban conservarse. Respaldar SQL y objetos juntos.
El traslado de prototipos anteriores se describe en el runbook Core y no se ejecuta automáticamente.
