# Criterios de entrega P1

Cada criterio se cierra con evidencia de implementación y ejecución.

Las decisiones se documentan en SPEC/contratos/ADR; Plane conserva planes de trabajo y evidencia.

| Puerta | Acción antes de aceptar | Dueño / impacto |
| --- | --- | --- |
| Registro local | Implementar transacción cuenta/perfil/canal/idempotencia y FK; rollback/retry tras perder respuesta | Cuentas/Canales Core; publicación solo tras commit |
| Stack por proceso | Confirmar lenguajes generales en artefactos reales y uso NoSQL justificado; Java y Rust seleccionados mediante ADR; Chat Go/Redis (ADR-010) y Web React/TypeScript/SWC (ADR-007) aceptados | Responsables Web/Chat/Core/Streaming; no seleccionar stack distinto por módulo local |
| Dos conectores HTTP | Evidenciar REST/GraphQL/WS y confirmación docente de al menos dos tipos | SPEC-13; no declarar RNF-006 cerrado por un diagrama |
| Contexto Chat | Schema neutro, TLS/servicio/Origin/cookie, revocación local Core y estado/timeline actual Streaming; medir timeout Core 400ms/presupuesto hasta intentar persistir<=500ms (ADR-010) y carrera de operaciones en vuelo | Core/Streaming/Chat; p95 de entrega <1s, sin auth cacheada |
| Mensajes durables y escalado | Dedupe/secuencia/cuota por cuenta consistentes, persistencia antes de ACK y broadcast recuperable; propietario/fan-out por sala entre réplicas | Chat; no contador independiente por réplica ni ACK perdido |
| Outbox de sesión | SQL Streaming→inbox Chat, HTTP idempotente, backoff/alerta/dead-letter, reparación snapshot; capacidad/retención operativa definida | Emisiones/Chat; sin broker obligatorio |
| Recuperación total Chat | Snapshot paginado con watermark para sesiones o backup/inventario durable; lookups de IDs conocidos no reconstruyen pérdida total | Streaming/Core/Chat; antes de afirmar RNF-050 completo |
| Media y códecs | MediaMTX y adaptador Rust según ADR-005; configuración/digest, señal real/callbacks/sourceGeneration/path, stop y prueba RTMP→HLS | Media/Emisiones; LIVE nunca simulado |
| Reloj/reinicio | Una sola gracia, timer/callback serializados, recuperación de restante o ENDED; multi-réplica requiere fencing/transferencia | Streaming Rust; una réplica inicial no elimina concurrencia |
| Callbacks/DLQ Media | ACK durable/dedupe, retry2s/calendario15min/alerta30s/dead-letter sin TTL, umbral de capacidad/redrive probado | Media/Streaming; no pérdida silenciosa |
| Schemas y compatibilidad | Materializar/generar contratos, schemas/error/ejemplos, adopción y retiro de endpoints viejos | SPEC-10; no dos fuentes semánticas |
| Seguridad e imágenes | Conservar hash/CSRF/sesión del ADR-001 y almacenamiento ADR-002/009; avatar>=200×200 obligatorio; GIF/dimensiones/píxeles/limpieza, S3 compartido o volumen compartido antes de réplicas | Core; restricciones de Cuentas |
| Consulta/frescura | SQL acotado/paginación/snapshot/cursor/índices, proyección Streaming pública con inbox/versiones, observación/publicación<=2s y entrega/aplicación<=3s; reconstrucción consistente con watermark, sin HTTP por fila | Consultas Core; no runtime Discovery independiente |
| Perfil integrado | Cinco RTMP/100 players/20msg/s/10min, API10req/s y leases por separado; máximos/p95 originales y fallos reales | SPEC-13; no llamar “cumplido” sin ejecución |
| Enumeración de identidad | Mantener conflicto genérico/cuotas; sin verificación email sigue riesgo residual por tiempos/señales | Cuentas; no afirmar eliminación total |
| Capacidades futuras | Dueño inicial/capacidad priorizada/política retención/pago/acceso y criterio de extracción, sin nuevas tablas/servicios por anticipación | Fases futuras; no cambios de producto implícitos |
