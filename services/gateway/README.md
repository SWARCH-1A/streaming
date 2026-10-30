# Gateway de agregación

Go1.26, sin BD, según [ADR-023](../../docs/adr/ADR-023-gateway-go-caddy.md). Forma parte del arranque
local `infra/local/dev.py init/build/up`. Caddy conserva la entrada TLS, el proxy WS/HLS, límites y
supresión de headers privados; este proceso compone `/api/screens/channels/{handle}`,
`/api/screens/vods/{vodId}` y `/api/screens/explore`, y enruta retiradas administrativas VOD.

Variables: `GATEWAY_CORE_URL`, `GATEWAY_VOD_URL`, `GATEWAY_STREAMING_URL` (upstreams privados),
`GATEWAY_TLS_CERT_FILE`, `GATEWAY_TLS_KEY_FILE`, `SSL_CERT_FILE`, `GATEWAY_PUBLIC_ORIGIN`,
`GATEWAY_VOD_TOKEN`, `GATEWAY_VOD_INTERNAL_URL`. La imagen copia las consultas GraphQL generadas;
`GATEWAY_QUERIES_FILE` permite otra ubicación de ese mismo artefacto. TLS8097, timeout total3s;
los permisos pertenecen a los servicios. No reenviar secretos de servicio desde clientes.

```sh
cd services/gateway
go test -count=1 -v ./...
```

La suite comprueba caída parcial VOD, requestId, identidad inválida y solicitudes concurrentes de
dos usuarios. El agregado live, pruebas de carga, WS extremo a extremo y aceptación móvil siguen
fuera de esta integración VOD; no se declara SPEC-16 completamente aceptada.
