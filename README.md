# STREAMING

STREAMING es nuestro proyecto académico de una plataforma de transmisiones en vivo.
Permite crear una cuenta y un canal, emitir video, conversar por chat y encontrar emisiones.
La interfaz se presenta como Vault tv.

Los requisitos, contratos y decisiones están en [docs](docs/README.md). Esta guía explica
cómo levantar el código disponible en local. El [alcance P2](docs/alcance_p2.md) y sus
[decisiones pendientes](docs/decisiones_pendientes_p2.md) describen la próxima entrega. El arranque
local incluye las capacidades existentes y la integración inicial P2 de [RabbitMQ](infra/broker/README.md).

## Ejecutar todo en local

Necesitas Docker con Compose ≥2.24.4, Python ≥3.12 y OpenSSL. Puedes usar macOS, Linux o Windows
mediante WSL. Docker compila Java, Rust, Go y Web, así que este arranque no requiere instalar
los lenguajes ni Node/pnpm en el host.

El primer build puede tardar varios minutos. Reserva al menos 15 GB libres en el disco de Docker.

Desde la raíz de este repositorio:

```sh
python3 infra/local/dev.py init
python3 infra/local/dev.py build
python3 infra/local/dev.py up
python3 infra/local/dev.py status
```

`init` genera los secretos y certificados locales en `infra/p1/.state-local/`, que Git ignora.
Puedes repetirlo: conserva la configuración y los datos existentes. También prepara certificados,
credenciales y topología del broker, y las claves de recuperación de Cuentas. `build` compila
los servicios y la herramienta de operación.
`up` espera los servicios con healthcheck, incluido RabbitMQ, y respuestas HTTPS de Web y la API.
El gestor combina el [Compose raíz](compose.yaml) y [RabbitMQ](infra/broker/compose.yaml)
para levantar **diez contenedores**:

| Servicio | Función |
| --- | --- |
| `core` | Java/Spring: cuentas, perfiles, canales, catálogo y Discovery |
| `core-db` | PostgreSQL de Core |
| `chat` | Go: mensajes, historial y WebSocket |
| `chat-db` | Redis con AOF: mensajes, secuencia y cuota |
| `live` | Rust: control de emisiones y adaptador Media |
| `live-db` | PostgreSQL: bases separadas de Live y del adaptador |
| `media-server` | MediaMTX: ingesta RTMPS y entrega HLS |
| `web` | Build estático de la SPA React, servido por HTTPS |
| `proxy` | Entrada pública HTTPS y rutas Web/API/WebSocket/HLS |
| `broker` | RabbitMQ: ciclo de sesión, Discovery y trabajos de recuperación |

Abrir **[https://localhost:3445](https://localhost:3445)**. Antes, confiar en
`infra/p1/.state-local/ca.crt` en el sistema donde corren OBS y el navegador, siguiendo la
[guía de certificados para Linux, macOS y Windows/WSL](infra/local/certificados.md).
Importar solo ese certificado público como CA y reiniciar ambas aplicaciones. Firefox permite importarlo en Ajustes → Privacidad y
seguridad → Certificados → Autoridades; Chromium/Brave utiliza el gestor de certificados del
sistema o del navegador. Los certificados de servicio duran 90 días y la CA un año.
Una advertencia TLS indica que falta confianza o que el certificado expiró; no desactivar su verificación.
Si usas otro stack en localhost, abrir este en un perfil de navegador separado: las cookies comparten
el host aunque los puertos sean distintos.

Comprobar la API sin modificar el almacén de confianza del sistema:

```sh
curl --fail --cacert infra/p1/.state-local/ca.crt https://localhost:3445/api/taxonomy
```

Registrarse en `/register` e iniciar sesión en `/login`. «¿Olvidaste tu contraseña?», debajo del
botón de inicio de sesión, abre `/forgot-password`. Cuentas registra la recuperación y publica
la solicitud de envío en RabbitMQ. **La entrega del correo está pendiente del responsable de
Notificaciones**; este checkout no incluye consumidor SMTP, buzón ni base de datos de ese módulo.
Cuentas ya permite confirmar un token válido, cambiar la contraseña y revocar sesiones anteriores.
La integración está definida en los [contratos de recuperación](docs/contratos_p2_http.md#sesión-nativa-y-recuperación)
y [eventos](docs/contratos_p2_eventos.md#integración-de-recuperación-entre-cuentas-y-notificaciones).

En `/profile` se edita el perfil y avatar;
`/studio/channel` permite editar el canal y su portada. `/studio` crea una emisión, muestra la URL y
clave de ingest y permite detenerla. Para **OBS**, seleccionar Servicio: Personalizado, copiar
«Servidor» en «Servidor» y «Clave de retransmisión» completa en «Clave de retransmisión»;
desactivar «Usar autenticación». El servidor no contiene credenciales; copia únicamente la clave `sk_...`,
sin usuario, contraseña ni identificador adicional. Streaming identifica la emisión por su hash. El encoder debe ser compatible con
**RTMPS** y confiar en la CA local. Ingest usa `localhost:11938`.
El perfil soporta H.264/AAC; con señal activa, abrir el canal o `/watch/{streamId}` desde otra pestaña
para reproducir HLS y probar el chat. Discovery se actualiza desde las emisiones reales.

Solo HTTPS y RTMPS están publicados, en loopback. Las bases y APIs privadas permanecen en la red
Compose. Avatares y portadas usan volúmenes locales; no se necesitan credenciales S3. `live` es el
nombre del servicio desplegado; sus fuentes siguen en `services/streaming` y los contratos conservan
sus rutas y variables `STREAMING_*`.

## Parar, actualizar y diagnosticar

```sh
python3 infra/local/dev.py init       # conserva datos y añade configuración nueva
python3 infra/local/dev.py down       # conserva datos y secretos
python3 infra/local/dev.py build      # recompila el checkout después de actualizarlo
python3 infra/local/dev.py up
```

Al actualizar una instalación que tenía worker SMTP, `up` retira los contenedores anteriores
de recuperación y buzón sin borrar volúmenes ni los datos de cuentas. No se requiere configurar
un proveedor de correo para levantar la parte de Cuentas y el broker.

También se puede usar Compose directamente, incluyendo los dos manifests:

```sh
docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml up --build -d --remove-orphans --wait
docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml ps
docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml logs --tail=100 broker core live chat proxy
docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml down --remove-orphans
```

Los errores del gestor quedan en `infra/p1/.state-local/diagnostics.log`. Para empezar con bases
vacías, `python3 infra/local/dev.py reset --confirm-disposable` **elimina los datos de este proyecto**.
No borrar `.state-local` sobre bases existentes: cambiaría las contraseñas y rompería el acceso.
No compartir su configuración, claves, logs con credenciales ni URLs de ingest completas.

El proyecto se llama `streaming-local`, separado de los fixtures de integración. Antes de `init`
se pueden elegir otros puertos con `P1_HTTPS_PORT` y `P1_RTMPS_PORT`; después se conservan los
inicializados. El inicializador selecciona una subred Docker libre. Para fijarla, elegir una pareja `LOCAL_SUBNET` y
`LOCAL_PROXY_IP` antes de `init` (por ejemplo `192.168.239.0/24` y `192.168.239.2`). El proxy tiene una
IP fija porque Core solo acepta los headers de forwarding de esa IP.

## Desarrollo y comprobaciones

La recuperación de contraseña incorpora APIs Core, tokens de un uso, revocación de sesiones y
las vistas `/forgot-password` y `/reset-password`. Su [configuración](services/core/README.md#recuperación-de-contraseña)
se habilita automáticamente en el arranque local con RabbitMQ. La publicación del trabajo y los
contratos privados de entrega pertenecen a Cuentas; el consumidor, almacenamiento de intentos y
proveedor SMTP corresponden a Notificaciones y están pendientes. Core aislado conserva default
deshabilitado hasta configurar su publicador.
La política y los contratos se documentan en [SPEC-01 P2](docs/spec-p2/spec_01_auth.md) y
[ADR-017](docs/adr/ADR-017-recuperacion-de-credenciales.md).

- [Core](services/core/README.md), [Live](services/streaming/README.md), [Chat](services/chat/README.md)
  y [Web](apps/web/README.md) documentan sus comandos y configuración.
- CI automático: calidad de Web cuando cambia `apps/web` o sus contratos; generación y validación
  de contratos cuando cambian sus fuentes/consumidores de prueba o el SDL de Core. Los pushes a
  `main`/`develop` y las PR ejecutan esos checks; los cambios solo documentales no levantan stacks.
- El workflow **P1 integration (manual)** permite elegir TLS/recuperación/réplicas, dominios, Web,
  Core, Chat o el consumidor Core–Live. Las suites también se ejecutan desde [tests](tests/README.md).
- El [perfil TLS de aceptación](infra/p1/README.md) conserva los fixtures aislados para réplicas,
  recuperación y carga. Arrancar local no acredita los criterios de carga ni accesibilidad manual.

[Arquitectura](docs/arquitectura_c4_cnc_despliegue.md), [SPEC P1](docs/spec-p1/README.md),
[contratos](docs/contratos_modelo_datos.md) y [ADR](docs/adr/README.md) describen las fronteras técnicas.

## Integración inicial P2 por broker

RabbitMQ transporta ciclo de sesión a Core/Chat y snapshots a Discovery y forma parte del arranque
local anterior. Sus métricas, DLQ y recuperación están en el [runbook](infra/broker/README.md).
La selección está en [ADR-016](docs/adr/ADR-016-rabbitmq-eventos-p2.md); quedan pendientes las
funciones VOD, bandeja de notificaciones, push y las demás ampliaciones P2. La recuperación
RF-004 usa el mismo broker y el worker de [ADR-017](docs/adr/ADR-017-recuperacion-de-credenciales.md).

Al actualizar una instalación que usaba entrega HTTP P1, detener las emisiones antes del cambio
y repetir `init` para añadir la configuración privada del broker conservando los secretos y datos.
Los perfiles de pruebas bajo `infra/p1` mantienen su transporte HTTP y su estado independiente.
