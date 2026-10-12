# Interfaz de canales

Este módulo contiene la página pública del canal y su editor de descripción y portada.
Consulta el bootstrap Core y usa sus APIs de edición y upload. La página conserva los datos
del canal e indica UNKNOWN si no puede confirmar el estado de Streaming.

## Organización

La entrada pública es `entry.tsx`. Los consumidores usan esa entrada y los contratos del módulo;
sus componentes y estado interno permanecen encapsulados. El [shell](../../shell/README.md)
organiza las rutas globales y el layout.

La [SPEC](../../../../../docs/spec-p1/spec_03_channel.md) define el comportamiento.
[SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md) fija los criterios de accesibilidad
de sus recorridos. Los comandos están en el [README Web](../../../README.md),
y las pruebas locales se mantienen junto al código.
