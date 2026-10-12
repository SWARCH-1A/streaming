# Interfaz de descubrimiento

Las vistas consultan GraphQL Core con filtros, paginación y datos públicos reales.
`previewUri` permite pedir un JPEG de Media por HTTP, sin abrir reproductores ni leases en el listado.

El listado se actualiza cada 10 s y el fotograma cada 15 s. Si falta la imagen, muestra el SVG
del sistema y vuelve a intentarlo a los 5 s. Las muestras locales se reservan para demos y pruebas.

## Organización

La entrada pública es `entry.tsx`. Los consumidores usan esa entrada y los contratos del módulo;
sus componentes y estado interno permanecen encapsulados. El [shell](../../shell/README.md)
organiza las rutas globales y el layout.

La [SPEC](../../../../../docs/spec-p1/spec_07_disc.md) define el comportamiento.
[SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md) fija los criterios de accesibilidad
de sus recorridos. Los comandos están en el [README Web](../../../README.md),
y las pruebas locales se mantienen junto al código.
