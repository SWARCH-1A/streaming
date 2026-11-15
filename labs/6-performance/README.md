# Lab 6 · Rendimiento de la consulta de un canal público

La funcionalidad de Vault tv es abrir un canal público como visitante (RF-010).
El perfil Docker Compose `streaming-lab6` reutiliza PostgreSQL de Core y los Dockerfiles reales
de [Core](../../services/core), [Gateway](../../services/gateway) y [Web SSR](../../apps/web).
Caddy es la entrada HTTPS del Gateway, según el [ADR-023](../../docs/adr/ADR-023-gateway-go-caddy.md).

| Solicitud | Recorrido |
| --- | --- |
| `GET /channels/lab6_canal` | Caddy → Web SSR → Gateway → Core → PostgreSQL |
| `GET /api/screens/channels/lab6_canal` | Caddy → Gateway → Core → PostgreSQL |

El único puerto publicado es el de Caddy. El tráfico entre servicios se queda en la red privada de
Compose. El perfil tiene su propia base y su propia CA. Core aplica sus migraciones; la preparación
crea una cuenta ficticia y su canal con el registro real de Core.

## Nodo de despliegue

Ejecuta estos comandos desde la raíz del repo con Docker Compose, OpenSSL y Python 3.

```sh
cd labs/6-performance
if [ ! -f .env ]; then
  umask 077
  (
    sed '/^CORE_/d' .env.example
    printf 'CORE_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'CORE_RATE_LIMIT_HMAC_SECRET=%s\n' "$(openssl rand -hex 32)"
  ) > .env
fi
```

Para publicar el entorno en la red privada, configura **`LAB6_HOST` y `LAB6_BIND`** en `.env`
con la IPv4 privada del nodo de despliegue y permite conexiones TCP al puerto `LAB6_PORT`
desde el nodo de pruebas. Los valores de ejemplo (`localhost` y `127.0.0.1`) sirven para la
comprobación local.

```sh
. ./.env
ORIGIN="https://${LAB6_HOST}:${LAB6_PORT}"
docker compose up --build -d --wait
docker compose cp proxy:/data/caddy/pki/authorities/local/root.crt ./lab6-ca.crt
python3 check.py "$ORIGIN" --seed
docker compose ps
```

Si el certificado aún no existe al copiarlo, espera unos segundos y repite la copia. Si el chequeo
falla, consulta `docker compose logs --tail=100 core-db core gateway web proxy`.
El chequeo conserva el canal si ya existe. `docker compose down` detiene el entorno y conserva
la base y su CA. Usa el mismo `.env` al volver a levantarlo.
