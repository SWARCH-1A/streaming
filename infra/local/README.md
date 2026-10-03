# Entorno local

`compose.core.yaml` inicia Core y PostgreSQL 18, la parte ejecutable actual. Chat, Media, Web y proxy
se incorporan al implementar sus SPEC/ADR. Definición y variables: [Core](../../services/core/README.md).

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
contenedor Core en el mismo puerto.

Volúmenes separados para SQL y avatares; `down` conserva datos:

```sh
docker compose --env-file infra/local/.env -p streaming-core -f infra/local/compose.core.yaml down
```

No añadir `-v` para detener una instalación con datos que deban conservarse. Respaldar SQL y objetos
juntos. Core arranca con base nueva; el procedimiento para trasladar datos anteriores está en su
runbook y no se ejecuta automáticamente.
