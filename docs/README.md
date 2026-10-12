# Documentación de STREAMING

Esta carpeta reúne los requisitos, el diseño y los criterios de aceptación de STREAMING.
Es la referencia del equipo para implementar el proyecto. El catálogo contiene 79 requisitos
funcionales (RF) y 50 no funcionales (RNF), cuyos identificadores se conservan entre documentos.

En Plane, dentro del proyecto STREAMING, organizamos el trabajo a partir de estas SPEC.
Cuando cambia una especificación, su descripción en Plane debe actualizarse desde este repositorio
e indicar el commit de origen. Esa actualización conserva responsables, estados, comentarios y
relaciones. Los planes de trabajo y los resultados de las pruebas se registran por separado:
terminar una tarea de un módulo no demuestra que el recorrido completo ya funcione.
La documentación situada fuera del repositorio remite a esta carpeta.

## Por dónde empezar

1. [Contexto y términos](glosario_y_contexto.md), [alcance de P1](decisiones_alcance_p1.md) y [requisitos](catalogo_requisitos.md): qué vamos a construir y qué queda para después.
2. [Arquitectura](arquitectura_c4_cnc_despliegue.md) y [ADR-005](adr/ADR-005-streaming-rust-y-proyeccion-discovery.md): cómo se reparten las responsabilidades entre Core, Streaming, Chat, Media y Web.
3. [Mapa de responsabilidades](mapa_sdd_p1.md) y [SPEC de P1](spec-p1/README.md): qué le corresponde a cada módulo y cómo se acepta su entrega.
4. [Contratos y datos](contratos_modelo_datos.md), [flujos de integración](integracion_sistema_p1.md) y [Web y proxy](integracion_frontend_reverse_proxy.md): cómo se comunican las partes.
5. [Fases futuras](fases_futuras.md): quién se encargaría de cada capacidad y cuándo tendría sentido separar otro servicio.
6. [Recorridos de integración](matriz_recorridos_p1.md), [matriz RNF](matriz_trazabilidad_rnf.md) y [criterios de entrega](puertas_implementacion_p1.md): qué necesitamos comprobar.
7. [Política de ADR](politica_ADR.md), [decisiones registradas](adr/README.md) y [plantilla de SPEC](sdd_template.md): cómo documentamos cambios y nuevas especificaciones.

## Cómo usamos estos documentos

Una SPEC describe el comportamiento esperado y sus criterios de aceptación. Un módulo agrupa
una responsabilidad dentro del código. Una unidad desplegable tiene su propio proceso,
configuración, comprobaciones de salud y versión de entrega. Son conceptos distintos: Core
contiene varios módulos, mientras que Streaming y Chat tienen procesos propios.

MediaMTX se ejecuta por separado. En P1, el adaptador Media comparte el proceso de Streaming,
pero mantiene sus datos separados, según ADR-011. Discovery combina datos locales de Core
con una copia de los datos públicos de Streaming. Integración y accesibilidad abarcan varios módulos.

Cada módulo modifica sus datos mediante su repositorio. Dentro de Core usamos claves foráneas,
transacciones locales y vistas de lectura publicadas con columnas explícitas. Los procesos externos
se comunican por contratos y no consultan esas tablas. Los DTO públicos excluyen credenciales,
correo privado y secretos.

Los ADR explican las decisiones aceptadas y las alternativas que evaluamos. Para incorporar una
tecnología candidata, primero registramos la decisión. Para declarar cumplido un criterio,
además necesitamos resultados de implementación y pruebas; los diagramas describen el diseño.
