# Web · Vault tv

Vault tv es la interfaz Web de STREAMING: React y TypeScript con **React Router en modo framework y
SSR** ([SPEC-18](../../docs/spec-p2/spec_18_ssr.md)), gestionada con pnpm. Usa el diseño Dark Cinema
Broadcast y organiza sus funciones en módulos internos.

## SSR (prototipo 2)

- `src/app/root.tsx` arma el documento HTML y resuelve la sesión **de esa petición** en el servidor
  (`loader`); el navegador hidrata con ese dato y no repite la consulta.
- `src/app/routes.ts` declara las mismas URL del P1; cada archivo de `src/app/routes/` envuelve una
  página existente. `/channels/:handle` tiene `loader`: el HTML ya trae el canal y, con sesión, el
  estado de seguimiento (CA-01). La revalidación cada 4 s sigue en el navegador.
- `/watch/:streamId` carga emisión, sesión y canal en SSR. Sesión y canal se leen en paralelo,
  con un plazo total de 3 s. Canal no disponible conserva el player; sesión incoherente o
  Streaming no disponible da 503 y una emisión inexistente da 404. El navegador reutiliza
  estos datos iniciales y revalida emisión/conteo cada 4 s.
- `src/server/api.server.ts` (solo servidor) reenvía únicamente las cookies `stream_session` y
  `XSRF-TOKEN` a `WEB_API_UPSTREAM` (Core hoy; el Gateway cuando exista). Nunca a una URL de usuario.
- Las lecturas públicas de Streaming usan `WEB_STREAMING_UPSTREAM` sin reenviar cookies;
  si no se configura, usan `WEB_API_UPSTREAM` para una entrada común de API. Ambas URL son
  configuración del servidor y no se serializan al navegador.
- El reproductor HLS y el WebSocket del chat se montan en efectos, después de hidratar.
- HTML dependiente de identidad: `Cache-Control: no-store, private`; rutas inexistentes: 404 real.
- `server.mjs` es el runtime de producción (HTTPS, assets con hash cacheados un año, resto por SSR).
- Nuevas vistas: botón Seguir y aviso por correo en `/channels/:handle`, y `/notifications`
  (bandeja, leído y medios de aviso).

Para levantar el sistema completo, sigue el [Compose raíz y la guía local](../../README.md).
[ADR-007](../../docs/adr/ADR-007-web-react-typescript.md) define la base;
[ADR-013](../../docs/adr/ADR-013-web-integrada-caddy-hls.md) conecta los proveedores reales.

## Ejecutar

Node >=22.12 y pnpm 11.17.0. Desde esta carpeta:

```sh
pnpm install --frozen-lockfile
pnpm check
pnpm dev
```

`pnpm check` incluye una prueba HTTP del build de producción (`pnpm test:ssr`) con API ficticia:
HTML inicial de canal/live/offline, fallos parciales, coherencia de sesión, rutas de
administración/notificaciones, cookies permitidas, aislamiento y códigos 404/503.

Las vistas funcionales necesitan los servicios. Si no están disponibles, muestran el error.
Los datos de muestra se usan en pruebas y en la biblioteca de desarrollo.

Para desarrollar por HTTP con Core:8081, Streaming:8080, Chat:8085 y HLS:8888 ya iniciados:

```sh
VITE_P1_PROXY=1 WEB_API_UPSTREAM=http://127.0.0.1:8081 WEB_STREAMING_UPSTREAM=http://127.0.0.1:8080 pnpm dev
```

Abrir http://localhost:3000, el mismo WEB_ORIGIN de los proveedores. En PowerShell, primero
`$env:VITE_P1_PROXY='1'`. Opcionalmente configurar CORE_DEV_UPSTREAM, STREAMING_DEV_UPSTREAM,
CHAT_DEV_UPSTREAM, NOTIFICATIONS_DEV_UPSTREAM y HLS_DEV_UPSTREAM; son variables del servidor Vite, no credenciales del browser.
`pnpm dev` ejecuta `react-router dev` (SSR con recarga). `pnpm build` genera `build/client` y
`build/server`; `pnpm start` los sirve con `server.mjs`. Caddy es la entrada integrada documentada
[con sus rutas](../../infra/reverse-proxy/README.md).

El navegador integrado requiere los proveedores reales y no los inicia por sí mismo:

```sh
pnpm exec playwright install chromium
# Desde la raíz, con las dependencias Python del fixture:
python tests/integration/p1-domains/run.py --web
```

El runner construye servicios actuales, crea secretos ficticios efímeros, inicia Caddy y ejecuta
`pnpm test:e2e` para escritorio/móvil; elimina únicamente su proyecto al finalizar.
Fuera del runner, la suite E2E omite explícitamente los recorridos reales: un skip no es aceptación.
`pnpm test:watch` y `pnpm test:coverage` sirven para pruebas locales; `pnpm start` sirve el build
SSR y no sustituye el proxy. Axe/teclado complementan la revisión con lector de pantalla SPEC-08.

## Rutas y comportamiento

| Ruta                                                  | Fuente                                                                                        |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| / y /search?q=…&category=…&tag=…                      | Consultas GraphQL generadas de Discovery y Taxonomy dinámico; ranking/cursor/AND en servidor. |
| /channels/:handle                                     | Bootstrap Core, casing canónico y estado UNKNOWN si Streaming falla.                          |
| /watch/:streamId                                      | Streaming autoritativo, canal Core por ID, HLS y Chat real por sessionId.                     |
| /login y /register                                    | Cookie HttpOnly/CSRF; registro crea cuenta/perfil/canal y login es separado.                  |
| /profile                                              | Sesión requerida, perfil y avatar con upload multipart Core.                                  |
| /studio/channel                                       | Sesión requerida, descripción y portada con upload multipart Core.                            |
| /studio                                               | Configuración/metadata, clave de creación/rotación solo en memoria, stop y monitor real.      |
| /design-system, /design-system/components, /prototype | Biblioteca visual disponible solo con `pnpm dev`; producción muestra página no encontrada.    |

La identidad visible es Vault tv. El logo local se importa desde `public/brand/vault-tv.png` y
conserva sus proporciones; los tokens Dark Cinema Broadcast definen contraste, foco y estados.
Las páginas de diseño se cargan de forma diferida bajo `import.meta.env.DEV`: sus rutas y módulos
no se incluyen en producción. `pnpm build` comprueba también los paquetes emitidos. Los componentes
compartidos permanecen disponibles para las vistas funcionales.

El [protocolo de accesibilidad](src/accessibility/README.md) cubre SPEC-08 y diferencia comprobación
automática de observación humana. Opcionalmente, `P1_A11Y_ARTIFACTS` indica una carpeta externa para
capturas del runner Web; se oculta el campo de clave. Revisar las capturas antes de compartirlas.

No se guardan credenciales, claves, tokens de lease ni sesión en local/session storage. CSRF se
solicita por operación Core; Streaming valida Origin según su contrato. El servidor decide permisos.
Uploads admiten JPEG/PNG/GIF hasta 10 MB; los errores conservan el formulario. Cambios de recurso
cancelan lecturas y descartan resultados tardíos. Una caída Chat no desmonta el player.

El reproductor usa controles nativos. Carga hls.js de forma diferida cuando hay MediaSource
y usa HLS nativo si MediaSource/hls.js no está disponible. Solo carga el medio en PLAYABLE.
Un frame decodificado inicia el lease, que se renueva cada 10 s y se cierra al pausar, salir,
fallar o cambiar de sesión. La expiración de 30 s del servidor limita las creaciones o cierres
cuyo resultado se desconoce.

Como inicio de RF-028…030 (P2), el selector Calidad ofrece únicamente las variantes del maestro
HLS y comienza en Automática. La selección manual cambia nuevas cargas sin recrear el motor,
alterar la posición ni abrir otro lease. La calidad reproducida se informa aparte de la elegida;
retirar una variante seleccionada vuelve a automático. HLS nativo conserva la adaptación del
navegador y lo indica sin selector manual. El MediaMTX del perfil actual entrega una sola pista
de vídeo; probar cuatro calidades reales depende de P2-08 en [SPEC-04](../../docs/spec-p2/spec_04_stream.md).
El HTML SSR no carga vídeo ni ofrece variantes antes de leer el maestro en el navegador.
Las pruebas de hidratación comprueban que no haya discrepancias, motores duplicados ni leases
antes de recibir un frame.

Chat abre WS antes del historial, fusiona secuencias, avisa de huecos no recuperables y permite
reintentar el mismo clientMessageId. No se anuncia éxito sin ACK. Pausar autoscroll conserva foco.

## Organización

Componentes/tokens en src/components y src/styles; módulos publican entry.tsx y no comparten internals.
Shell posee sesión, router, layout y boundaries; accessibility mantiene foco/título al navegar.
Aliases @/src y @/public conservan imports internos, y @contracts usa ../../contracts/generated.
Los schemas, tipos p1.d.ts y queries se derivan de contracts/p1.json y del SDL en docs/contratos_modelo_datos.md; ejecutar su generador
antes de pnpm check cuando cambie el contrato. Los modelos demo solo son presentación para pruebas.

TypeScript es estricto; ESLint comprueba tipos/hooks/accesibilidad/límites; Prettier define formato.
Tests junto al módulo; integración compartida en tests/ y e2e/. Dist, logs y resultados no se versionan.
VOD, pagos, suscripciones, drops, moderación y captions permanecen fuera de P1 según catálogo.
