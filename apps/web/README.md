# Web · STREAMING

Una SPA React/TypeScript/SWC con pnpm, diseño Dark Cinema Broadcast y módulos internos.
[ADR-007](../../docs/adr/ADR-007-web-react-typescript.md) define la base;
[ADR-013](../../docs/adr/ADR-013-web-integrada-caddy-hls.md) conecta los proveedores reales.

## Ejecutar

Node >=22.12 y pnpm 11.17.0. Desde esta carpeta:

```sh
pnpm install --frozen-lockfile
pnpm check
pnpm dev
```

Sin servicios, las vistas muestran sus errores; no cargan cuentas/emisiones ficticias como fallback.
Para desarrollo HTTP explícito con Core:8081, Streaming:8080, Chat:8085 y HLS:8888 ya iniciados:

```sh
VITE_P1_PROXY=1 pnpm dev
```

Abrir http://localhost:3000, el mismo WEB_ORIGIN de los proveedores. En PowerShell, primero
`$env:VITE_P1_PROXY='1'`. Opcionalmente configurar CORE_DEV_UPSTREAM, STREAMING_DEV_UPSTREAM,
CHAT_DEV_UPSTREAM y HLS_DEV_UPSTREAM; son variables del servidor Vite, no credenciales del browser.
Vite solo sirve desarrollo. `pnpm build` genera dist; Caddy es la entrada integrada documentada
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
`pnpm test:watch` y `pnpm test:coverage` sirven para pruebas locales; `pnpm preview` solo inspecciona
el build y no sustituye el proxy. Axe/teclado complementan la revisión con lector de pantalla SPEC-08.

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
| /design-system, /design-system/components, /prototype | Biblioteca visual y enlaces; ejemplos estáticos explícitos.                                   |

No se guardan credenciales, claves, tokens de lease ni sesión en local/session storage. CSRF se
solicita por operación Core; Streaming valida Origin según su contrato. El servidor decide permisos.
Uploads admiten JPEG/PNG/GIF hasta 10 MB; los errores conservan el formulario. Cambios de recurso
cancelan lecturas y descartan resultados tardíos. Una caída Chat no desmonta el player.

HLS usa controles nativos y hls.js diferido cuando no existe HLS nativo. Solo PLAYABLE carga medio;
solo un frame decodificado inicia lease, con heartbeat 10 s y cierre en pausa/salida/error/cambio de
sesión. Cierres/creaciones con resultado desconocido quedan limitados por expiración servidor 30 s.
Chat abre WS antes del historial, fusiona secuencias, avisa de huecos no recuperables y permite
reintentar el mismo clientMessageId. No se anuncia éxito sin ACK. Pausar autoscroll conserva foco.

## Organización

Componentes/tokens en src/components y src/styles; módulos publican entry.tsx y no comparten internals.
Shell posee sesión, router, layout y boundaries; accessibility mantiene foco/título al navegar.
Aliases @/src y @/public conservan imports internos, y @contracts usa ../../contracts/generated.
Los schemas, tipos p1.d.ts y queries vienen de docs/contratos_modelo_datos.md; ejecutar su generador
antes de pnpm check cuando cambie el contrato. Los modelos demo solo son presentación para pruebas.

TypeScript es estricto; ESLint comprueba tipos/hooks/accesibilidad/límites; Prettier define formato.
Tests junto al módulo; integración compartida en tests/ y e2e/. Dist, logs y resultados no se versionan.
VOD, pagos, suscripciones, drops, moderación y captions permanecen fuera de P1 según catálogo.
