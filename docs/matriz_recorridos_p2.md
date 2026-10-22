# Trazabilidad y recorridos de aceptación P2

Esta matriz asigna cada RF incluido y cada RNF a una definición y una comprobación. Describe
pruebas por ejecutar; no registra resultados ni sustituye los criterios particulares de la SPEC.
Los requisitos de pagos/premium RF-038…045 quedan fuera de la entrega.

## Cobertura funcional

| RF | Definición principal | Comprobación |
| --- | --- | --- |
| RF-001…007 | [SPEC-01](spec-p2/spec_01_auth.md) | CA-01…08: registro/perfil base, sesión web/móvil, recuperación y permisos |
| RF-008…012 | [SPEC-03](spec-p2/spec_03_channel.md) | CA-01/07 y casos base: canal, propiedad, edición y estado |
| RF-013 | [SPEC-14](spec-p2/spec_14_vod.md) | CA-05/06: catálogo publicado por canal y privacidad de borradores |
| RF-014…016 | [SPEC-03](spec-p2/spec_03_channel.md) | CA-02…06: follow idempotente, listado, snapshot y outbox |
| RF-017…026 | [SPEC-04](spec-p2/spec_04_stream.md) | CA-07…09 y máquina base: inicio, estado, reconexión, metadata, stop y leases |
| RF-027…030 | [SPEC-04](spec-p2/spec_04_stream.md) | CA-01…03: calidades reales, un cupo y selección manual/automática |
| RF-031…037 | [SPEC-05](spec-p2/spec_05_chat.md) | CA-01…04/08: mensajes, cuota, orden, supresión y bloqueo flexible |
| RF-046…051 | [SPEC-21](spec-p2/spec_21_watch_party.md) | CA-01…07: políticas por cerrar, permisos, varias fuentes y recuperación |
| RF-052…055 | [SPEC-20](spec-p2/spec_20_notifications.md) | CA-01…09: bandeja, leído, preferencias y entrega por medio |
| RF-056…062 | [SPEC-14](spec-p2/spec_14_vod.md) | CA-01…09: grabación, publicación, reproducción, edición, borrado y duración |
| RF-063…065 | [SPEC-22](spec-p2/spec_22_subtitles.md) | CA-01…07: política/formato pendientes, pistas reales y controles |
| RF-066…067 | [SPEC-06 base](spec-p1/spec_06_tax.md) y [SPEC-04 P2](spec-p2/spec_04_stream.md) | Catálogo controlado, una categoría y hasta cinco etiquetas activas |
| RF-068…073 | [SPEC-07](spec-p2/spec_07_disc.md) | CA-01…07 y consultas base: filtros, búsqueda, ranking y frescura |
| RF-074…075 | [SPEC-14](spec-p2/spec_14_vod.md) | CA-06 y p2.vod.list: título, categoría/etiqueta, cursor y visibilidad |
| RF-076…079 | [SPEC-23](spec-p2/spec_23_admin.md) | CA-01…08: rol, listados, retirada, suspensión/reactivación y URLs directas |
| RF-080…081 | [SPEC-03](spec-p2/spec_03_channel.md) y [SPEC-14](spec-p2/spec_14_vod.md) | Preferencia default/copia por sesión; borrador, publicación manual y permisos |
| RF-082…083 | [SPEC-05](spec-p2/spec_05_chat.md) | CA-04…07: replay/mapping, borrado opcional por usuario y retención |

## Cobertura no funcional

| RNF | Definición principal | Evidencia necesaria |
| --- | --- | --- |
| RNF-001…010 | [SPEC-24](spec-p2/spec_24_deployment.md), [16](spec-p2/spec_16_gateway.md), [17](spec-p2/spec_17_broker.md), [18](spec-p2/spec_18_ssr.md), [19](spec-p2/spec_19_mobile.md) | Procesos, SSR/móvil, HTTP, cuatro datos y cinco lenguajes implementados; arranque/reinicio por unidad |
| RNF-011 | [SPEC-15](spec-p2/spec_15_int.md) y [24](spec-p2/spec_24_deployment.md) | Perfil base de carga por endpoint; medir además agregados SSR y trabajos P2, sin ocultar fallos tras p95 |
| RNF-012…014 | [SPEC-04](spec-p2/spec_04_stream.md), [07](spec-p2/spec_07_disc.md), [18](spec-p2/spec_18_ssr.md) | Tiempo a primer frame, latencia de chat y frescura desde commit hasta cliente |
| RNF-015…018 | [SPEC-24](spec-p2/spec_24_deployment.md), [04](spec-p2/spec_04_stream.md), [05](spec-p2/spec_05_chat.md) | Escala por unidad, cinco emisiones, cien players y cuota/latencia de veinte mensajes/s agregados |
| RNF-019…020 | [SPEC-15](spec-p2/spec_15_int.md) y [16](spec-p2/spec_16_gateway.md) | Chat/notificaciones caídos con reproducción disponible; error por sección |
| RNF-021 | [SPEC-04](spec-p2/spec_04_stream.md) | Pérdida/reconexión a 29/30/31 s y recuperación sin nueva gracia |
| RNF-022/024/050 | [SPEC-24](spec-p2/spec_24_deployment.md), [14](spec-p2/spec_14_vod.md), [17](spec-p2/spec_17_broker.md) | Reinicio, backup/restore, outbox/inbox, archivo y efectos parciales |
| RNF-023 | [SPEC-16](spec-p2/spec_16_gateway.md) | Timeouts, cancelación, recurso esencial y secciones secundarias |
| RNF-025…032 | [SPEC-01](spec-p2/spec_01_auth.md), [10](spec-p2/spec_10_contracts.md), [19](spec-p2/spec_19_mobile.md), [23](spec-p2/spec_23_admin.md) | TLS, hashing base, sesión, cuotas, permisos, privacidad y suspensión |
| RNF-033…037 | [SPEC-18](spec-p2/spec_18_ssr.md), [19](spec-p2/spec_19_mobile.md), [22](spec-p2/spec_22_subtitles.md) | Recorridos, estados visibles, teclado/foco/semántica y navegadores probados |
| RNF-038…042 | [SPEC-10](spec-p2/spec_10_contracts.md) y [24](spec-p2/spec_24_deployment.md) | APIs versionadas, propiedad, catálogo sin reconstruir cliente y fronteras de datos |
| RNF-043…045 | [SPEC-24](spec-p2/spec_24_deployment.md) | Correlación, resultados por servicio, métricas de cola y health/readiness |
| RNF-046…047 | [SPEC-10](spec-p2/spec_10_contracts.md) | Schemas neutrales y proveedores/consumidores reales en varios lenguajes |
| RNF-048…049 | [SPEC-24](spec-p2/spec_24_deployment.md) y decisiones de cada almacén | Datos relacionales/NoSQL con justificación, propiedad y recuperación |
| RNF-051 | [SPEC-17](spec-p2/spec_17_broker.md) y [20](spec-p2/spec_20_notifications.md) | Trabajo real fuera de solicitud, un broker y consumidores independientes |

## Recorridos entre componentes

| ID | Recorrido | Fallos y límites obligatorios | Responsable del cierre |
| --- | --- | --- | --- |
| P2-I-01 | Registro web → Core → canal/perfil → SSR | Rollback, respuesta perdida y broker detenido; registro no espera proyecciones | Cuentas / Web |
| P2-I-02 | Login móvil → Bearer → Gateway → WS Chat | Expiración, logout desde web, suspensión y Origin nativo sin relajar navegador | Cuentas / Móvil / Chat |
| P2-I-03 | Recuperación → outbox → broker → SMTP → confirmación Core | Cuenta inexistente uniforme, entrega desconocida, token vencido/usado y concurrencia | Cuentas / Notificaciones |
| P2-I-04 | Follow → Core/outbox → destinatarios/preferencias → LIVE → aviso | No-op, snapshot con cambios, reconexión sin aviso nuevo y medio desactivado | Canales / Notificaciones |
| P2-I-05 | OBS multicalidad → Media → Streaming → Gateway → player | Una/cuatro calidades, un cupo, cambio manual/automático y fuente 720 sin 1080 ficticio | Streaming / Web / Móvil |
| P2-I-06 | Streaming/outbox → broker → Core y Chat | Consumidor independiente caído, duplicado, versión vieja y reanudación tras reinicio | Mensajería / Core / Chat |
| P2-I-07 | Captura → S3 → segmento → VOD → índice final → publicación | Carga sin evento, evento sin objeto, cierre adelantado, hash distinto y todas las rendiciones | Streaming / VOD |
| P2-I-08 | Preferencia canal → copia sesión → borrador o autopublicación | Cambiar preferencia durante emisión, tercero editando y grabación incompleta | Canales / VOD |
| P2-I-09 | VOD → timeline → seek → replay Chat | Hueco de reconexión, cursor/mapping cambiado, moderación y evento atrasado | VOD / Chat / Players |
| P2-I-10 | Bloqueo/borrado → Chat → WS/history/replay | Temporal vencido tras reinicio, permanente hasta retiro y borrado de usuario opcional | Chat |
| P2-I-11 | Aviso lógico → jobs bandeja/email/push | Un medio falla, proveedor confirma sin respuesta, token inválido y cambio de cuenta | Notificaciones / Móvil |
| P2-I-12 | Cuenta suspendida → Core → Streaming/Chat/VOD → Gateway | Sesiones revocadas, consumidores atrasados, URL directa de cada asset y autoridad caída | Administración / todos los proveedores |
| P2-I-13 | DELETE VOD → retirada → limpieza S3/Chat/pistas | Trabajo repetido/fallido, índice/cierre atrasado y reactivación sin republicar | VOD / Chat / Subtítulos |
| P2-I-14 | Navegación SSR → Gateway → agregado → hidratación | Dos usuarios concurrentes, sección caída, cancelación y cinco segundos completos | Web / Gateway |
| P2-I-15 | Watch party con fuentes reales | Después de P2-01…03: roles/acceso, límites, salida y fuente terminada/suspendida | Watch parties |
| P2-I-16 | Pista → contenido → player | Después de P2-04…05: validación, idioma, seek, calidad y restricción por URL directa | Subtítulos / Players |
| P2-I-17 | Checkout limpio → despliegue → reinicio → restauración | Volúmenes, datos/objetos, broker/DLQ, proveedores externos y mínimos académicos reales | Integración |

## Estado de interfaces y cierre

Una prueba de schema verifica forma; una prueba de proveedor/consumidor verifica comportamiento.
Una SPEC aceptada no implica una prueba ejecutada. Para cerrar un recorrido se registra versión
probada, entorno, datos ficticios, resultado y evidencia, sin credenciales.

P2-I-15/16 requieren las decisiones delegadas; sus RF siguen incluidos. La selección tecnológica,
provisión push, empaquetado y clasificación académica siguen en
[decisiones pendientes](decisiones_pendientes_p2.md). No se certifican por un diagrama ni por
haber instalado un broker. El criterio de cierre es el recorrido real y sus fallos.
