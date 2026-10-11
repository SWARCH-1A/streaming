# Imágenes de la Web

`signal-grid.svg` es un gráfico vectorial propio: retícula, ondas y anillos en los colores del sistema.
Se usa en autenticación/perfil y como portada o fondo de emisión cuando el canal no tiene imagen.
El contenido y estado se escriben en HTML; el SVG es decorativo, sin texto ni solicitudes externas.
Los canales LIVE de exploración muestran un JPEG real de su señal entregado por Media.

Los JPEG `explore-N.jpg` y `studio-N.jpg` son referencias de los exports Stitch originales.
Se conservan únicamente para las demos explícitas de componentes/prototipo; no representan emisiones
ni usuarios reales de la aplicación funcional. El prefijo y número siguen su orden en
`03_explorar_y_buscar/code.html` y `07_08_estudio_prototipo_interactivo/code.html`.

Vite empaqueta los assets importados con `@/public/images/`; las fuentes Inter y Plus Jakarta Sans
proceden de @fontsource-variable y conservan sus licencias en sus dependencias.
