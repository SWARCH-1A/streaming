# SPEC-15 — Integración del prototipo 2

**Prioridad:** P2. **Responsable:** Integración y responsables de componentes.
**Estado:** diseño para desarrollo. **RF:** todos salvo RF-038…045. **RNF:** RNF-001…051.

## 1. Contexto y problema

Web SSR y móvil deben consumir una plataforma con agregación, eventos, VOD, replay y
notificaciones sin acoplar los clientes a la topología interna. [Alcance](../alcance_p2.md).

## 2. Definición del componente

El Gateway es una unidad de comunicación/orquestación con código de composición y errores.
Web SSR y móvil son presentaciones. El broker es infraestructura compartida; sus consumidores
ejecutan los trabajos. Core, Streaming, Chat, VOD y Notificaciones conservan sus datos y reglas.

## 3. Historia de usuario

Como espectador, quiero abrir un canal desde web o móvil y usar las partes disponibles aunque
una sección falle. Como equipo, necesitamos contratos que permitan desarrollar por componente.

## 4. Alcance

Incluye SSR React Router, móvil KMP/Compose, entrada Gateway, agregación por pantalla, HTTP
síncrono y broker único, VOD/replay, seguimiento, recuperación, notificaciones, administración
y las capacidades de watch parties/subtítulos por definir por sus responsables. Excluye pagos,
suscripciones de pago, premium y emisión desde cámara móvil.

## 5. Requisitos funcionales

Cada RF del [catálogo](../catalogo_requisitos.md) tiene dueño en el [mapa](../mapa_sdd_p1.md).
RF-038…045 quedan fuera de P2. Los contratos de seguimiento/recuperación permanecen en Core;
Notificaciones recibe trabajos y resuelve medios. RF-079 afecta autorización y disponibilidad
de canal, live, VOD y replay. Watch parties/subtítulos mantienen RF-046…051 y RF-063…065 sin
fijar sus políticas pendientes como decisiones aceptadas.

## 6. Criterios de aceptación

- [ ] CA-01: una navegación inicial obtiene HTML SSR con datos reales; el navegador hidrata y
  conserva HLS/WS. Navegación y restauración de sesión no duplican conexiones ni filtran credenciales.
- [ ] CA-02: el Gateway compone datos por pantalla. Ante VOD caído con canal disponible, conserva
  el canal y señala la sección fallida. Ausencia confirmada, fallo y acceso denegado se distinguen.
- [ ] CA-03: HLS y WebSocket siguen accesibles por la entrada pública sin agregación de sus
  flujos, pérdida de Upgrade, MIME types, segmentos anidados o Range.
- [ ] CA-04: consultas periódicas muestran cambios de estado/conteo dentro de cinco segundos
  en condiciones normales, incluyendo la latencia de proyección y el intervalo del cliente.
- [ ] CA-05: móvil demuestra login, descubrimiento, HLS live/VOD, chat, seguimiento y bandeja en
  Android. Se informa la cobertura iOS ejecutada, sin atribuir compatibilidad por compilación teórica.
- [ ] CA-06: cookie/CSRF web y Bearer móvil comparten expiración/revocación. Chat autentica ambos
  transportes. Suspensión impide nuevas acciones y acceso al contenido restringido por URL directa.
- [ ] CA-07: Core y Chat reciben independientemente el ciclo de sesión. Duplicados/desorden,
  consumidor caído y reinicio no pierden efectos confirmados ni autorizan desde una proyección vieja.
- [ ] CA-08: un worker procesa un trabajo real de VOD o Notificaciones fuera de la solicitud.
  El broker por sí solo no acredita procesamiento asíncrono.
- [ ] CA-09: bandeja, leído, preferencias, correo y push funcionan con intentos independientes.
  Reconectar una sesión no repite su aviso lógico. Prueba real de push requiere provisión externa.
- [ ] CA-10: RF-004 y RF-014…016 tienen contratos idempotentes/seguros y recorridos integrados;
  la entrega SMTP de recuperación tiene resultado observable y reintentos seguros.
- [ ] CA-11: las políticas de watch parties/subtítulos se cierran antes de aceptar sus RF. La
  administración valida rol/propiedad y conserva publicación al suspender/reactivar.
- [ ] CA-12: inventario de ejecución demuestra presentaciones, lógica, datos, cinco lenguajes,
  conectores, asincronía y contenedores. Diagramas y estados de tareas no sustituyen esa evidencia.

## 7. Diseño técnico y datos

[Arquitectura](../arquitectura_c4_cnc_despliegue.md), [contratos](../contratos_modelo_datos.md)
y [Web/Gateway](../integracion_frontend_reverse_proxy.md) definen fronteras. Las firmas P2 y sus ejemplos están en [contratos HTTP/WS](../contratos_p2_http.md),
[eventos](../contratos_p2_eventos.md), [medio/S3](../contratos_p2_media.md) y
[contracts/p2.json](../../contracts/p2.json). Se ratifican las interfaces en revisión y se completan
las bloqueadas antes de integrar. Los artefactos `contracts/p1.json` y sus derivados describen
los contratos base de P1. Las interfaces P2 requieren sus propios schemas y consumidores.

Las respuestas compuestas conservan estado de cada sección y correlación. El Gateway no cambia
un error en una colección vacía ni reintenta ciegamente mutaciones. Un fallo de autorización
no se degrada a permiso. Las versiones y timestamps del productor conservan su significado.

## 8. Dependencias y contratos de integración

| Definición particular | SPEC |
| --- | --- |
| Cuentas / Canales / Streaming / Chat / Discovery | [01](spec_01_auth.md), [03](spec_03_channel.md), [04](spec_04_stream.md), [05](spec_05_chat.md), [07](spec_07_disc.md) |
| Contratos y VOD | [10](spec_10_contracts.md), [14](spec_14_vod.md) |
| Gateway / broker / SSR / móvil / Notificaciones | [16](spec_16_gateway.md), [17](spec_17_broker.md), [18](spec_18_ssr.md), [19](spec_19_mobile.md), [20](spec_20_notifications.md) |
| Watch parties / subtítulos / administración / despliegue | [21](spec_21_watch_party.md), [22](spec_22_subtitles.md), [23](spec_23_admin.md), [24](spec_24_deployment.md) |


| Dependencia | Propósito | Fallo |
| --- | --- | --- |
| Core / Streaming / Chat / VOD | Consultas, comandos y autorización | Timeout acotado; fallo parcial o rechazo según necesidad |
| Broker | Distribución a consumidores independientes | Outbox, confirmación, dedupe, reintentos y DLQ |
| Notificaciones / SMTP / push | Bandeja y entrega por medio | Intentos independientes; no revierte LIVE |
| S3 externo / compatible local | Persistencia de segmentos/manifiestos | Reintento y validación antes de publicar |
| Presentaciones | HTML SSR y aplicación espectadora | Errores visibles, navegación y accesibilidad |

## 9. Decisiones y preguntas abiertas

[ADR-015](../adr/ADR-015-arquitectura-p2.md) registra las decisiones aceptadas.
[Decisiones pendientes](../decisiones_pendientes_p2.md) asigna tecnologías, políticas funcionales,
contratos y provisión a los responsables. La clasificación académica del Gateway y el inventario
de seis componentes lógicos permanecen pendientes de verificación.

## 10. Verificación

| RF/RNF | Evidencia requerida |
| --- | --- |
| RNF-001…010/051 | Procesos, cuatro datos, SSR/móvil, cinco lenguajes, HTTP, workers y arranque reproducible |
| RNF-011…021/023 | Perfil de carga, frescura completa, HLS/WS y fallos parciales |
| RF-004/014…016/052…055; RNF-020 | Recuperación SMTP, follow/preferencias, bandeja y entrega real por medio |
| RF-076…079; RNF-025…032 | Rol administrativo, suspensión, reactivación y acceso directo |
| RF-046…051/063…065 | Criterios específicos del responsable tras cerrar políticas |
| RNF-022/024/040…050 | Durabilidad, privacidad, contratos, aislamiento, métricas y recuperación |
| RNF-033…039 | Recorridos web/móvil, teclado, controles, errores y catálogos sin reconstruir cliente |

La [matriz de recorridos P2](../matriz_recorridos_p2.md) asigna responsables y pruebas por flujo.
Los criterios particulares live de SPEC-01…13 P1 siguen siendo evidencia de base; las funciones
nuevas requieren sus escenarios. El artefacto P2 incluye C&C, despliegue, capas y descomposición,
descripción de elementos/conectores/estilos, equipo, nombre, logo y descripción del proyecto,
y comandos desde un checkout limpio.

## 11. Esfuerzo, riesgos y consecuencias

Las interfaces de Gateway, broker, grabación, replay y autenticación móvil necesitan adopción
coordinada. El cierre de código es el 30 de octubre; la aceptación integrada y el artefacto se
preparan para el 2 de noviembre. Proveedores push y empaquetado multicalidad son dependencias
visibles, y las tecnologías abiertas no deben transformarse en dependencias implícitas.
