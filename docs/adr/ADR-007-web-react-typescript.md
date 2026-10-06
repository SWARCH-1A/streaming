# ADR-007: Web modular con React, TypeScript y SWC

- Estado: aceptada
- Fecha: 2026-10-02
- Responsable: Web / integración, por encargo explícito del usuario
- SDD/contratos afectados: SPEC-08, SPEC-12; sin cambios de contratos de red

## Contexto

Web era un esqueleto documental. El encargo selecciona React + TypeScript + SWC, pnpm,
componentes atómicos, lint, formato, imports agrupados y tests. Stitch aporta los tokens y vistas.
Se necesita una base ejecutable sin depender de contenedores o servicios disponibles.

## Decisión

Una SPA con Vite y plugin React SWC, TypeScript estricto y React Router declarativo. pnpm tiene
versión fijada y lockfile. Mantener src/modules por responsabilidad, src/shell para composición y
src/accessibility para foco. src/components contiene atoms, molecules y organismos compartidos.
Cada módulo publica entry.tsx; consumidores no importan sus internals. No usar barrels generales.

ESLint valida tipos, hooks, semántica accesible, límites e imports. Prettier aplica formato.
Imports de proyecto permiten ./Archivo en la misma carpeta; las otras rutas usan @/src/... o
@/public/.... Imports de paquetes y node: conservan sus especificadores oficiales. Se agrupan
Node, dependencias, assets, proyecto, relativos y efectos laterales. No se permiten ../ ni
./subcarpeta/Archivo. Alias @ apunta a apps/web tanto en TypeScript como Vite y tests.

publicDir=false permite importar los assets en public mediante @/public y generar nombres con
hash; public es una carpeta de assets de fuente, no una carpeta copiada sin transformación.
Imágenes de Stitch y fuentes se sirven localmente, sin CDN ni Tailwind en tiempo de ejecución.
CSS usa tokens y capas, con estilos encapsulados por componente mediante CSS Modules.

Mocks tipados pertenecen a cada módulo y viven únicamente en memoria. Son modelos de presentación,
no nuevos contratos de backend. La identidad local es una demostración, no autorización. No se
almacenan contraseñas, claves reales, tokens o sesiones en navegador. Player representa estados
con un póster estático; no declara ni inicia HLS. La conexión real tendrá clientes de cada módulo,
CSRF, cookies HttpOnly, contexto Chat y bootstrap según contratos existentes.

Vitest + Testing Library verifican comportamientos, límites Unicode, filtros y formularios;
Playwright cubre rutas y recorridos responsive. Un workflow ejecuta calidad, build y E2E.

## Opciones consideradas

- React con Vite/SWC: coincide con selección del usuario, build único y despliegue estático sencillo.
- Next.js: agrega runtime/SSR sin necesidad en esta base; no se selecciona.
- Mantener HTML generado/CDN: duplica estilos y controles, sin contratos tipados ni tests; descartado.
- Microfrontends: contradicen ADR-005 y multiplican builds; descartados.

## Consecuencias

Se fija un stack reproducible y límites de módulo verificables. Mocks no acreditan SPEC integradas;
seguirán pendientes HTTP/WS/HLS, autorización real, cargas de archivos y proxy. Capacidades futuras
presentes en Stitch (pagos, drops, VOD, moderación) no adquieren implementación P1.
El servidor de despliegue deberá respetar fallback solo para rutas SPA, nunca para API o /internal.

## Verificación

pnpm check y pnpm test:e2e son las puertas ejecutables de esta base. Los tests se mantienen junto
al módulo; los recorridos de esta SPA están en apps/web/e2e. Pruebas de contenedores permanecen
bajo tests/ y se incorporarán al conectar servicios. No se declara conformidad global WCAG.

## Revisión

Revisar por Web/integración cuando SSR sea necesario, existan servicios reales, cambien contratos,
o mediciones de bundle/experiencia justifiquen nuevas herramientas. Mantener tokens y primitivas
comunes; revisar variantes antes de añadir controles duplicados.
