# Documentación de STREAMING

`docs/` contiene la definición vigente del proyecto: requisitos, arquitectura, responsabilidades,
contratos y criterios de aceptación. El catálogo conserva 79 RF y 50 RNF con IDs estables.
Plane, proyecto STREAMING, contiene las mismas SPEC y agrupaciones de trabajo.
La documentación ubicada junto al repositorio remite a esta fuente.

## Orden de lectura

1. [Contexto](glosario_y_contexto.md), [decisiones de alcance](decisiones_alcance_p1.md) y [catálogo](catalogo_requisitos.md).
2. [Arquitectura](arquitectura_c4_cnc_despliegue.md) y [ADR-005](adr/ADR-005-streaming-rust-y-proyeccion-discovery.md): Core, Streaming, Chat, Media, Web y sus fronteras.
3. [Mapa de responsabilidades](mapa_sdd_p1.md) y [SPEC P1](spec-p1/README.md).
4. [Contratos y datos](contratos_modelo_datos.md), [flujos](integracion_sistema_p1.md) y [Web/proxy](integracion_frontend_reverse_proxy.md).
5. [Fases futuras](fases_futuras.md): dueño inicial y condiciones para extraer servicios.
6. [Matriz RNF](matriz_trazabilidad_rnf.md) y [criterios de entrega P1](puertas_implementacion_p1.md).
7. [Política ADR](politica_ADR.md), [registro ADR](adr/README.md) y [plantilla SPEC](sdd_template.md).

## Convenciones

Una SPEC define comportamiento y aceptación; un módulo encapsula una responsabilidad; una unidad
desplegable posee runtime, configuración, salud y release. Core contiene varios módulos. Streaming y Chat tienen procesos propios. MediaMTX se despliega separado; el adaptador técnico Media comparte el runtime Streaming en P1 según ADR-011, conservando su frontera de datos. Discovery combina datos locales con la proyección pública de Streaming. Integración y accesibilidad son trabajo transversal.

Cada módulo escribe mediante su repositorio. Dentro de Core se permiten FK, transacciones locales y
vistas de lectura publicadas con columnas explícitas; ningún proceso externo consulta sus tablas.
Los DTO públicos excluyen credenciales, email privado y secretos.

Una decisión aceptada define la solución; el cumplimiento se demuestra con evidencia de implementación.
Las tecnologías candidatas se seleccionan mediante ADR antes de incorporarlas. Los criterios de entrega
siguen abiertos hasta comprobarlos; un diagrama no acredita una prueba ejecutada.
