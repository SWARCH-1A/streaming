# Mapa de responsabilidades y especificaciones — P2

Una responsabilidad funcional no equivale a un proceso. Core agrupa módulos con transacciones
locales; Gateway, Streaming, Chat, VOD y Notificaciones tienen sus fronteras descritas en
[ADR-015](adr/ADR-015-arquitectura-p2.md).

| Responsable funcional | Propiedad | SPEC y requisitos | Ubicación actual |
| --- | --- | --- | --- |
| Core / Cuentas | Identidad, sesiones, perfil, recuperación y rol administrativo | [SPEC-01 P2](spec-p2/spec_01_auth.md); RF-001…007 y RF-076/079 | services/core, módulo accounts |
| Core / Canales y seguimiento | Canal, propiedad, follow/unfollow | [SPEC-03 P2](spec-p2/spec_03_channel.md); RF-008…016; RF-077 | services/core, módulo channels |
| Core / Catálogo | Categorías, etiquetas, valores activos y tombstones | SPEC-06 base; RF-066…069 | services/core, módulo taxonomy |
| Core / Discovery | Proyección pública y consultas live/canal | [SPEC-07 P2](spec-p2/spec_07_disc.md); RF-070…073 | services/core, módulo discovery |
| Streaming / Media | Fuente, cupo, reloj, generaciones, leases, rendiciones recibidas y empaquetado; publicación de referencias durables de segmentos | [SPEC-04 P2](spec-p2/spec_04_stream.md); RF-017…030 | services/streaming; infra/media |
| Chat | Mensajes, cuota, orden, dedupe, moderación y replay durable | [SPEC-05 P2](spec-p2/spec_05_chat.md); RF-031…037, RF-082…083 | services/chat |
| VOD | Biblioteca, metadatos, vínculo a sesión, borradores, publicación, objetos y búsqueda VOD | [SPEC-14](spec-p2/spec_14_vod.md); RF-013, RF-056…062, RF-074…075, RF-080…081 | Nuevo contenedor; ubicación y tecnologías por definir |
| Notificaciones | Bandeja, lectura, preferencias, dispositivos e intentos por medio | [SPEC-20](spec-p2/spec_20_notifications.md); RF-052…055 | Nueva unidad; ubicación y tecnologías por definir |
| Watch parties | Visualización conjunta y permisos de sala | [SPEC-21](spec-p2/spec_21_watch_party.md); RF-046…051; políticas P2-01…03 | Ubicación y políticas por definir |
| Subtítulos / accesibilidad | WebVTT de propietario para VOD; publicación/sincronía y controles accesibles | [SPEC-22](spec-p2/spec_22_subtitles.md); RF-063…065; P2-04…05 | contracts/p2.json, contracts/webvtt.py y tests/contracts; integración pendiente en VOD y reproductores Web/móvil |
| Web | SSR, rutas, HTML inicial, hidratación y módulos de interacción | [SPEC-18](spec-p2/spec_18_ssr.md); RNF-002/035…037 | apps/web |
| Móvil | Experiencia de espectador KMP + Compose, sesión segura y push | [SPEC-19](spec-p2/spec_19_mobile.md); RNF-002/007 | Nueva aplicación; ubicación por definir |
| Gateway / integración | Entrada, agregación por pantalla, errores parciales, HTTP/WS/HLS y broker compartido | [SPEC-16](spec-p2/spec_16_gateway.md), [SPEC-17](spec-p2/spec_17_broker.md), [SPEC-10](spec-p2/spec_10_contracts.md) y [SPEC-24](spec-p2/spec_24_deployment.md); RNF-003/006/023/051 | Nuevo Gateway; contracts, infra/broker (ADR-016) y tests compartidos |
| Administración | Operaciones privilegiadas sobre cada dominio y UI común | [SPEC-23](spec-p2/spec_23_admin.md); RF-076…079; P2-17 | Cuentas provisiona rol; cada dominio ejecuta su operación |

La bandeja VOD por canal puede componerse en Gateway, pero VOD conserva su consulta y autorización.
Core no almacena metadatos de biblioteca ni lee su base. Notificaciones no se convierte en autoridad
de follow. Chat no delega moderación en VOD. Cada dueño documenta datos, permisos y fallos.

## Base de implementación

Las [SPEC P1](spec-p1/README.md) conservan contratos de las capacidades existentes. P2 mantiene
sus invariantes de registro transaccional, identidad opaca, propiedad y aislamiento de bases.
La retención efímera de Chat, SPA y eventos HTTPS punto a punto son perfiles P1; para P2 rigen
las SPEC actuales, SSR y broker con consumidores independientes.

Integración mantiene infra/local/dev.py e infra/p1/manage.py. El arranque local incluye RabbitMQ
por defecto junto con los nueve servicios base; los fixtures P1 conservan HTTP y sus almacenes separados.

No se asignan integrantes nominales desde este mapa. Cada responsable cierra sus
[decisiones pendientes](decisiones_pendientes_p2.md), selecciona tecnología mediante ADR y
ratifica las interfaces marcadas en revisión y completa schemas bloqueados antes de integrar sus consumidores. Los módulos Core siguen en su
proceso; no cuentan por separado para los seis componentes de lógica.
