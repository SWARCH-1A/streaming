# Shell Web

Composición de rutas, navegación, layout, sesión común demo y boundaries por vista. `App.tsx`
registra rutas y `AppLayout` compone header/sidebar con navegación móvil en dialog nativo.
`RouteFocus` gestiona título/foco; `ViewErrorBoundary` aísla vistas y Chat.
La lógica de cada funcionalidad vive en su módulo. No hay clientes de red ni autorización real.

La base usa React Router declarativo y un build Vite/SWC según ADR-006. Configuración, comandos,
rutas, imports y límites de la demo en [README Web](../../README.md).
Definición de integración futura: [SPEC-12](../../../../docs/spec-p1/spec_12_int.md).
