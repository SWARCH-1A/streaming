# Interfaz de categorías y etiquetas

`useCatalog` consulta GET /api/taxonomy y ofrece las categorías y etiquetas activas.
Las opciones provienen del servidor, por lo que los nuevos valores llegan sin reconstruir Web.
Los datos locales de muestra se usan en pruebas y demos explícitas.

## Organización

La entrada pública es `entry.tsx`. Los consumidores usan esa entrada y los contratos del módulo;
sus componentes y estado interno permanecen encapsulados. El [shell](../../shell/README.md)
organiza las rutas globales y el layout.

La [SPEC](../../../../../docs/spec-p1/spec_06_tax.md) define el comportamiento.
[SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md) fija los criterios de accesibilidad
de sus recorridos. Los comandos están en el [README Web](../../../README.md),
y las pruebas locales se mantienen junto al código.
