# Interfaz streaming

Vistas, estado y acciones propias de streaming. Publica su entrada para composición en shell, sin importar internals de otros módulos.
Rutas globales/layout pertenecen a [shell](../../shell/README.md).

Definición: [SPEC](../../../../../docs/spec-p1/spec_04_stream.md).
Accesibilidad en cada recorrido: [SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md).

## Base frontend

La entrada pública es `entry.tsx`; los consumidores no importan componentes/estado internos.
El estudio consume los contratos reales de configuración, sesiones y catálogo. Mantiene el
monitor 16:9 y las credenciales a la izquierda y los metadatos a la derecha, siguiendo
`07_08_estudio_prototipo_interactivo` de Stitch; en móvil apila las columnas. Sin señal utiliza
la portada del canal o el gráfico del sistema. El reproductor conserva su búfer al pausar y
tras ENDED; muestra la finalización después de consumir los segmentos disponibles. Ejecución y reglas en
[README Web](../../../README.md). Tests locales junto al código.
