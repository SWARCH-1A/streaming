# Perfil integrado persistente P1

Implementación de [SPEC-13](../../docs/spec-p1/spec_13_int.md) y
[ADR-014](../../docs/adr/ADR-014-perfil-integrado-tls-p1.md). Ocho contenedores con una réplica de
Core/Chat; el stack propio Streaming conserva exactamente tres: PostgreSQL, Streaming con adaptador
Media y MediaMTX. Java/Core, Rust/Streaming, Go/Chat y TypeScript/Web tienen responsabilidades reales.
PostgreSQL conserva dominios, estado y outboxes; Redis conserva mensajes, secuencia, dedupe y cuota,
con AOF antes del ACK. REST, GraphQL y WebSocket son los patrones HTTP que se ejercitan; la aceptación
académica de RNF-006 requiere confirmación del evaluador.

## Preparación y arranque

macOS/Linux o WSL, Python 3.12+, OpenSSL, Docker con Compose >=2.24.4, Node >=22.12 y pnpm 11.17.
Reservar espacio suficiente en el disco **de Docker**, además del host, para compilación, ambos SQL
y AOF. El build Rust requiere tiempo y memoria; las fuentes de prueba necesitan FFmpeg con libx264,
AAC, RTMPS y HTTPS. Las pruebas no comparten proyectos/volúmenes con desarrollo o producción.

Desde la raíz del checkout actual:

```sh
pnpm --dir apps/web install --frozen-lockfile
pnpm --dir apps/web build
python3 -m venv /tmp/streaming-p1-venv
/tmp/streaming-p1-venv/bin/pip install -r tests/integration/p1-domains/requirements.txt
python3 infra/p1/manage.py init
python3 infra/p1/manage.py build
python3 infra/p1/manage.py up
python3 infra/p1/manage.py status
curl --fail --cacert infra/p1/.state/ca.crt https://localhost:3443/api/taxonomy
/tmp/streaming-p1-venv/bin/python tests/integration/p1-delivery/verify.py --confirm-disposable
```

`up` inicia procesos; no afirma readiness de aplicaciones. Esperar las bases y Streaming saludables,
Taxonomy 200 por HTTPS y la prueba nominal antes de usar el perfil. Un readiness SQL no acredita Core
ni Chat. Core puede reconciliar después del arranque de Streaming. La prueba espera convergencia con
deadline y valida chat real, no solo que un socket esté abierto. `build` construye los fuentes actuales;
reutilizar imágenes históricas no acredita el checkout. No ejecutar pruebas concurrentes contra el
mismo proyecto. El puerto HTTPS predeterminado es 3443 y RTMPS 11936, ambos en loopback; se pueden
seleccionar `P1_HTTPS_PORT`/`P1_RTMPS_PORT` **antes de init**, conservándolos después. No se publican
puertos SQL, Redis, listeners privados ni control MediaMTX. La API pública de salud no se expone en Caddy.

Para pruebas desechables mientras se usa la instancia interactiva, seleccionar `P1_PROFILE=load`
en **todos** los comandos de gestión y suites. Este perfil usa proyecto `streaming-p1-load`, estado
privado `.state-load`, lock separado y puertos fijos HTTPS 3444/RTMPS 11937. Genera su propia CA,
secretos, redes y volúmenes. El perfil `default` conserva `.state`, `streaming-p1` y sus puertos
inicializados. Un perfil desconocido, propietario ajeno o path de estado distinto se rechaza.
Los navegadores de prueba usan un perfil independiente; las cookies host-only de localhost no
están aisladas por puerto. No usar ambos perfiles con la misma sesión del navegador interactivo.

```sh
P1_PROFILE=load python3 infra/p1/manage.py init
P1_PROFILE=load python3 infra/p1/manage.py up --replicas 2
curl --fail --cacert infra/p1/.state-load/ca.crt https://localhost:3444/api/taxonomy
P1_PROFILE=load /tmp/streaming-p1-venv/bin/python tests/integration/p1-delivery/load.py --confirm-disposable
P1_PROFILE=load python3 infra/p1/manage.py down
```

Las imágenes compiladas y el build Web proceden del mismo checkout; no hace falta duplicar su build.
`P1_PROFILE=load ... reset --confirm-disposable` elimina únicamente los volúmenes de `streaming-p1-load`.
Las pruebas de selección y protección se ejecutan con `python3 infra/p1/test_profiles.py` sin Docker.

## TLS, secretos y almacenamiento

`init` genera una CA local (30 días), certificados SAN por servicio (14 días), un PKCS12 Core y tokens
distintos por consumidor; es idempotente y preserva secretos existentes. Cada listener usa TLS nativo.
PostgreSQL usa verify-full, Redis rediss, Caddy verifica upstream y MediaMTX fija el fingerprint del
listener de autorización. Los probes y FFmpeg reciben la CA explícitamente, incluido OpenSSL para
playlists/segmentos HLS. No usar `curl -k`, ignoreHTTPSErrors ni deshabilitar verificación.
El perfil integrado sirve HLS fMP4 con segmentos de un segundo y una ventana de siete segmentos;
el stack de desarrollo mantiene LL-HLS. Se verifica el mismo máximo de cinco segundos al primer frame.

`.state` tiene modo 0700 y no se versiona. Env, configuración, diagnósticos y CA privada son 0600.
Certificados/claves de runtime tienen modo 0644 **dentro de ese padre privado** y se montan como
archivos individuales read-only para que los UID sin privilegios puedan leerlos. La CA privada nunca
se monta. El truststore Java conserva las CA públicas del JDK y agrega la CA local; no contiene claves privadas. `changeit` es su contraseña de integridad,
no una credencial privada. `JAVA_TOOL_OPTIONS` puede mostrarla al arrancar Java.

No se instala la CA en el sistema operativo. Para uso interactivo, importar únicamente `ca.crt` en un
perfil de navegador dedicado o usar certificados de una CA ya confiable; registrar versión y confianza
configurada. La suite automatizada no acredita versiones estables instaladas ni lector de pantalla.
No publicar `.state`, cookies, DTO owner, URLs RTMPS con clave, logs multimedia, HAR o traces. Los
errores de comandos se capturan en `.state/diagnostics.log`, que debe revisarse localmente.

Este perfil selecciona **filesystem explícitamente**. Avatares/portadas viven en volúmenes compartidos
por las réplicas Core. S3 sigue siendo el default del servicio; para un bucket externo privado configurar
`CORE_IMAGE_S3_BUCKET`, `CORE_IMAGE_S3_REGION`, prefijos `CORE_IMAGE_S3_AVATAR_PREFIX` y
`CORE_IMAGE_S3_BANNER_PREFIX`, y credenciales temporales AWS por entorno/cadena SDK. Endpoint compatible
opcional solo HTTPS, con path-style si el proveedor lo requiere. Ejecutar build/up/status/down con
`--s3`; no cambiar de proveedor sobre datos existentes sin migrar sus objetos. La validación estructural
del manifest no demuestra permisos ni recuperación de un bucket real.

## Reinicio, réplicas y recuperación

```sh
python3 infra/p1/manage.py up --replicas 2
python3 infra/p1/manage.py status
python3 infra/p1/manage.py down
python3 infra/p1/manage.py up --replicas 1
```

Con dos réplicas Core/Chat hay diez contenedores en el sistema completo; Streaming sigue teniendo tres.
Core comparte SQL, HMAC de cuotas, cookies/sesiones y almacenamiento de imágenes. Chat comparte Redis
para orden, cuota, dedupe, AOF y fan-out; el socket WS sigue en su réplica mientras vive. Caddy resuelve
los servicios por DNS de Compose; un cliente se reconecta tras perder su socket. No cambiar frontend.
El proxy confiable de Core se obtiene de la IP real de Caddy en cada `up`.

`down` conserva volúmenes y secretos. Eliminar datos solo en este proyecto desechable:

```sh
python3 infra/p1/manage.py reset --confirm-disposable
python3 infra/p1/manage.py up
```

Reset no borra `.state` ni toca proyectos ajenos. Detener fuentes FFmpeg antes de down/reset. La prueba
de recuperación crea únicamente datos ficticios, inyecta un trigger de rollback acotado a su handle y
lo retira en finally, pierde la respuesta de registro y reintenta la misma clave. Nunca fuerza LIVE por
SQL. Reiniciar Streaming incluye su adaptador; el generador de vídeo permanece fuera del contenedor.

Para backup consistente detener la lógica que escribe, usar pg_dump por base/propietario y copiar AOF
completo con Redis detenido; cifrar/proteger los backups igual que los datos. Restaurar en volúmenes
vacíos y comprobar IDs, sesiones, mensajes y objetos antes de afirmar recuperación. Un snapshot
Streaming recupera inventario/lifecycle Chat, nunca mensajes perdidos. La retención de Chat permanece
la de ADR-010. Certificados deben renovarse antes de expirar conservando credenciales, CA confiada,
SAN y configuración; recrear/reiniciar contenedores para recargar certificados/fingerprint. Eliminar
`.state` sobre bases existentes cambia contraseñas y rompe acceso: no es un procedimiento de renovación.

## Alcance de aceptación

La prueba TLS/reinicio no sustituye el perfil completo 60 s warm-up + 600 s medidos, cinco fuentes
720p30, 100 players, 10 req/s API, leases/heartbeats separados y 20 mensajes/s, ni CA-10/15/16. Los
resultados por criterio y commit se registran en Plane. Carga reducida, mocks, tests locales, una réplica
arrancada o schemas válidos no cierran esas puertas. SPEC-08 manual, navegador estable y RNF-006 se
mantienen explícitos cuando falta evidencia.

Las suites de réplicas y carga, sus dependencias, reset del fixture y formato de evidencia están
explicados en [tests/integration/p1-delivery](../../tests/integration/p1-delivery/README.md).
