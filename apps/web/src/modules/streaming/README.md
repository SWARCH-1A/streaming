# Interfaz de emisiones

El estudio usa los contratos de configuración, sesiones y catálogo. Mantiene el monitor 16:9
y las credenciales a la izquierda, y los metadatos a la derecha, siguiendo
`07_08_estudio_prototipo_interactivo` de Stitch. En móvil apila las columnas.

Sin señal, muestra la portada del canal o el gráfico del sistema. El reproductor conserva
su búfer al pausar y después de ENDED; muestra la finalización cuando consume los segmentos
disponibles.

## Organización

La entrada pública es `entry.tsx`. Los consumidores usan esa entrada y los contratos del módulo;
sus componentes y estado interno permanecen encapsulados. El [shell](../../shell/README.md)
organiza las rutas globales y el layout.

La [SPEC](../../../../../docs/spec-p1/spec_04_stream.md) define el comportamiento.
[SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md) fija los criterios de accesibilidad
de sus recorridos. Los comandos están en el [README Web](../../../README.md),
y las pruebas locales se mantienen junto al código.
