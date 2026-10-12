# Shell Web

El shell reúne las rutas, la navegación, el layout y la sesión común.
`App.tsx` registra las rutas y `AppLayout` compone la cabecera y la barra lateral;
en móvil, la navegación usa un diálogo nativo. `RouteFocus` gestiona título y foco,
y `ViewErrorBoundary` aísla los errores de cada vista, incluido Chat.

`SessionProvider` restaura la sesión desde el perfil self y la identidad pública de Core.
También gestiona login y logout mediante los clientes HTTP compartidos. El servidor comprueba
los permisos; cada módulo implementa las acciones de su funcionalidad.

La aplicación usa React Router declarativo y un build Vite/SWC, según ADR-007.
Los comandos, rutas y reglas de imports están en el [README Web](../../README.md).
[SPEC-12](../../../../docs/spec-p1/spec_12_int.md) define su integración con los servicios y el proxy.
