# Entorno local

`compose.core.yaml` inicia Core y PostgreSQL 18. `compose.chat.yaml` añade Chat y su Redis con AOF.
Media, Web y proxy se incorporan al implementar sus SPEC/ADR. Definición y variables:
[Core](../../services/core/README.md) y [Chat](../../services/chat/README.md).

Desde la raíz:

```sh
cp infra/local/.env.example infra/local/.env
# Completar CORE_DB_PASSWORD y CORE_RATE_LIMIT_HMAC_SECRET (al menos 32 bytes).
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml up --build -d
curl http://localhost:8081/actuator/health
```

Las credenciales permanecen en `.env` ignorado. Core escucha en localhost:8081 y PostgreSQL en
localhost:5440; cambiar CORE_PORT/CORE_DB_PORT evita conflictos. Para Maven en el host, usar
`CORE_DB_URL=jdbc:postgresql://localhost:5440/core` y las mismas variables; no correr Maven y el
contenedor Core en el mismo puerto. Exportar PROFILE_AVATAR_STORAGE y CHANNELS_BANNER_STORAGE
con directorios persistentes distintos al ejecutar Maven fuera del contenedor.

Volúmenes separados para SQL, avatares y portadas; `down` conserva datos:

```sh
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml down
```

Chat se levanta junto con Core para resolver `core:8081` en la misma red. Completar antes
CHAT_CORE_SERVICE_TOKEN y CHAT_SESSION_EVENTS_TOKEN en `.env`:

```sh
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml -f infra/local/compose.chat.yaml up --build -d
curl http://localhost:8085/readyz
```

Chat publica 8085 (historial/WS) y 8086 (`/internal/chat/session-events`) solo en localhost. Redis
no se publica. El volumen `chat-redis` conserva mensajes con ACK ante reinicios. Las salas expiran
5 minutos después de terminar la sesión.

No añadir `-v` para detener una instalación con datos que deban conservarse. Respaldar SQL y objetos
juntos. Core arranca con base nueva; el procedimiento para trasladar datos anteriores está en su
runbook y no se ejecuta automáticamente.
