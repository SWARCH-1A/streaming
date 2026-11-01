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
Antes de construir, ambos runners comprueban las referencias de sus scripts con ESLint.
También puede ejecutarse solo esa comprobación: `node tests/integration/vod-web/check-scripts.mjs`.
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

Este modo reducido usa una calidad y no levanta el proveedor Chat. Su indisponibilidad no debe impedir
el vídeo. No demuestra replay, multicalidad de Media, móvil, observación con lector de pantalla,
zoom manual ni contraste perceptual de subtítulos sobre cualquier contenido audiovisual.

## Captura nativa y replay real en un proyecto desechable

En Linux/WSL, con las mismas dependencias y Node disponible (también admite `node.exe` de Windows):

```sh
python3 tests/integration/vod-web/full_stack.py --browser subtitles.mjs
# En la rama administrativa:
python3 tests/integration/vod-web/full_stack.py --browser administration.mjs
```

Este runner reutiliza la inicialización canónica y los Compose raíz/broker, con estado temporal,
proyecto, imágenes, red, puertos y volúmenes propios. No utiliza `.state-local` ni cambia la confianza
de certificados del equipo. Solo publica en loopback la entrada HTTPS y la administración TLS
del broker para comprobar ACK/reintentos. Al terminar elimina exclusivamente los contenedores y
volúmenes de su proyecto aleatorio; conserva diagnóstico/configuración en un directorio temporal
privado, que contiene secretos de prueba y no debe compartirse ni versionarse.

Ejecuta dos capturas RTMPS H264/AAC de más de 64 segundos con reconexión, mensajes WebSocket,
moderación por propietario, archivo PostgreSQL, publicación VOD y mapping del replay. Usa
`tests/integration/p2-chat-replay/run.py`, ahora parametrizable por `--state`, `--project`,
`--network`, `--origin` y `--live-image`; importar ese módulo no lee la instalación personal.
Después entrega esas grabaciones al escenario de navegador. Subtítulos comprueba sincronía del
panel, seek y continuidad HLS cuando Chat cae. Administración comprueba URLs de replay conocidas
antes/después de retirada, suspensión/reactivación, evento atrasado, reinicio y fallo de Core.

Tener el runner no acredita su aprobación: los resultados se registran por commit en el PR.
Se conserva una calidad; móvil, multicalidad y observación humana asistiva siguen siendo verificaciones
separadas. La moderación por propietario de Chat no otorga privilegios administrativos globales.
