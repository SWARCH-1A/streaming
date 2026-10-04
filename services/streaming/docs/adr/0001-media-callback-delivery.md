# ADR-001: entrega durable de callbacks Media

- Estado: aceptada para P1.
- Fecha: 2026-10-03.
- Responsable: Streaming / Media.
- Alcance: adaptador técnico Media de SPEC-04.
- SDD/contratos afectados: SPEC-04, SPEC-10, SPEC-11 y SPEC-13; entrega de callbacks Media.

## Contexto

La aceptación de un callback puede perderse por un fallo de transporte. La ingesta y la recuperación necesitan conservar su identidad, generaciones y payload durante reintentos, reinicios y resolución manual. La retención no tiene TTL; requiere un límite operativo de admisión.

## Decisión

El adaptador guarda observación y evento en una transacción de su PostgreSQL privado. Entrega por HTTP privado autenticado con timeout de 2 s, backoff 100/250/500/1000/2000 ms y después 2 s. La ventana dura 15 minutos desde el primer intento. Un claim SQL cercado impide que un worker vencido confirme una entrega; el orden se conserva por publisher.

Cualquier 2xx confirma aceptación durable; 410 confirma obsolescencia. Un 4xx permanente o una ventana agotada conserva el evento en DLQ sin retry automático. La alerta a los 30 s se deduplica por sesión/sourceGeneration. Redrive conserva eventId/payload y abre una ventana nueva. Redrive y cierre registran operador, motivo y momento; cerrar no fabrica un ACK.

P1 fija un umbral de **10000 callbacks abiertos en DLQ**, configurable con `MEDIA_MAX_OPEN_DEAD_LETTERS`. Al alcanzarlo, readiness falla y se rechaza nueva ingesta. Los eventos pendientes y observaciones ya aceptadas siguen conservándose y entregándose; el umbral no permite eliminar datos ni impedir registrar un fallo de una fuente existente. La retención de eventos cerrados y abiertos no expira automáticamente.

Se publican métricas de profundidad de cola, edad, intentos y DLQ abiertos. La operación usa `media-adapter dead-letter list|redrive|close`; el acceso a esa CLI y a PostgreSQL pertenece al entorno privado de operación.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Entrega directa sin persistencia | Una caída o respuesta perdida impide recuperar la observación confirmada. |
| Outbox PostgreSQL privada | Elegida: observación y evento comparten transacción, con recuperación y dedupe sin otro proceso de infraestructura. |
| Broker externo | Añade operación y coordinación de la escritura SQL con la publicación; los reintentos y dos extremos de P1 se cubren mediante outbox. |

## Consecuencias

No se requiere broker ni servicio externo. Las bases de Streaming y Media permanecen privadas y separadas. Un atraso del receptor no pierde una observación ya confirmada en SQL. La capacidad de disco debe dimensionarse y vigilarse antes del despliegue; elevar el umbral requiere comprobar almacenamiento y capacidad de resolución operativa.

## Verificación

Las pruebas del adaptador verifican dedupe, orden por publisher, claims vencidos, transición a DLQ y redrive con identidad/payload conservados. La aceptación integrada verifica respuesta perdida, calendario de reintentos, alerta a los 30 s, agotamiento a los 15 min y recuperación tras reinicio según SPEC-13.

## Revisión

Streaming y Media revisan esta decisión si el atraso sostenido, la capacidad de disco o el volumen de DLQ impiden operar el servicio. Un cambio de transporte o retención requiere conservar identidad, dedupe y resolución auditable, y coordinar el contrato con el receptor.
