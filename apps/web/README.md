# Web · STREAMING

Base frontend de los diseños **Dark Cinema Broadcast** de Stitch. Una SPA modular con React,
TypeScript estricto, Vite + SWC y pnpm. La decisión está en
[ADR-007](../../docs/adr/ADR-007-web-react-typescript.md).

## Ejecutar

Node >=22.12 y pnpm 11.17.0. Desde esta carpeta:

```sh
pnpm install --frozen-lockfile
pnpm dev
```

Abrir http://127.0.0.1:3000. No requiere Docker, Core, Chat ni Media.

```sh
pnpm check              # formato, lint, tipos, tests y build
pnpm test:watch         # pruebas de componentes/lógica durante desarrollo
pnpm test:coverage      # cobertura local
pnpm exec playwright install chromium
pnpm test:e2e          # escritorio y móvil, rutas y axe
pnpm preview           # sirve dist tras pnpm build
```

`dist/` es la salida estática. El workflow `web.yml` ejecuta la misma puerta de calidad y E2E.
Las comprobaciones automáticas de accesibilidad no sustituyen la revisión manual con lector de pantalla.

## Rutas

| Ruta                           | Vista                                                   |
| ------------------------------ | ------------------------------------------------------- |
| `/`                            | Destacado, categorías y transmisiones por popularidad   |
| `/search?q=…&category=…&tag=…` | Búsqueda de canales/streams y filtros                   |
| `/channels/:handle`            | Perfil público de canal, LIVE u OFFLINE                 |
| `/watch/:streamId`             | Player estático, metadatos, chat y simulador de estados |
| `/login`, `/register`          | Formularios de identidad de demostración                |
| `/profile`                     | Editor de perfil en memoria                             |
| `/studio`                      | Señal, monitor, metadatos y RTMP ficticio               |
| `/design-system`               | Índice, tokens, tipografía y fundamentos                |
| `/design-system/components`    | Biblioteca interactiva de las primitivas reales         |
| `/prototype`                   | Recorridos enlazados del prototipo                      |

Para escribir en el chat o guardar el perfil, inicia una sesión de demostración con datos ficticios.
Registro solo valida el formulario y lleva a login: no crea cuenta real ni sesión automáticamente.
La sesión y los cambios se eliminan al recargar. No se almacena contraseña, token ni stream key real.

## Organización y reglas de código

- `src/components/atoms`: Button, ActionLink, Input, Select, Textarea, Badge, Avatar, Icon, Spinner.
- `src/components/molecules`: campos, avisos, paneles, métricas y estados vacíos.
- `src/components/organisms`: tarjeta de stream compuesta.
- `src/modules`: accounts, channels, streaming, chat, taxonomy y discovery; cada uno publica `entry.tsx`.
- `src/shell`: layout, navegación, composición de rutas, sesión demo y boundaries por vista/Chat.
- `src/accessibility`: foco y título al navegar.
- `src/styles`: tokens y estilos base; CSS Modules encapsula estilos de componentes/vistas.
- `public/images`: assets locales de Stitch importados mediante alias; Vite emite nombres con hash.

Un único `Button` define variantes primary/secondary/ghost/danger y tamaños sm/md/lg/icon.
`ActionLink` comparte sus estilos y conserva semántica de enlace. No duplicar botones por vista.
Preferir composición a componentes con lógica y presentación mezcladas; cada funcionalidad vive en
su módulo y el shell solo compone las entradas públicas.

```ts
import { useState } from 'react';

import poster from '@/public/images/explore-0.jpg';

import { Button } from '@/src/components/atoms/Button';

import styles from './Example.module.css';
```

Imports de proyecto: `./Archivo` únicamente dentro de la misma carpeta. Para otros directorios usa
`@/src/...` o `@/public/...`; no usar `../` ni `./subcarpeta/...`. Paquetes externos y módulos `node:`
conservan sus nombres oficiales. ESLint ordena grupos de Node, dependencias, assets, proyecto,
relativos y efectos laterales. Los consumidores no importan internals de módulos ajenos.
`publicDir=false` es deliberado: esta carpeta contiene assets de fuente, no copias sin transformación.

TypeScript activa strict, exactOptionalPropertyTypes y noUncheckedIndexedAccess. ESLint valida tipos,
hooks, accesibilidad, import paths/orden y límites de módulo; Prettier define el formato.
Los tests locales están junto a sus componentes y la suite del navegador en `e2e/`.

## Alcance de la demostración e integración futura

Los fixtures pertenecen a `mock/` de Discovery, Taxonomy y Chat. Sus IDs y modelos son de presentación,
no una segunda definición de contratos HTTP. No hay fetch, WebSocket, HLS, encoder ni métricas reales.
La UI local no autoriza operaciones: el backend deberá validar principal y propiedad.

Se aplican los límites existentes: título 1–100, una categoría, hasta cinco tags; perfil 1–50/300;
chat NFC recortado y hasta 500 puntos Unicode. El límite local de un mensaje por segundo demuestra
feedback en una sala; la cuota global entre salas/réplicas pertenece al futuro servicio Chat.
Búsqueda NFKC/case-insensitive conserva acentos; categoría y una etiqueta combinan AND, ranking por
viewers/inicio/ID. Canales offline siguen siendo buscables.

Al conectar contenedores, cada módulo incorpora su cliente/adaptador conforme a
[contratos](../../docs/contratos_modelo_datos.md),
[SPEC-12](../../docs/spec-p1/spec_12_int.md) y
[proxy](../../docs/integracion_frontend_reverse_proxy.md). Identidad usará cookie HttpOnly/CSRF;
canal consumirá bootstrap Core; Discovery, GraphQL; player, availability/HLS; Chat, WS + historial.
Uploads de avatar/banner, edición real de canal y autorización permanecen pendientes.
El reverse proxy debe separar las rutas SPA de `/api`, `/realtime`, `/hls` y `/internal`.
Suscripciones, drops, moderación y VOD mostrados en Stitch no se convierten en funciones P1.

## Referencias visuales

Los dos ZIP originales se descomprimieron en `stitch_streaming_responsive_web_ui_system/` en la raíz
del workspace, fuera del repositorio Git `streaming/`. Contienen cinco referencias: fundamentos,
componentes, explorar/buscar, canal/player y estudio/prototipo. Las imágenes de esta base provienen
de sus enlaces; consulta [assets](public/images/README.md). No se carga Tailwind ni fuentes desde CDN.
