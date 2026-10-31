# Web VOD con proveedores reales

Desde la raíz, con Docker Desktop/Engine, OpenSSL, Python y Node compatibles con el repositorio:

```sh
cd apps/web
corepack pnpm install --frozen-lockfile
corepack pnpm exec playwright install chromium
cd ../..
python tests/integration/vod-web/run.py --browser ESCENARIO.mjs
```

Construye las imágenes del checkout y crea contenedores/red con un prefijo aleatorio propio.
Elige el escenario disponible en la rama: `subtitles.mjs` para SPEC-22 o
`administration.mjs` para SPEC-23. Cada SPEC documenta su entrada y cobertura.
`--skip-build` solo es válido si las cinco imágenes de prueba ya corresponden a este checkout;
no se usa como sustituto de construir cambios. No requiere ni utiliza `.state-local`, cuentas
de demostración o volúmenes del usuario. Retira exclusivamente sus contenedores al terminar.

Web SSR, Core, Gateway, VOD, dos PostgreSQL, MinIO, RabbitMQ y el entregador Media son reales.
La entrada Caddy usa HTTPS con certificado temporal: Python verifica su CA; Chromium acepta
ese certificado únicamente dentro del contexto de prueba. No instala confianza en el sistema.
AMQP y Media usan TLS; los listeners internos Core/VOD y S3 usan HTTP explícito dentro de la
red de fixture. Los puertos auxiliares se publican exclusivamente en loopback. Esto no acredita
TLS en todos los saltos del despliegue normal.

FFmpeg produce cuatro segmentos reales H264 de 16 segundos. Se entregan a la API privada de
captura; un evento de cierre viaja por RabbitMQ. VOD verifica bytes/índice con ffprobe y publica
dos grabaciones de 64 segundos. No hay acceso directo a tablas VOD ni autoridad Core simulada.
No representa una emisión OBS ni ejercita el capturador live; empieza en la frontera de captura.

Cada escenario debe ejercer el navegador y los permisos mediante servicios reales. Si inyecta
un fallo de red, debe distinguirlo de las respuestas del proveedor y no usarlo como evidencia
de una caída real de autoridad o almacenamiento.

La prueba usa una calidad y no levanta el proveedor Chat. Su indisponibilidad no debe impedir
el vídeo. No demuestra replay, multicalidad de Media, móvil, observación con lector de pantalla,
zoom manual ni contraste perceptual de subtítulos sobre cualquier contenido audiovisual.
