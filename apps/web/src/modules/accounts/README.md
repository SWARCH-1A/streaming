# Interfaz accounts

Vistas, estado y acciones propias de accounts. Incluye registro, login y perfil; autenticación y presentación pertenecen al mismo módulo UI.
Rutas globales/layout pertenecen a [shell](../../shell/README.md).

Definición: [SPEC](../../../../../docs/spec-p1/spec_01_auth.md).
Accesibilidad en cada recorrido: [SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md).

## Base frontend

La entrada pública es `entry.tsx`; los consumidores no importan componentes/estado internos.
La implementación actual usa modelos de presentación y datos locales de demostración.
Contratos e integración real siguen la SPEC del módulo. Ejecución y reglas en
[README Web](../../../README.md). Tests locales junto al código.
