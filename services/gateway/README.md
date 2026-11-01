# Gateway de agregación

Go1.26, sin BD, según [ADR-023](../../docs/adr/ADR-023-gateway-go-caddy.md) y
[SPEC-16](../../docs/spec-p2/spec_16_gateway.md). Forma parte del arranque local
`infra/local/dev.py init/build/up`. Caddy conserva la entrada TLS, el proxy WS/HLS, límites y
supresión de headers privados; este proceso solo recibe lo que Caddy le enruta:

| Ruta | Contrato | Esencial | Secciones |
| --- | --- | --- | --- |
| `GET /api/screens/channels/{handle}` | `P2ChannelScreen` | canal (Core) | live, vods, follow |
| `GET /api/screens/watch/{streamId}` | `P2WatchScreen` | emisión y sesión (Streaming) | canal, rendiciones |
| `GET /api/screens/vods/{vodId}` | `P2VodScreen` | VOD y mapping | canal |
| `GET /api/screens/explore` | `P2ExploreScreen` | — | vods, discovery |
| `POST /api/admin/content-removals` | `p2.admin.content-removal` | — | reenvío único a VOD |

Las consultas independientes son concurrentes, con presupuesto total de 3s (504 al agotarse) y
cancelación de las lecturas internas. Cuerpos internos acotados a 2MiB; respuestas `no-store, private`.
Hacia Core/VOD solo se copian `stream_session` y Authorization; Streaming y Discovery no reciben
identidad. En watch, una credencial presentada se valida con `GET /api/profile/me` (401 si Core la
rechaza), porque sus demás lecturas son públicas. Ningún header `X-Service-*`, `X-Session-Credential` ni `Forwarded` del cliente se reenvía.

Variables: `GATEWAY_CORE_URL`, `GATEWAY_VOD_URL`, `GATEWAY_STREAMING_URL` (upstreams privados),
`GATEWAY_TRUSTED_PROXIES` (IP/CIDR separados por coma cuyo X-Forwarded-For se acepta; en local, la
IP fija de Caddy), `GATEWAY_TLS_CERT_FILE`, `GATEWAY_TLS_KEY_FILE`, `SSL_CERT_FILE`,
`GATEWAY_PUBLIC_ORIGIN`, `GATEWAY_VOD_TOKEN`, `GATEWAY_VOD_INTERNAL_URL`. La imagen copia las
consultas GraphQL generadas; `GATEWAY_QUERIES_FILE` permite otra ubicación de ese mismo artefacto.
El Gateway tiene IP fija (`LOCAL_GATEWAY_IP`) y Core la incluye en `CORE_TRUSTED_PROXIES`. Los
permisos pertenecen a los servicios. No reenviar secretos de servicio desde clientes.

```sh
cd services/gateway
go vet ./... && go test -count=1 -race ./...
# Validar las respuestas producidas por las pruebas contra contracts/p2.json:
GATEWAY_SCREEN_SAMPLES=/tmp/screens go test -count=1 ./...
for f in /tmp/screens/*.json; do (cd ../../contracts && python check_p2.py --public --payload "$(basename "$f" | cut -d. -f1)" "$f"); done
```

Borde y stack: `python tests/integration/p2-gateway-edge/run.py` (Caddyfile real, solo Docker) y
`python3 tests/integration/p2-gateway/run.py` con el stack local levantado. La adopción del agregado
watch por SSR/móvil, la carga sostenida y las retiradas no VOD siguen pendientes (SPEC-16 §10).
