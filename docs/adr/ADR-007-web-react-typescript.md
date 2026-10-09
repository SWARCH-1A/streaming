# ADR-007: Web modular con React, TypeScript y SWC

- Estado: aceptada
- Fecha: 2026-10-02
- Responsable: Web / integración
- SDD/contratos afectados: SPEC-08, SPEC-12; sin cambios de contratos de red

## Contexto

La aplicación requiere una base modular con componentes compartidos, tipos estrictos,
formato, lint y pruebas. Stitch aporta los tokens y vistas.
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

Los datos de demostración pertenecen a la biblioteca de componentes y a pruebas, no son fallback
para las vistas funcionales. ADR-013 define HTTP/WS/HLS, CSRF, cookies HttpOnly, contexto Chat y
bootstrap desde los proveedores reales; la autorización permanece en el backend.

Vitest + Testing Library verifican comportamientos, límites Unicode, filtros y formularios;
Playwright cubre rutas y recorridos responsive. CI ejecuta calidad y build; los recorridos E2E se ejecutan mediante la suite de integración manual.

## Opciones consideradas

- React con Vite/SWC: build único, compilación rápida y despliegue estático sencillo.
- Next.js: agrega runtime/SSR sin necesidad en esta base; no se selecciona.
- Mantener HTML generado/CDN: duplica estilos y controles, sin contratos tipados ni tests; descartado.
- Microfrontends: contradicen ADR-005 y multiplican builds; descartados.

## Consecuencias

Se fija un stack reproducible y límites de módulo verificables. Las muestras visuales no acreditan SPEC integradas. ADR-013 conecta HTTP/WS/HLS, autorización, uploads y proxy conservando esta base. Capacidades futuras
presentes en Stitch (pagos, drops, VOD, moderación) no adquieren implementación P1.
El servidor de despliegue deberá respetar fallback solo para rutas SPA, nunca para API o /internal.

## Verificación

pnpm check y pnpm test:e2e son las puertas ejecutables de esta base. Los tests se mantienen junto
al módulo; los recorridos de esta SPA están en apps/web/e2e. Pruebas de contenedores permanecen
bajo tests/integration/. No se declara conformidad global WCAG.

## Revisión

Revisar por Web/integración cuando SSR sea necesario, existan servicios reales, cambien contratos,
o mediciones de bundle/experiencia justifiquen nuevas herramientas. Mantener tokens y primitivas
comunes; revisar variantes antes de añadir controles duplicados.
