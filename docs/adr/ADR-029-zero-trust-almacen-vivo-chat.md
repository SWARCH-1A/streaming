# ADR-029: Zero Trust en las conexiones internas, cerrando el Redis de Chat con identidad propia

- Estado: aceptada para integración local
- Fecha: 2026-11-21
- Responsable: Chat
- SDD/contratos afectados: SPEC-05 P2, SPEC-24 P2; RNF-025, RNF-027, RNF-041. Sin cambios en contratos HTTP, de eventos ni de Media.

## Contexto

El prototipo 3 pide responder escenarios de seguridad con patrones elegidos por el equipo. El
escenario 5 aplica **Zero Trust**: ningún componente obtiene acceso por estar dentro de la red de
Docker. Cada acceso debe presentar una identidad propia, verificarse y limitarse a lo que esa
identidad necesita.

Antes de esta decisión, el sistema ya aplicaba ese principio en casi todas sus conexiones internas:

| Conexión | Identidad que se verifica | Mínimo privilegio |
| --- | --- | --- |
| Servicio → listener privado de Core | `X-Service-Name` + `X-Service-Token`, comparación en tiempo constante, un token por servicio llamador | `PrivateCoreListener.permits`: cada servicio solo obtiene sus permisos (`CHAT_*` para Chat, `VOD_ACCESS` para VOD…); rechaza tokens reutilizados entre servicios |
| Servicio → VOD, Streaming y Chat (rutas `/internal/*`) | Token del llamador, uno por par de servicios: nombre y token en VOD y Chat, `Authorization: Bearer` comparado por hash en Streaming | Cada ruta compara con la credencial de su llamador; Chat monta `/internal/*` solo en su listener interno |
| Servicios → PostgreSQL | Usuario y contraseña propios en una base propia por servicio, TLS `verify-full` | Ningún servicio tiene credenciales de la base de otro (RNF-041) |
| Servicios → RabbitMQ | Usuario propio por servicio, AMQPS | Sin permiso de configurar (`^$`); escritura y lectura limitadas por expresión regular a sus colas e intercambio |
| VOD y Media → MinIO | Usuarios `vod` y `media-reader` | Políticas distintas de escritura y de lectura |
| Usuario → cualquier dominio | Sesión opaca de Core, validada por el dominio que ejecuta la operación (RNF-027) | El Gateway compone pantallas, pero no autoriza en nombre de otro dominio |

Había una excepción. El Redis del vivo de Chat (`chat-db`) cifraba con TLS, pero arrancaba con
`--tls-auth-clients no` y sin contraseña ni usuarios. Cualquier contenedor de la red podía leer,
modificar o borrar las salas, la cuota, los bloqueos copiados y el stream `chat:archive`, que es la
fuente del replay durable. Era el único almacén que confiaba en la ubicación de red.

## Decisión

1. **Redis sin usuario anónimo.** `chat-db` carga un archivo ACL (`--aclfile`) que desactiva el usuario
   `default`.
2. **Una identidad propia para Chat.** El usuario `chat` se autentica con una contraseña aleatoria de
   48 caracteres, `CHAT_REDIS_PASSWORD`, que genera `infra/local/chat_config.py` durante `init`. Chat
   la recibe en `CHAT_REDIS_URL` (`rediss://chat:<contraseña>@chat-db:6379/0`).
3. **Mínimo privilegio.** El usuario `chat` solo accede a claves `chat:*`, no tiene canales Pub/Sub
   (`resetchannels`) y tiene denegada la categoría `@dangerous` (`FLUSHALL`, `CONFIG`, `KEYS`, `ACL`,
   `INFO`, `CLIENT PAUSE`/`KILL`/`LIST`, `SHUTDOWN`…). También se le deniegan `SCRIPT FLUSH` y todos
   los subcomandos de `FUNCTION`: Chat ejecuta sus scripts Lua con `EVALSHA`/`EVAL` y no usa funciones.
4. **Sin secreto en el archivo ACL.** El archivo guarda solo el SHA-256 de la contraseña. La contraseña
   se guarda en `environment.env` (modo 600), como el resto de secretos locales, y Compose la entrega
   como variable de entorno a Chat (`CHAT_REDIS_URL`) y a `chat-db` (`REDISCLI_AUTH`, solo para la
   comprobación de salud), igual que las demás contraseñas del proyecto.
5. **Sin cambio en el código de Chat.** `redis.ParseURL` ya acepta usuario y contraseña en la URL.

La decisión aplica a la instalación raíz (`compose.yaml`, gestionada por `infra/local/dev.py`).

## Opciones consideradas

| Opción | Ajuste | Esfuerzo y riesgo |
| --- | --- | --- |
| **A. Cerrar el almacén que confiaba en la red, con identidad y ACL (elegida)** | Elimina la única conexión interna sin identidad y añade mínimo privilegio, que el Redis no tenía | Bajo: un archivo de configuración, una variable y un montaje; Chat no cambia. Requiere repetir `init` en instalaciones existentes |
| B. TLS mutuo (mTLS) en todas las conexiones internas | Daría identidad criptográfica a cada servicio y reforzaría los tokens | Alto: certificados de cliente y verificación en Java, Go, Rust, Node.js, Caddy y los scripts de inicio; riesgo alto antes de la entrega |
| C. Solo `requirepass` en Redis | Exige una credencial | Usa el usuario `default` con todos los permisos: no aplica mínimo privilegio |
| D. Documentar sin cambiar | Ninguno | Deja un almacén que cualquier contenedor puede leer y modificar |

## Consecuencias

- **Seguridad.** Un contenedor comprometido de la red ya no puede leer ni alterar el chat en vivo sin
  la credencial de Chat. Con esa credencial no puede borrar la base, cambiar la configuración ni tocar
  claves ajenas a `chat:*`.
- **Operación.** Tras actualizar el repositorio, `python3 infra/local/dev.py init` añade la contraseña y
  el ACL sin rotar los demás secretos. Si se omite, Compose se detiene con el mensaje
  `Run python3 infra/local/dev.py init` y no se pierden datos. Redis lee el ACL al arrancar: si `init`
  lo reescribe en una instalación que ya lo tenía, hay que reiniciar `chat-db`. El backup de
  `infra/local/backup.py` incluye el archivo ACL, y al restaurar un backup anterior a este ADR se
  genera una credencial propia para la copia restaurada.
- **Fallos.** Si un comando que Chat necesita quedara fuera del ACL, Redis responde `NOPERM` y Chat lo
  registra. La verificación integral comprueba que no ocurre en el envío, historial, moderación y replay.
- **Límites.** No hay TLS mutuo: un servicio que robe el token o la contraseña de otro puede
  suplantarlo. El perfil P1 (`infra/p1/core-chat.yaml`) y el Compose de desarrollo
  `infra/local/compose.chat.yaml` conservan su Redis sin credenciales. Mailpit, el capturador de
  correos de desarrollo, acepta conexiones sin credencial y no forma parte de un despliegue real. Los
  tokens entre servicios son estáticos durante la vida de la instalación. Quien controle Docker en el
  equipo anfitrión puede leer las variables de entorno de los contenedores, incluidas las contraseñas.

## Verificación

Con la instalación raíz arriba:

1. `redis-cli` desde otro contenedor de la red sin credenciales recibe `NOAUTH`; con una contraseña
   incorrecta, `WRONGPASS`.
2. El usuario `chat` recibe `NOPERM` en `FLUSHALL`, `CONFIG GET`, `SCRIPT FLUSH`, `FUNCTION FLUSH` y
   en claves fuera de `chat:*`.
3. Chat queda sano, y el envío, el historial y el replay funcionan en el stack integrado.
4. El listener privado de Core rechaza una llamada sin token y una con el token de otro servicio.

## Revisión

Revisar esta decisión si el sistema se despliega fuera de una red de un solo equipo, si se agrega un
almacén o servicio interno nuevo, o si el equipo dispone de tiempo para TLS mutuo (opción B). La
revisa la persona responsable de Chat con quien sea responsable de la integración.
