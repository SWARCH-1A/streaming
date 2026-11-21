# Escenario de seguridad 5: Zero Trust

## Escenario

Un atacante toma el control de un contenedor cualquiera de Vault tv, por ejemplo mediante una
vulnerabilidad en una dependencia, y desde la red interna de Docker intenta leer o modificar datos de
otro componente sin tener sus credenciales.

| Parte | Valor |
| --- | --- |
| Fuente | Atacante con control de un contenedor de la red interna, sin las credenciales de los demás componentes. |
| Estímulo | Conexiones directas desde la red interna a los almacenes de datos y a las APIs privadas de los servicios. |
| Artefacto | Redis de Chat, PostgreSQL de cada servicio, RabbitMQ, MinIO y los listeners privados de Core, Chat, VOD y Streaming. |
| Entorno | Operación normal, con el sistema desplegado con Docker Compose en una sola red. |
| Respuesta | Ninguno de los componentes del artefacto concede acceso por estar dentro de la red. Cada conexión exige la identidad propia de quien llama, y esa identidad solo obtiene los permisos que necesita. |
| Medida de respuesta | 0 de los almacenes y APIs internas del artefacto aceptan una conexión anónima; 100 % de los intentos sin credencial, con credencial incorrecta o con la credencial de otro servicio rechazados; 0 comandos administrativos permitidos a la identidad de Chat en Redis. |

## Problema de diseño

La red de Docker suele tratarse como zona de confianza: si un componente está dentro, se le deja
pasar. Con ese supuesto, comprometer un solo contenedor basta para llegar a los datos de todos.

Al revisar las conexiones internas encontramos que casi todas ya exigían identidad. Había una
excepción: **el Redis de Chat**, que guarda las salas en vivo, la cuota de mensajes, la copia de los
bloqueos y el stream `chat:archive` del que sale el replay. Cifraba con TLS, pero no pedía ninguna
credencial. Cualquier contenedor podía leer los mensajes, inyectar mensajes falsos o borrar todo.

## Tácticas aplicadas

- **Autenticar actores** (resistir ataques): cada servicio presenta su propia identidad en cada
  conexión: token propio hacia las APIs privadas y usuario propio en cada almacén.
- **Autorizar actores** (resistir ataques): cada identidad tiene solo los permisos que necesita
  (permisos por servicio en Core, una ruta por llamador en VOD y Chat, ACL en Redis y RabbitMQ,
  políticas en MinIO).
- **Limitar el acceso** (resistir ataques): las APIs internas viven en listeners separados de los
  públicos, y la identidad de Chat en Redis no tiene comandos administrativos ni canales Pub/Sub.
- **Separar entidades** (resistir ataques): cada servicio tiene su propia base de datos y sus
  propias credenciales; ninguno puede usar las de otro.
- **Cambiar la configuración por defecto** (resistir ataques): Redis trae un usuario `default` sin
  contraseña y con todos los permisos; en el P3 ese usuario queda desactivado.

## Patrón aplicado: Zero Trust

**Problema.** Un perímetro de red no protege frente a un componente interno comprometido.

**Solución.** No confiar en la ubicación de red. Cada acceso se autentica con una identidad propia,
se autoriza con el mínimo privilegio y se verifica en cada conexión, no una sola vez en el borde.

### Cómo se aplica en Vault tv

| Conexión interna | Identidad que se verifica | Mínimo privilegio |
| --- | --- | --- |
| Servicio → listener privado de Core (`core:8082`) | `X-Service-Name` y `X-Service-Token`, un token por servicio llamador, comparados en tiempo constante | Cada servicio solo obtiene sus permisos (`PrivateCoreListener.permits`); Core rechaza tokens repetidos entre servicios |
| Servicio → rutas `/internal/*` de VOD, Streaming y Chat | Token del llamador, uno por par de servicios (en Streaming, `Authorization: Bearer` comparado por hash) | Cada ruta compara con la credencial de su llamador; Chat solo monta `/internal/*` en su listener interno |
| Servicio → su PostgreSQL | Usuario y contraseña propios, TLS `verify-full` | Una base por servicio; ninguno tiene la credencial de otra |
| Servicio → RabbitMQ | Usuario propio por servicio, AMQPS | Sin permiso de configurar; lectura y escritura limitadas a sus colas |
| VOD y Media → MinIO | Usuarios `vod` y `media-reader` | Políticas distintas de escritura y de lectura |
| **Chat → Redis (cambio del P3)** | **Usuario `chat` con contraseña propia** | **Solo claves `chat:*`, sin Pub/Sub ni comandos administrativos** |
| Usuario → cualquier dominio | Sesión de Core, validada por el dominio que ejecuta la operación | El Gateway compone pantallas, pero no autoriza en nombre de otro dominio |

### Cambio en el Prototipo 3

El Redis de Chat deja de aceptar clientes anónimos ([ADR-028](../adr/ADR-028-zero-trust-almacen-vivo-chat.md)):

- `infra/local/chat_config.py` genera durante `init` una contraseña aleatoria, `CHAT_REDIS_PASSWORD`,
  y el archivo `chat-redis.acl`, sin rotar los demás secretos.
- El archivo ACL desactiva el usuario anónimo (`user default off`) y crea el usuario `chat`, limitado a
  sus claves (`~chat:*`), sin canales (`resetchannels`), sin la categoría `@dangerous` y sin
  `SCRIPT FLUSH` ni `FUNCTION`. Guarda solo el SHA-256 de la contraseña.
- `infra/local/backup.py` copia el ACL, y al restaurar un backup anterior genera una credencial propia
  para la copia restaurada.
- `compose.yaml` carga el ACL en `chat-db` (`--aclfile`) y Chat se conecta con
  `rediss://chat:<contraseña>@chat-db:6379/0`. El código de Chat no cambia.

## Evidencia

Ejecutada el 2026-11-21 sobre la instalación completa (`python3 infra/local/dev.py init` y `up`,
19 contenedores). El atacante se simula con un contenedor desechable conectado a la red interna,
que solo tiene el certificado público de la CA (necesario para el TLS, no para entrar):

```bash
docker run --rm --network streaming-local_default -v <estado>/ca.crt:/ca.crt:ro redis:8-alpine \
  redis-cli --tls --cacert /ca.crt -h chat-db <comando>
```

### 1. Redis de Chat antes y después

| Intento desde el contenedor atacante | Antes del cambio | Después del cambio |
| --- | --- | --- |
| `PING` sin credencial | `PONG` | `NOAUTH Authentication required.` |
| `DBSIZE` sin credencial | `63` claves visibles | `NOAUTH` |
| `SCAN 0 MATCH chat:*` sin credencial | Lista las salas (`chat:p2:inbox:…`) | `NOAUTH` |
| `SET zt:probe intruso` sin credencial | `OK`: el atacante puede escribir | `NOAUTH` |
| `--user chat --pass incorrecta PING` | `WRONGPASS`, y luego `PONG`: cae al usuario anónimo y entra igual | `WRONGPASS`, y luego `NOAUTH` |

### 2. Mínimo privilegio de la identidad de Chat

Con el usuario `chat` y su contraseña:

| Comando | Resultado |
| --- | --- |
| `ACL WHOAMI` | `chat` |
| `GET chat:…` (sus claves) | Permitido |
| `SET zt:probe x` (fuera de `chat:*`) | `NOPERM No permissions to access a key` |
| `FLUSHALL` | `NOPERM User chat has no permissions to run the 'flushall' command` |
| `CONFIG GET requirepass` | `NOPERM … 'config\|get' command` |
| `KEYS *` | `NOPERM … 'keys' command` |
| `PUBLISH canal x` | `NOPERM No permissions to access a channel` |
| `SCRIPT FLUSH` | `NOPERM … 'script\|flush' command` |
| `FUNCTION FLUSH` / `FUNCTION DELETE` | `NOPERM … 'function\|flush'` / `'function\|delete' command` |
| `CLIENT PAUSE 1` | `NOPERM … 'client\|pause' command` |
| `INFO` | `NOPERM … 'info' command` |

### 3. Las APIs internas ya verificaban la identidad

Llamada al listener privado de Core (`https://core:8082/internal/core/chat/sessions/…`) desde el
mismo contenedor atacante:

| Credencial presentada | Respuesta |
| --- | --- |
| Ninguna | `401` |
| Nombre `chat` con el token de Streaming | `401` |
| Nombre `streaming` con el token de Chat | `401` |
| Nombre `chat` con el token de Chat | `404` (autorizado; la sesión de prueba no existe) |

### 4. El sistema sigue funcionando

- `init` sobre la instalación existente agregó solo `CHAT_REDIS_PASSWORD` (51 secretos antes, 52
  después, ninguno cambiado) y Redis conservó sus datos (77 claves antes y después).
- Los 19 contenedores quedaron arriba y `chat-db` pasa su comprobación de salud autenticada.
- La prueba integral [p2-chat-replay](../../tests/integration/p2-chat-replay/run.py) pasa igual antes
  y después del cambio: transmisión RTMPS, mensajes por WebSocket, reconexión, retiro por el
  propietario, archivo en PostgreSQL, replay sobre el VOD, paginación y borrado. Chat no registró
  ningún `NOPERM` ni error.
- Las pruebas de Chat (`go test ./...`) y de la infraestructura local (`test_backup`,
  `test_network_config`, `test_profiles`) pasan en Linux.
- La contraseña no aparece en el repositorio, en el archivo ACL ni en los logs de Chat o de Redis.
- Si se omite `init`, Compose no arranca un Redis abierto: se detiene con
  `required variable CHAT_REDIS_PASSWORD is missing a value: Run python3 infra/local/dev.py init`.

## Límites

- No hay TLS mutuo: un atacante que robe el token o la contraseña de un servicio puede hacerse pasar
  por él. Los tokens son estáticos durante la vida de la instalación.
- Todos los contenedores comparten una red; Zero Trust no sustituye a la segmentación de red
  (escenario 3), la complementa.
- El perfil P1 (`infra/p1`) y el Compose de desarrollo `infra/local/compose.chat.yaml` conservan su Redis
  sin credenciales; el cambio aplica a la instalación completa (`infra/local/dev.py`).
- Mailpit, el capturador de correos de desarrollo, acepta conexiones sin credencial. No está en el
  artefacto del escenario porque reemplaza al servidor SMTP real solo en local.
- Las contraseñas, como en el resto del proyecto, llegan a los contenedores como variables de entorno:
  quien controle Docker en el equipo anfitrión puede leerlas.
