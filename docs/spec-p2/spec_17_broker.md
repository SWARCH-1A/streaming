# SPEC-17 — Broker y entrega durable de eventos y trabajos

**Prioridad:** P2. **Responsable:** Mensajería / integración. **Estado:** diseño para desarrollo.
**RF:** RF-011, 020…022, 052, 056, 061, 079, 082. **RNF:** RNF-001, 006, 009…010, 014, 020…024, 025, 038, 041…047, 050…051.

## 1. Contexto y problema

Streaming debe informar independientemente a Core y Chat, VOD debe procesar segmentos y
Notificaciones debe distribuir entregas. Una entrega perdida o repetida no puede dejar datos
incoherentes ni perder trabajos aceptados.

## 2. Definición del componente

Un único broker interno compartido y adaptadores de publicación/consumo en los servicios.
No se implementa un motor de mensajería desde cero. La selección del producto y protocolo
corresponde al responsable; esta SPEC define la semántica exigida a cualquier selección.

## 3. Historia de usuario

Como responsable de un servicio, quiero recibir mis eventos aunque otro consumidor esté caído,
poder reintentarlos y conocer los trabajos que requieren intervención.

## 4. Alcance

Aprovisionamiento reproducible, autenticación/ACL, nombres lógicos, suscripciones durables,
publicación confirmada, outbox/inbox, dead-letter/redrive, observabilidad y recuperación. HTTP
continúa para consultas y autorización inmediata. El broker no transporta bytes de video.

## 5. Requisitos funcionales

- Cada suscripción de dominio recibe su copia; réplicas del mismo consumidor comparten trabajo.
- Entrega al menos una vez: eventId estable y dedupe por consumidor; no se promete exactly-once.
- Versiones de agregado impiden regresiones por desorden; una secuencia incompleta se reconcilia.
- Los trabajos de bandeja, correo y push se enrutan según medio, con intentos independientes.
- Cada productor puede recuperar publicaciones confirmadas localmente tras reinicio.

## 6. Criterios de aceptación

- [ ] CA-01: Core y Chat reciben el mismo ciclo de sesión; detener Chat no bloquea a Core ni pierde su copia pendiente.
- [ ] CA-02: matar el publicador entre commit local/publicación/confirmación repite el mismo eventId sin duplicar efectos.
- [ ] CA-03: matar consumidor antes/después del commit y antes del ACK recupera el efecto una sola vez lógicamente.
- [ ] CA-04: duplicados y versiones viejas se ignoran; mismo ID con contenido distinto se registra como conflicto y no se aplica.
- [ ] CA-05: payload inválido no entra en un bucle infinito: se conserva en DLQ con causa, origen e identidad y puede redirigirse con autorización.
- [ ] CA-06: reiniciar broker y servicios con volúmenes conservados no pierde eventos confirmados ni suscripciones.
- [ ] CA-07: una credencial de VOD no publica eventos de cuentas ni consume trabajos de correo; el broker no tiene listener público.
- [ ] CA-08: correo confirmado no se repite por un fallo posterior de push. La cola de segmentos detenida no bloquea ciclo de sala.
- [ ] CA-09: métricas muestran backlog, edad, reintentos y DLQ; la ruta nominal cumple cinco segundos hasta la interfaz.

## 7. Diseño técnico y datos

[Mensajería P2](../contratos_p2_eventos.md) y `contracts/p2.json.events` fijan nueve tipos de
mensaje, productores, payloads y suscripciones lógicas. El responsable mapea esos nombres al
producto elegido sin cambiar qué consumidores reciben cada mensaje.

El outbox se escribe con el cambio de dominio. Solo se marca publicado tras confirmación durable
del broker. El inbox tiene unicidad `(consumer,eventId)`, fingerprint y efecto/estado de trabajo
persistidos atómicamente. Un ACK de transporte no acredita entrega SMTP ni publicación VOD.

Los workers no mantienen una transacción SQL abierta mientras esperan un proveedor externo.
Persisten un intento y resultado; si el resultado externo es desconocido, lo registran como tal.
Ordenar por partición ayuda, pero no reemplaza chequeo de versión ni detección de huecos.

El despliegue documenta capacidad, límite de mensajes, prefetch/concurrencia, tiempo de reentrega,
backoff, retención y ventana de dedupe. La retención cubre reentrega/redrive y reconstrucción; no
se acepta un TTL que elimine una responsabilidad durable pendiente sin recuperación.

## 8. Dependencias y contratos de integración

| Publicador / consumidor | Contrato |
| --- | --- |
| Streaming → Core/Chat | Ciclo por suscripción propia; snapshots públicos para Discovery |
| Streaming/Media → VOD | Segmentos confirmados y cierre de grabación con índice |
| Core → Notificaciones | Seguimiento, estado de cuenta y trabajos de recuperación |
| Notificaciones → workers | Trabajo filtrado por medio y target/deliveryId |
| VOD → Chat/Core | Disponibilidad y borrado con versión |

Cada dueño implementa su outbox/inbox. El responsable del broker entrega topología y librería o
adaptador neutral, sin importar clases de dominio de otro lenguaje.

## 9. Decisiones y preguntas abiertas

P2-07: producto, protocolo, SDK por lenguaje, topología física, tamaños, reintentos, DLQ y redrive.
La entrega durable, suscripciones independientes y un único broker son obligatorios. El ADR
incluye matriz de compatibilidad Java/Rust/Go y runtimes nuevos, más evidencia de recuperación.

## 10. Verificación

Usar mensajes reales de cada familia, reinicios en las ventanas de confirmación, duplicados,
desorden, payload inválido y consumidor detenido. Comprobar ACL y recuperación de volúmenes.
La prueba de una cola con texto arbitrario no acredita contratos de los servicios ni asincronía
funcional; SPEC-15 exige grabación o entrega real procesada por un worker.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. Introduce dependencia operativa y coordinación por contratos. La instancia única
no garantiza alta disponibilidad; el runbook debe describir recuperación y efecto de su caída.
No se bloquea un commit de dominio esperando a todos los consumidores.
