# ADR-005: Streaming Rust independiente y proyección Discovery en Core

- Estado: aceptada; despliegue del adaptador actualizado por [ADR-011](ADR-011-streaming-tres-contenedores-p1.md)
- Fecha: 2026-10-03
- Responsable: Streaming; contratos coordinados con Core, Chat, Discovery e Integración
- SPEC/contratos afectados: SPEC-01, SPEC-03…SPEC-07, SPEC-09…SPEC-13
- Sustituye ADR-003 en la frontera de Emisiones y sus consultas; conserva registro local, Core modular, Chat, Media y Web.

## Contexto

El control de emisiones requiere una capacidad autónoma durante las cuatro iteraciones del proyecto. La frontera tiene un dueño: configuración, secreto de ingesta, sesiones, generaciones, cupos, reloj, leases y coordinación multimedia. Discovery permanece en Core junto a los datos públicos de cuentas, canales y catálogo.

La separación se elige por autonomía de desarrollo, release y operación. No existe evidencia de que Java sea insuficiente para P1, ni un benchmark que acredite ventaja de rendimiento de Rust. El tráfico audiovisual se sirve por MediaMTX y no pasa por el API de control. Se acepta el costo adicional de contratos, persistencia, autenticación, observabilidad y recuperación entre procesos.

## Decisión

1. **Core Java/Spring:** Cuentas, Canales, Catálogo y Discovery. Registro cuenta/perfil/canal sigue siendo una transacción PostgreSQL con FK locales. Discovery conserva GraphQL y SQL de lectura en Core.
2. **Streaming Rust:** Tokio/Axum, PostgreSQL/SQLx con pools, repositorios encapsulados y contenedor propio en `services/streaming`. Posee configuración, claves, sesiones, cupos, clocks, generaciones, leases e inbox/outbox. Su base es privada; Core no consulta sus tablas.
3. **Media:** MediaMTX autogestionado, RTMP y Low-Latency HLS; adaptador técnico propio en Rust. MediaMTX se despliega separado del control de negocio. En P1 el adaptador técnico comparte el proceso/contenedor Streaming según ADR-011; conserva base, repositorio y contratos privados propios. No se implementa transcoding, ABR, VOD ni otro protocolo en P1. La configuración/digest y los fixtures de códecs se verifican antes del despliegue.
4. Streaming solicita a Core un contexto nuevo para cada comando protegido: sesión vigente, propietario de canal y validación tipada de los IDs de catálogo presentes. La operación usa una autorización acotada, no una transacción entre bases ni un permiso cacheado. Las claves son privadas de Streaming, se entregan una vez y no viajan en eventos.
5. **Proyección pública:** Streaming publica `StreamDiscoverySnapshot` completo por configuración mediante outbox transaccional. Core acepta en inbox y aplica en sus tablas de lectura; combina esas filas con Canales/Cuentas/Catálogo locales. Estado, metadata, conteo y versiones provienen de Streaming. Discovery no se convierte en autoridad ni hace HTTP por fila.
6. Se usa **HTTPS privado idempotente** para entrega de eventos P1, con ACK durable, retries, DLQ y credenciales por consumidor. Outbox entrega a Discovery y Chat de manera independiente; un consumidor caído no bloquea LIVE ni al otro. No se incorpora un broker en esta decisión.
7. Snapshots llevan versiones, fecha de observación y posición de commit. Duplicados se ignoran; versiones viejas no reemplazan nuevas. Discovery tiene reconstrucción paginada consistente y watermark, con inbox durable durante reconstrucción; Chat necesita backup/inventario para pérdida total, sin asumir que lookups de sesiones conocidas basten. Los detalles normativos están en `contratos_modelo_datos.md`.
8. La frescura de estado/metadata/conteo mantiene el máximo de cinco segundos en el perfil normal. Streaming observa y publica snapshots de configuraciones con margen de dos segundos para entregar/aplicar en los tres restantes. Estado no confirmado se marca desconocido y se excluye de resultados reproducibles; conteo observado hace más de cinco segundos es `viewerCountFresh=false`. Atrasos/fallos no se ocultan como OFFLINE.
9. Chat conserva su endpoint de contexto Core. Core valida identidad/autor localmente y consulta el estado/timeline actual a Streaming por mensaje nuevo; jamás autoriza desde Discovery. Streaming entrega directamente los eventos de ciclo de sesión a Chat. Presupuesto agregado y operaciones en vuelo se validan según contratos.
10. P1 inicia una réplica Streaming con anchors monotónicos. Si pierde reloj/owner, termina la sesión sin renovar gracia. Fencing, enrutamiento de owners y transferencia de clock deben probarse antes de habilitar varias réplicas. Core y Media pueden evolucionar independientemente.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Gestión Java en Core y adaptador Rust Media | Menor costo de coordinación, validaciones/FK locales; alternativa válida si se prioriza una entrega mínima. |
| Streaming Rust independiente y Discovery en Core | Elegida: autonomía de Streaming y consultas locales sobre una proyección pública; introduce consistencia eventual y recuperación explícita. |
| Discovery como otro servicio desde P1 | Añade despliegue y proyecciones de cuentas/canales/catálogo; no se necesita para separar Streaming. |
| Componer cada resultado GraphQL mediante HTTP a Streaming | Introduce llamadas por fila y complica filtros/ranking/paginación; se descarta. |
| Broker o event sourcing universal | No necesario para outbox/inbox y dos consumidores; se evaluaría con un problema medido. SQL sigue siendo la autoridad del estado. |

## Consecuencias

Una autorización Core no mantiene un lock sobre Streaming. Core valida en el instante del contexto y Streaming confirma dentro del presupuesto; un logout/inactivación posterior puede coincidir con un comando ya autorizado. La siguiente autorización debe rechazar. Valores inactivados después de asociarlos conservan ID/último label; no hay FK ni escritura entre bases. Registro y edición de canal permanecen locales.

Discovery puede atrasarse o requerir reconstrucción. Sus errores de frescura son observables; la lectura autoritativa del player y la autorización Chat siguen usando Streaming. Los snapshots nunca llevan credenciales, claves de emisión, tokens de lease, email ni paths de ingesta. No se replican permisos ni muestras periódicas de timeline.

La operación necesita backups separados, pools dimensionados por proceso, TLS y secretos distintos, métricas de edad/cola/DLQ y redrive. MediaMTX se dimensiona por bitrate/lectores; separar contenedores en un host no aumenta su capacidad física. El owner de cada almacén define migración y rollback. El runtime Rust usa un esquema vacío inicial; integrar esta decisión no importa ni elimina datos de Core automáticamente.

## Compatibilidad y despliegue

Los endpoints públicos se conservan. `/api/channels/{channelId}/streams` se enruta a Streaming con regla explícita; el resto de Canales permanece en Core. El despliegue integrado exige contratos neutrales y adopción de ambos extremos. Los cambios de enrutamiento se coordinan con los consumidores y conservan rollback; Streaming es el único escritor de emisiones.

## Verificación

Comprobar owner ajeno/logout, IDs de tipo incorrecto/inactivos y preservación de tombstones; RTMP/HLS real y primer frame; cupos y clocks 29/30/31 s; outbox/inbox con respuesta perdida, duplicados/conflictos/desorden, ENDED, atraso/frescura y reconstrucción concurrente. Medir latencia Core→Streaming dentro del contexto Chat, cinco emisiones/cien players y el perfil SPEC-13. Build o decisión aceptada no cierran esos criterios.

## Revisión

Reevaluar si la coordinación domina el desarrollo, la latencia incumple el presupuesto o el equipo no puede operar los procesos separados. Extraer Discovery o introducir broker solo con necesidad medida, contrato y reconstrucción definidos. Core, Streaming, Discovery y Chat revisan cambios de propiedad/protocolo antes de activarlos.
