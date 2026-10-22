# Documentación de STREAMING

Esta carpeta contiene la definición de P2: 83 requisitos funcionales y 51 no funcionales,
responsabilidades, contratos y criterios de aceptación. Los diagramas y las decisiones describen
el diseño; la implementación y las pruebas se acreditan por separado.

## Lectura principal

1. [Alcance P2](alcance_p2.md) y [catálogo de requisitos](catalogo_requisitos.md): funciones y políticas de la entrega.
2. [Decisiones pendientes](decisiones_pendientes_p2.md): preguntas que resuelve cada responsable antes de fijar su interfaz.
3. [Arquitectura y despliegue](arquitectura_c4_cnc_despliegue.md) y [ADR-015](adr/ADR-015-arquitectura-p2.md): presentaciones, Gateway, comunicación híbrida, VOD y notificaciones.
4. [Mapa de responsabilidades](mapa_sdd_p1.md) y [SPEC P2](spec-p2/README.md): propietarios y aceptación por capacidad.
5. [Contratos y datos](contratos_modelo_datos.md), [HTTP/WS P2](contratos_p2_http.md), [eventos P2](contratos_p2_eventos.md) y [S3/timeline](contratos_p2_media.md): interfaces y coordinación.
6. [Recorridos P2](matriz_recorridos_p2.md), [flujos](integracion_sistema_p1.md) y [Web, móvil y Gateway](integracion_frontend_reverse_proxy.md): integración y aceptación.
7. [Fases futuras](fases_futuras.md), [ADR](adr/README.md), [política ADR](politica_ADR.md) y [plantilla SPEC](sdd_template.md).

## Documentación de P1

Las [SPEC P1](spec-p1/README.md), los [recorridos P1](matriz_recorridos_p1.md), la
[matriz RNF P1](matriz_trazabilidad_rnf.md) y las [puertas P1](puertas_implementacion_p1.md)
conservan el perfil de la primera entrega. No acreditan SSR, broker, aplicación móvil ni VOD.
Los comandos del [README del repositorio](../README.md) describen el código disponible.

Las SPEC P2 y el alcance vigente rigen las capacidades ampliadas. Los esquemas de
[contratos base](../contracts/README.md) son la base del código existente; los nuevos contratos
requieren esquemas y generación consistentes antes de integrar consumidores.

## Propiedad y mantenimiento

Una SPEC organiza comportamiento y aceptación; un módulo organiza responsabilidades del código;
un componente desplegable tiene proceso, configuración, salud y versión propios. Core contiene
varios módulos. Streaming, Chat, VOD y Notificaciones tienen fronteras independientes; el Gateway
compone datos y el servidor SSR renderiza HTML.

Cada dueño escribe sus datos y expone contratos. Los módulos Core comparten transacciones locales;
los servicios no leen tablas ajenas. Ningún DTO público expone secretos o datos privados.

Plane organiza el trabajo del proyecto STREAMING. Al publicar una actualización de SPEC, su
referencia en Plane debe indicar el commit de origen y conservar responsables, estados y relaciones.
Una tarea terminada no acredita por sí sola un recorrido integrado. Las tecnologías nuevas se
seleccionan mediante ADR por el responsable correspondiente.
