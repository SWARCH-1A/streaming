# Interfaz discovery

Vistas, estado y acciones propias de discovery. Publica su entrada para composición en shell, sin importar internals de otros módulos.
Rutas globales/layout pertenecen a [shell](../../shell/README.md).

Definición: [SPEC](../../../../../docs/spec-p1/spec_07_disc.md).
Accesibilidad en cada recorrido: [SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md).

## Base frontend

La entrada pública es `entry.tsx`; los consumidores no importan componentes/estado internos.
Las vistas funcionales consultan GraphQL Core con filtros, paginación y datos públicos reales.
`previewUri` permite solicitar un JPEG de Media por HTTP, sin reproductores ni leases en el listado.
El listado se refresca cada 10 s y el fotograma cada 15 s; una imagen ausente usa el SVG del sistema
y reintenta a los 5 s. Las muestras locales pertenecen solo a demos explícitas y pruebas. Ejecución y reglas en
[README Web](../../../README.md). Tests locales junto al código.
