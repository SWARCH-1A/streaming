# Interfaz de descubrimiento

Las vistas consultan GraphQL Core con filtros, paginación y datos públicos reales.
`previewUri` permite pedir un JPEG de Media por HTTP, sin abrir reproductores ni leases en el listado.

La sección Grabaciones (RF-074/075, SPEC-07 P2 CA-06) busca VOD por título, categoría y etiqueta
con los mismos IDs exactos de los filtros en vivo, mediante `GET /api/screens/explore` del Gateway.
VOD tiene su propio cursor («Cargar más grabaciones») y su propio estado de error: si falla, solo
esa sección lo señala y las transmisiones en vivo siguen visibles, y a la inversa. La sección usa el
agregado completo del Gateway, que también consulta Discovery (12 emisiones) en cada página de VOD y la
web descarta ese dato; si se vuelve costoso, bastaría `GET /api/vods` con la misma consulta.

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
