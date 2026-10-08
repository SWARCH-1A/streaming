# Evolución y responsabilidades futuras

**Fuente de alcance:** catálogo RF/RNF; **topología base:** ADR-005.
P2/P3/Futuro conservan la prioridad del catálogo. Esta matriz asigna dueños iniciales y dependencias;
no adelanta funcionalidades ni convierte cada tema en un servicio desplegable. Las SPEC de capacidades
futuras deben resolver sus políticas antes de implementar, conservando la prioridad y alcance del catálogo.

## Matriz de capacidades

| Capacidad / RF | Dueño inicial | Flujo y consistencia | Cuándo justificar otra unidad |
| --- | --- | --- | --- |
| Recuperación de contraseña RF-004 | Cuentas en Core | Identidad, token de un uso/expiración y cambio de credencial en SQL; email asíncrono por adaptador de entrega | SSO/federación o proveedor externo con necesidad real; perfil permanece local aunque credenciales pasen a IdP |
| Seguimiento RF-014…016 | Canales/comunidad en Core | Relación única followerUserId–channelId; FK SQL; follow/unfollow idempotentes | Volumen de grafo/lecturas demostrado y contrato que elimine joins en caliente |
| Calidad/transcoding RF-027…030 | Media; selección/metadata en Streaming Rust | Job audiovisual y rendiciones en Media, perfil/estado de job en su dueño Streaming/Media; player selecciona variantes publicadas | Worker pesado separado de ingest si CPU, cola y fallo lo justifican; no servicio por cada calidad |
| Moderación RF-036…037 | Chat | Tombstone de mensaje, bloqueo por ámbito y auditoría dentro del dueño de mensajes; live y replay aplican misma supresión | No extraer “moderación” de sus mensajes; detección automática costosa puede ser worker que propone decisiones |
| Suscripciones/premium RF-038…045 | Monetización como módulo cohesivo Core, cuando se priorice | Producto/precio, suscripción, pago y entitlement explícitos; webhook firmado/deduplicado por providerEventId; estado local transaccional e intentos de proveedor asíncronos | Riesgo operativo, pagos/contabilidad, equipo/release independientes o aislamiento de cumplimiento; extraer pagos/suscripciones/entitlements juntos primero |
| Watch party RF-046…051 | Sesiones de grupo como módulo Core; Streaming/Media/Chat colaboran por contratos | Membresía y política de acceso en una transacción local; referencias a streamId/sessionId; sincronización según SPEC futura | Servicio de sincronización solo si frecuencia/fan-out supera al API normal; el compositor audiovisual, si existe, pertenece a Media |
| Notificaciones RF-052…055 y RNF-020 | Módulo/worker de notificaciones junto a Core inicialmente | Core decide destinatarios por follow/preferencias y crea intento por evento+destinatario; entrega fuera del camino LIVE; bandeja/leído durables | Volumen/reintentos/proveedores requieren escala y operación independientes. Worker separable con inbox/dedupe; nunca fuente de verdad de follow |
| VOD RF-013, RF-056…062, RF-074…075 | Biblioteca de contenido en Core; captura/procesado en Media | Media publica artefacto/job; Core posee vodId, origen sessionId, metadata, visibilidad y catálogo; publicación solo tras objeto válido | Transcoding/captura pueden usar workers separados; biblioteca no necesita un servicio “VOD metadata” ni otra búsqueda de inicio |
| Chat Replay derivado futuro | Chat posee mensajes/consulta; Core posee vínculo VOD–sessionId y política de acceso | Player solicita ventana temporal/cursor a Chat con autorización del VOD; no copia tablas ni reinterpreta moderación; Media/Core publican mapping de offset a medio | Un índice/worker de consulta si se demuestra necesidad; replay y moderación comparten dueño y supresiones |
| Subtítulos RF-063…065 | Biblioteca Core y Streaming para metadata/autorización de emisión; Media para pistas/procesado | Pistas versionadas asociadas a VOD/stream; validación/publicación y acceso heredan política del contenido | Transcripción automática costosa como worker; no servicio de negocio por idioma o control del player |
| Administración RF-076…079 | Cada módulo mantiene sus operaciones privilegiadas; shell administrativo común | Roles/permiso en Cuentas, autorización en proveedor, auditoría por operación; UI compone pantallas | No servicio “Admin” dueño de todas las tablas. Analítica masiva puede usar proyección de solo lectura |
| Descubrimiento sobre VOD/follow/futuro | Consultas en Core | Lecturas SQL paginadas, activos visibles y permisos del contenido; índice solo si se mide cuello de botella | Motor externo/servicio de índice reconstruible por outbox+snapshot consistente con watermark; nunca autoridad de contenido/acceso |

RF-049 pide mostrar streams simultáneamente: eso se cumple inicialmente con varios reproductores.
No exige mezcla audiovisual; si se solicita luego un stream combinado, registrar ese nuevo alcance y
su costo en Media, sin atribuirlo al catálogo actual. Pagos requieren proveedor,
moneda, cancelación, devolución y reconciliación antes de implementar. Retención de VOD/chat, privacidad,
suspensión/borrado de cuenta y sincronía de pistas se decidirán en la fase correspondiente; no se inventan TTL en P1.

## Habilitadores mínimos que sí corresponden a P1

IDs opacos de cuenta/canal/stream/sesión/mensaje; handle inmutable; metadata versionada; timeline de
sesión y snapshot de autor en Chat; formatos públicos sin credenciales; repositorios encapsulados,
FK locales; mensajes ordenados/deduplicados; callbacks Media idempotentes; outbox para
ciclo de sesión hacia Chat y snapshots públicos hacia Discovery. No se construyen bus universal, entitlement engine, grabación,
export API de replay ni tablas vacías de todas las futuras capacidades.

## Flujos futuros sin coordinador global

- **Inicio y notificación:** Streaming confirma LIVE → outbox → worker de notificación
  resuelve followers/preferencias y deduplica entrega. Fallo de entrega nunca revierte LIVE.
- **Compra y acceso:** Monetización registra intento → proveedor → webhook/reconciliación → actualiza
  pago/suscripción/entitlement en su transacción. El proveedor de contenido consulta una política explícita;
  no recorre cuentas→pagos→suscripciones→premium como servicios separados por solicitud.
- **Publicar VOD:** job Media termina → Core valida objeto y publica biblioteca → consulta local lo
  descubre. Chat consulta por sesión al reproducir; un borrado genera trabajo idempotente en Media/Chat,
  con tombstone de visibilidad inmediato en Core y limpieza observable, sin prometer transacción global.
- **Administrar:** la UI llama al dueño de la operación; Core/Streaming/Chat verifican permisos y auditan. El shell
  administrativo no modifica directamente bases ni se convierte en motor de workflows.

La frontera Streaming Rust ya está aceptada en ADR-005 por autonomía a lo largo de las iteraciones, con costo de coordinación explícito y rendimiento integrado pendiente de medir. Las capacidades futuras mantienen su priorización propia.

## Puerta obligatoria para extraer un servicio

Un ADR nuevo debe demostrar: capacidad cohesiva y dueño único; invariante transaccional que no queda
partido sin explicación; demanda de escala/fallo/release distinta medida; consulta sin joins de red
por fila; costo operativo de DB/TLS/secretos/backups/on-call; protocolo y presupuesto de latencia;
compatibilidad/migración/rollback; snapshot completo y watermark para reconstrucción si usa proyecciones;
idempotencia y recuperación del único dueño del workflow. Si la extracción exige un coordinador que
conozca internamente a todos los participantes, revisar primero la frontera.

Separación de infraestructura (worker, proceso de cómputo, réplica) no implica inventar otro dominio.
ADR-011 integra el adaptador técnico Media en el runtime Streaming durante P1. El próximo prototipo reevaluará su extracción si la carga, el aislamiento o los releases requieren independencia; conservar contratos/bases no obliga a añadir procesos sin esa necesidad.

La plataforma debe poder evolucionar por módulos y adaptadores antes de distribuir datos por necesidad futura imaginada.
