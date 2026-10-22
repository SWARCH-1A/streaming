# Especificaciones vigentes de P2

Cada responsabilidad tiene una SPEC. Cuentas, Canales, Streaming, Chat, Discovery y Contratos
conservan sus IDs al evolucionar. Gateway, broker, SSR, móvil y Notificaciones tienen SPEC propias;
SPEC-15 verifica recorridos entre componentes, sin reemplazar sus definiciones particulares.

| SPEC | Responsable |
| --- | --- |
| [SPEC-01 Cuentas, recuperación y sesión web/móvil](spec_01_auth.md) | Core / Cuentas |
| [SPEC-03 Canales y seguimiento](spec_03_channel.md) | Core / Canales |
| [SPEC-04 Multicalidad, grabación y eventos de Streaming](spec_04_stream.md) | Streaming / Media |
| [SPEC-05 Chat, moderación y replay](spec_05_chat.md) | Chat |
| [SPEC-07 Discovery, proyecciones y búsqueda compuesta](spec_07_disc.md) | Core / Discovery |
| [SPEC-10 Contratos compartidos P2](spec_10_contracts.md) | Integración y dueños de interfaces |
| [SPEC-14 Biblioteca VOD](spec_14_vod.md) | VOD |
| [SPEC-15 Integración P2](spec_15_int.md) | Integración |
| [SPEC-16 API Gateway y agregación por pantalla](spec_16_gateway.md) | Gateway |
| [SPEC-17 Broker y entrega durable de eventos y trabajos](spec_17_broker.md) | Mensajería / integración |
| [SPEC-18 Web React Router SSR](spec_18_ssr.md) | Web |
| [SPEC-19 Aplicación KMP y Compose Multiplatform](spec_19_mobile.md) | Móvil |
| [SPEC-20 Bandeja y entrega de notificaciones](spec_20_notifications.md) | Notificaciones |
| [SPEC-21 Watch parties](spec_21_watch_party.md) | Responsable de watch parties |
| [SPEC-22 Pistas de subtítulos y controles](spec_22_subtitles.md) | Responsable de subtítulos / accesibilidad |
| [SPEC-23 Administración, suspensión y retirada](spec_23_admin.md) | Administración / dueños de dominio |
| [SPEC-24 Despliegue, datos y operación P2](spec_24_deployment.md) | Integración / responsables de componentes |

## Contratos y aceptación

- [HTTP y WebSocket](../contratos_p2_http.md): rutas, entrada/salida, permisos, fallos e idempotencia.
- [Eventos y trabajos](../contratos_p2_eventos.md): envelope, tipos, productores, suscripciones y recuperación.
- [S3, grabación y timeline](../contratos_p2_media.md): objetos, índice final, rendiciones y replay.
- [Fuente estructural P2](../../contracts/p2.json) y [validación](../../contracts/README.md).
- [Recorridos P2](../matriz_recorridos_p2.md): trazabilidad funcional y pruebas integradas.

## Alcance y versiones

[Alcance](../alcance_p2.md) y [catálogo](../catalogo_requisitos.md) fijan las funciones.
[Decisiones pendientes](../decisiones_pendientes_p2.md) asigna las políticas y tecnologías aún
abiertas. Watch parties y subtítulos tienen SPEC y operaciones requeridas; sus firmas definitivas
siguen bloqueadas por las políticas que debe elegir su responsable.

Las [SPEC P1](../spec-p1/README.md) conservan exclusivamente el perfil de la primera entrega.
Para las responsabilidades ampliadas rige una sola definición vigente: la SPEC P2 del mismo ID.
Catálogo (SPEC-06) y accesibilidad base (SPEC-08) conservan sus reglas; las nuevas pantallas se
verifican además en SPEC-18/19/22. Registro/perfil, control live y consultas mantienen sus contratos
base salvo las ampliaciones explícitas de P2. Las fuentes P1 no acreditan broker, SSR, móvil o VOD.
