# STREAMING

Plataforma académica de transmisión en vivo: cuentas, canales, emisiones, chat y descubrimiento.
La definición de requisitos y contratos está en [docs](docs/README.md).

## Ejecutar todo en local

Requisitos: Docker con Compose ≥2.24.4, Python ≥3.12 y OpenSSL; macOS, Linux o Windows mediante WSL.
Docker compila Java, Rust, Go y Web. No hace falta instalar esos lenguajes ni Node/pnpm en el host.
El primer build puede tardar varios minutos; reservar al menos 15 GB libres en el disco de Docker.

Desde la raíz de este repositorio:

```sh
python3 infra/local/dev.py init
python3 infra/local/dev.py build
python3 infra/local/dev.py up
python3 infra/local/dev.py status
```

`init` genera secretos y certificados privados en `infra/p1/.state-local/`, ignorado por Git.
Es idempotente: repetirlo conserva la configuración y los datos. `up` espera que Web y la API
respondan por HTTPS. El [Compose raíz](compose.yaml) levanta estos **nueve contenedores**:

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

Abrir **[https://localhost:3445](https://localhost:3445)**. Antes, confiar en
`infra/p1/.state-local/ca.crt` en el navegador/perfil de pruebas: importar solo ese certificado
público como CA y reiniciar el navegador. Firefox permite importarlo en Ajustes → Privacidad y
seguridad → Certificados → Autoridades; Chromium/Brave utiliza el gestor de certificados del
sistema o del navegador. Los certificados de servicio duran 90 días y la CA un año.
Una advertencia TLS indica que falta confianza o que el certificado expiró; no desactivar su verificación.
Si usas otro stack en localhost, abrir este en un perfil de navegador separado: las cookies comparten
el host aunque los puertos sean distintos.

Comprobar la API sin modificar el almacén de confianza del sistema:

```sh
curl --fail --cacert infra/p1/.state-local/ca.crt https://localhost:3445/api/taxonomy
```

Registrarse en `/register` e iniciar sesión en `/login`. En `/profile` se edita el perfil y avatar;
`/studio/channel` permite editar el canal y su portada. `/studio` crea una emisión, muestra la URL y
clave de ingest y permite detenerla. Publicar con OBS u otro encoder compatible con **RTMPS** usando
esos valores; el encoder también debe confiar en la CA local. Ingest usa `localhost:11938`.
El perfil soporta H.264/AAC; con señal activa, abrir el canal o `/watch/{streamId}` desde otra pestaña
para reproducir HLS y probar el chat. Discovery se actualiza desde las emisiones reales.

Solo HTTPS y RTMPS están publicados, en loopback. Las bases y APIs privadas permanecen en la red
Compose. Avatares y portadas usan volúmenes locales; no se necesitan credenciales S3. `live` es el
nombre del servicio desplegado; sus fuentes siguen en `services/streaming` y los contratos conservan
sus rutas y variables `STREAMING_*`.

## Parar, actualizar y diagnosticar

```sh
python3 infra/local/dev.py down       # conserva datos y secretos
python3 infra/local/dev.py build      # recompila el checkout después de actualizarlo
python3 infra/local/dev.py up
```

También se puede usar Compose directamente; el único manifest es el de la raíz:

```sh
docker compose --env-file infra/p1/.state-local/environment.env up --build -d
docker compose --env-file infra/p1/.state-local/environment.env ps
docker compose --env-file infra/p1/.state-local/environment.env logs --tail=100 core live chat proxy
docker compose --env-file infra/p1/.state-local/environment.env down
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
