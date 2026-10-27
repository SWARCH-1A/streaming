# Reverse proxy P1

Caddy enruta Web SSR y las APIs, WebSocket y HLS, según
[ADR-013](../../docs/adr/ADR-013-web-integrada-caddy-hls.md).
La [tabla de rutas](../../docs/integracion_frontend_reverse_proxy.md) indica el servicio
responsable de cada path.

El Caddyfile de esta carpeta enruta páginas al runtime SSR y APIs en http://localhost:3000 para desarrollo HTTP.
La instalación HTTPS está en el Compose raíz; SPEC-13 define su aceptación y el TLS entre contenedores.

| Unidad | Comando / upstream interno | Puerto / health privado |
| --- | --- | --- |
| Web/Caddy | caddy run --config /etc/caddy/Caddyfile; upstream web-ssr | 3000; páginas SSR por GET/HEAD/POST |
| Core | imagen services/core | 8081; /actuator/health (no expuesto por proxy) |
| Streaming | imagen services/streaming | 8080; /health/ready (no expuesto por proxy) |
| Chat | imagen services/chat | 8085; /readyz (no expuesto por proxy) |
| Adaptador Media | dentro de Streaming P1 | 8888; entrega HLS según availability |
| MediaMTX | imagen infra/media | 1935 TCP RTMP; no es ruta HTTP del proxy |

compose.web-dev.yaml se incluye junto con los proveedores. Construye `web-ssr` con el Dockerfile Web y monta Caddyfile; no requiere `dist`.
CORE_UPSTREAM/STREAMING_UPSTREAM/CHAT_UPSTREAM/HLS_UPSTREAM son opcionales y predeterminados a los
nombres anteriores. WEB_PORT cambia el bind local; WEB_ORIGIN de los proveedores debe coincidir.
Los puertos directos de proveedor se mantienen en loopback para diagnóstico de la prueba aislada.

La [prueba reproducible](../../tests/integration/p1-domains/README.md) arranca todo con `--web` y
establece CORE_TRUSTED_PROXIES a la IP real del proxy. Si esa IP cambia, hay que actualizar Core;
no confiar en todo el bridge ni aceptar X-Forwarded-For arbitrario. Caddy reemplaza forwarding con
el socket observado y elimina X-Service-Name/X-Service-Token/X-Session-Credential/Forwarded públicos.
Core aplica esa resolución a cuotas de Accounts y Discovery; las pruebas unitarias cubren spoofing.

Solo GET/POST exactos channels/{id}/streams van a Streaming antes del prefijo Channels Core.
WS solo acepta GET en el path exacto de Chat, que valida Origin incluso para anónimos. HLS conserva
MIME, byte ranges y cache del adaptador: playlists no-cache y segmentos inmutables según respuesta;
no hay cache intermedio nuevo ni fallback SPA para HLS. No reescribir keys, cookies ni autorización.
SSR resuelve páginas e hidratación; sus rutas inexistentes devuelven 404. Assets
con hash son inmutables; HTML por identidad usa no-store, private. /api desconocida devuelve JSON; /internal y health
se bloquean. Errores de upstream devuelven JSON 503 y el cliente conserva el error por vista.

No se habilitan access logs. El logger global elimina request y resp_headers también en errores
de upstream, para excluir cookies, credenciales y queries. Los logs de error deben revisarse antes
de compartirlos. El fixture genera credenciales ficticias y no conserva trazas de navegador con
payloads sensibles. No publicar los logs multimedia sin revisar sus posibles URLs privadas.
