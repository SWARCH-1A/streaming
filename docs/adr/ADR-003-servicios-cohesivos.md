# ADR-003: Core modular, Chat y Media como fronteras desplegables

- Estado: sustituida por [ADR-005](ADR-005-streaming-rust-y-proyeccion-discovery.md)
- Fecha: 2026-10-01
- Responsable: Core, Chat, Media e Integración
- SPEC/contratos afectados: SPEC-01, SPEC-03…SPEC-13; matriz RNF, arquitectura, contratos y fases futuras.

## Contexto

P1 requiere cinco emisiones simultáneas, cien espectadores y veinte mensajes/s agregados. Cuenta,
perfil y canal se publican juntos; configuración de emisión requiere propietario, catálogo y cupos.
Chat tiene conexiones largas, distribución y persistencia de mensajes; Media tiene carga audiovisual,
ancho de banda y recuperación de fuentes. La entrega requiere dos procesos propios de lógica,
SQL/NoSQL con uso real, conectores HTTP distintos y tres lenguajes de propósito general.

## Decisión

- Core reúne Cuentas (autenticación y perfil), Canales, Catálogo, Emisiones y Consultas. Java/Spring, PostgreSQL, un build, cadena de seguridad y gestor de transacciones. Una SPEC o asignación personal no crea un proceso.
- Registro confirma cuenta, perfil, canal y resultado idempotente en una transacción. Recuperación por UUID treinta días, sin iniciar sesión. Canales y Consultas componen datos públicos localmente; Discovery mantiene GraphQL dentro de Core.
- Cada módulo conserva repositorio de escritura y publica interfaces/vistas de lectura con columnas explícitas. FK e integridad local son parte del diseño. No HTTP, secretos de servicio ni eventos de replicación entre módulos Core.
- Chat es el segundo servicio propio: salas, cuota global por cuenta, dedupe, secuencia, historial, distribución, moderación y replay futuros. Go/MongoDB son candidatos; el ADR de Chat debe demostrar persistencia, orden y cuota consistentes, incluido fan-out entre réplicas.
- Chat solicita un contexto Core por mensaje nuevo: sesión vigente, autor público y estado/timeline. El contexto no se reutiliza para otro mensaje. Core→Chat comunica ciclo de vida mediante outbox SQL/inbox durable y HTTP idempotente; los eventos no autorizan escrituras.
- Media es una unidad aparte: RTMP/HLS, señales de fuente y procesamiento audiovisual; Core es dueño de estado/cupos/clock de negocio. Motor/adaptador se seleccionan mediante ADR multimedia.
- Web es una aplicación/un build con módulos internos. TypeScript es candidato. Proxy encamina tráfico; integración y accesibilidad son responsabilidades transversales sin runtime de negocio.
- P1 usa una réplica inicial por proceso. No necesita broker universal, gateway de reglas, coordinador de sagas, microfrontends, service mesh ni Kubernetes. La extracción futura exige evidencia y los criterios de fases_futuras.md.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Un servicio por SPEC | Distribuye invariantes de registro y consultas pequeñas; costo de coordinación, latencia y operación sin autonomía demostrada. |
| Cuentas + Emisiones + Chat + Discovery | Mantiene provisión remota del canal y consultas distribuidas; posible evolución con una necesidad medida. |
| Un backend para todo | No aísla conexiones/fallo Chat y no cumple los dos procesos propios de lógica de la entrega. |
| Core modular + Chat + Media | Elegida: integridad local para negocio y separación por carga/fallo para chat y audiovisual. |
| Autorización Chat cacheada/JWT | Introduce ventana de revocación; sesión opaca con contexto autoritativo preserva el contrato. |

## Consecuencias

Core simplifica registro, validación de metadata y consultas; comparte disponibilidad y release entre
módulos. Chat puede reiniciarse/escalarse sin cortar HLS. Core caído rechaza nuevas escrituras Chat
y nuevas autorizaciones RTMP; Media puede continuar una reproducción existente mientras conserva la
fuente, sin garantía indefinida. Una autorización válida previa a logout/ENDED puede confirmar dentro
del presupuesto acotado; no se promete atomicidad distribuida Core–Chat ni revocación retroactiva.

Chat persiste dedupe/cuota/secuencia/mensaje antes de ACK y conserva una entrega recuperable tras
crash. NoSQL tiene un uso de historial real. Más de una réplica exige propietario/orden de sala,
fan-out y cuota compartidos; no basta duplicar el contenedor. Core multi-réplica exige volumen
de imágenes compartido y fencing/recuperación probados para control de emisión.

Las tecnologías candidatas no acreditan RNF de lenguaje/NoSQL. Las interfaces REST/GraphQL/WS y
los artefactos reales deben evidenciar las restricciones del curso. La evolución de pagos,
suscripciones y permisos permanece cohesiva; notificaciones y procesos media son efectos asíncronos,
sin bloquear la autorización o LIVE con workflows globales.

## Verificación

Comprobar una transacción cuenta/perfil/canal, interfaces locales sin HTTP, seguridad común y
consultas públicas sin datos privados. Verificar contexto tras logout/fin, outbox/inbox, dedupe,
cuota y recuperación Chat, callbacks/clock Media, reinicios y aislamiento HLS ante fallo Chat.
Ejecutar el perfil de SPEC-13 antes de declarar carga/escala/recuperación cumplidas.

## Condiciones para cambiar la decisión

Extraer un módulo cuando haya evidencia sostenida de carga, alcance de fallo, equipo o releases
independientes que compensen coordinación y operación. Definir propiedad, invariantes, contrato,
consistencia, migración y recuperación antes de extraerlo. No separar por tabla, pantalla o SPEC.
