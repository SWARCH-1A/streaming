# SPEC-11 Flujos de sesión y eventos entre dominios P1

- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Define la transacción local de registro y los dos cruces reales de negocio: Core–Media y Core–Chat. Evita coordinación de estados que pueden confirmarse en el mismo dueño de datos.

## 2. Definición del componente

Los contratos de Core–Chat/Media y las interfaces locales se definen en contratos_modelo_datos.md. Cada flujo pertenece a su dueño de negocio; Integración no ejecuta workflows.

## 3. Historia de usuario

Como espectador o streamer, quiero que vista de canal, reproductor, chat y búsqueda representen la misma sesión aun ante retrasos o reconexiones.

## 4. Alcance

### Dentro de P1
Registro transaccional e idempotente; configuración/sesión/cupo/metadata/leases locales; autorización/callbacks Media; contexto autorizado Chat; estado de sala por notificación durable y snapshot. Fallo, timeout, dedupe, generación y recovery en fronteras reales.

### Fuera de P1
Saga de cuenta–canal, replicación de módulos Core, VOD/replay funcional, notificaciones/pagos y exactly-once.

### Supuestos
Emisiones dueño de reloj/estado, Chat de mensajes; entrega de eventos al menos una vez con outbox/inbox durables.

## 5. Requisitos de interacción

Core confirma cuenta/perfil/canal juntos, no PENDING nuevo. Metadata y catálogo se validan localmente. Media confirma evidencia HLS, sourceGeneration/eventId evita fuente/callback viejo. Chat obtiene contexto nuevo por mensaje; eventos solo informan sala. Lease no es participante de chat. Contexto es un snapshot acotado; operaciones en vuelo no se revierten retroactivamente.

## 6. Criterios de aceptación

- CA-01: diagramas cubren registro local, configuración, RTMP/HLS, gracia/fin, Chat y leases.
- CA-02: retry tras commit conserva cuenta/perfil/canal, sin gate de activación ni duplicado; evento sesión duplicado no duplica sala ni reabre generación vieja.
- CA-03: HLS reconectado antes de 30 s conserva sesión; a 30.000 s ENDED gana transición serializada, a 29.999 s recuperación válida; reinicio no extiende ventana.
- CA-04: estado público <=5 s, autorización posterior a ENDED rechaza envío; historial conserva lectura.
- CA-05: edición válida de perfil/metadata/catalogo refleja commit; inválido conserva estado.
- CA-06: recuperación Chat por snapshot/outbox sin duplicados; fallo Chat o endpoint Discovery no corta HLS disponible. Caída Core documenta alcance real de APIs.
- CA-07: leases tras primer frame, heartbeat10s/expiry30s/cierre inmediato, sin conteo cliente.
- CA-08: fallo entre escrituras registro hace rollback total; respuesta perdida después de commit recupera IDs.
- CA-09: callbacks Media preservan envelope, ACK durable, timeout2s, calendario de retry hasta15min, alerta30s, dead-letter/redrive sin TTL y manejo410 como obsoleto.
- CA-10: conteo consultable local, observación <=5s y frescura separada de estado; ENDED excluido, no proyección remota ViewerCountChanged.
- CA-11: versiones se comparan por agregado/sesión vigente, streamGeneration antes de sessionVersion; secuencia de mensaje no compara con countVersion.
- CA-12: una llamada Core por nuevo envío retorna sesión/autor/timeline confiables; sin caché de permisos. Core caído rechaza sin persistencia; operación ya autorizada tiene presupuesto acotado explícito.

## 7. Diseño técnico y datos

Registro una transacción SQL; sesión commit + outbox Core→Chat; receptor inbox durable. HTTP privado idempotente sin broker inicial. Composición Canal/Discovery usa SQL local. P1 una réplica Core, transiciones concurrentes con bloqueo/CAS; multi-réplica requiere ADR de fencing/clock. Correlación por request/eventId sin secretos.

## 8. Dependencias y contratos de integración

contratos_modelo_datos.md especifica schemas/timeouts; integracion_sistema_p1.md secuencias; proxy en documento frontend. Core–Media autentica/observa fuente; Core–Chat autoriza contexto/notifica ciclo. Todos los demás cruces de datos Core son locales.

## 9. Decisiones y preguntas abiertas

Estado y cuotas preservados, topología de ADR-003; delivery session-events HTTP con outbox/inbox, sin bus universal. P1 no genera eventos para crear proyecciones locales innecesarias.

## 10. Verificación

Verificar: rollback/retry de registro, callback duplicado/ACK perdido/antiguo, bordes29.999/30.000s, autorización tras logout/fin, cuota/dedupe/commit/broadcast de Chat, reinicios y recuperación sin extender gracia, HLS aislado de Chat.

## 11. Esfuerzo, riesgos y consecuencias

**Riesgos:** Core como unidad de fallo, autorización síncrona Chat, operaciones en vuelo y medios externos. La coordinación legítima pertenece al dueño de cada caso de uso; Integration no agrega lógica.
