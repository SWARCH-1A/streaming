# SPEC-10 — Contratos compartidos P2

**Prioridad:** P2. **Responsable:** Integración y dueños de interfaces. **Estado:** diseño para desarrollo.
**RF:** Todos salvo RF-038…045. **RNF:** RNF-006, 023…032, 038, 041…047, 050…051.

## 1. Contexto y problema

El desarrollo separado requiere firmas, datos, permisos y recuperación verificables entre
lenguajes. Un listado de responsabilidades no basta para integrar servicios.

## 2. Definición del componente

Fuente neutral de schemas y registro de operaciones/eventos. `contracts/p1.json` conserva la
base; `contracts/p2.json` la amplía mediante schemas locales y un registro explícito de rutas,
productores, consumidores, timeouts, visibilidad e idempotencia.

La fuente actual incorpora el registro de nueve eventos y los dos schemas de ciclo/Discovery.
Los payloads restantes y el inventario ejecutable HTTP/WS/media P2 siguen pendientes; esa
cobertura parcial no acredita los criterios completos de esta SPEC.

## 3. Historia de usuario

Como implementador, quiero validar mi respuesta o evento contra el contrato y conocer quién
lo produce/consume, qué errores admite y cómo reintentarlo.

## 4. Alcance

Schemas JSON, ejemplos válidos/negativos, inventario HTTP/WS/eventos, versiones y reglas
semánticas; referencias entre SPEC y contratos. Las APIs de watch parties/subtítulos quedan
bloqueadas hasta cerrar sus políticas, sin rutas ficticias consideradas listas.

## 5. Requisitos funcionales

- Cada operación identifica proveedor, dueño, consumidor, auth, entrada/salida, fallo y SPEC.
- Cada evento identifica tipo/version, payload, productor y suscripciones independientes.
- Los ejemplos son ficticios y las respuestas públicas excluyen secretos.
- P2 retira entrega HTTPS de lifecycle/snapshots, conservando consultas HTTP de contexto/rebuild.
- La firma de un contrato no selecciona la tecnología de su proveedor ni acredita su implementación.

## 6. Criterios de aceptación

- [ ] CA-01: `contracts/check_p2.py` valida schemas, ejemplos, referencias, trazas y casos negativos sin acceder a red.
- [ ] CA-02: cada operación nueva tiene permiso, error, idempotencia y esquema; cada evento tiene suscripciones y productor.
- [ ] CA-03: consumidores tipados/generados se derivan de las fuentes y su generación se incorpora al elegir toolchain; no se mantiene otra definición a mano.
- [ ] CA-04: proveedor y consumidor prueban solicitud/respuesta o evento real, incluyendo error y repetición.
- [ ] CA-05: un cambio incompatible se versiona y migra con consumidores; no se borra el schema anterior antes de retirarlos.
- [ ] CA-06: interfaces delegadas se identifican con `review-required`/`pendingInterfaces` y bloqueo correspondiente; un ejemplo parcial no las acredita.

## 7. Diseño técnico y datos

El bundle P2 hereda schemas P1; referencias `#/$defs/...` se resuelven sobre la unión, sin copiar
schemas base a mano. Los IDs de operaciones son únicos. Las dos entregas de eventos HTTP P1
retiradas figuran en `retiredP1Operations`; el resto mantiene su semántica base.

El formato es JSON Schema 2020-12. Los timestamps son UTC y los enteros interoperables no exceden
9007199254740991. Un tipo estructural no expresa todas las reglas: igualdad entre IDs path/body,
versiones, ámbito, expiración, límites entre campos, acceso y atomicidad se prueban semánticamente.

Las peticiones mutadoras usan principal, CSRF cuando corresponda e idempotencia. Los POST de
consulta privada no crean recursos ni requieren tratarse como comando. Los payloads públicos,
privados y portadores de credencial tienen políticas distintas de serialización/logging.

## 8. Dependencias y contratos de integración

[Inventario HTTP](../contratos_p2_http.md), [mensajería](../contratos_p2_eventos.md),
[medio/S3/timeline](../contratos_p2_media.md) y [README de contratos](../../contracts/README.md).
Cada SPEC enlaza IDs de operaciones y eventos en lugar de redefinir sus schemas.

## 9. Decisiones y preguntas abiertas

P2-01…05 bloquean las políticas delegadas; P2-06…17 cierran parámetros/tecnología y contratos
marcados en revisión. El responsable que cierre una pregunta actualiza SPEC y fuente neutral,
publica su ADR y valida los consumidores afectados en el mismo cambio.

## 10. Verificación

Ejecutar checker P2 y checker P1; probar ejemplos inválidos de bloqueo, secciones, segmentos,
eventos y replay. La aceptación requiere además una matriz proveedor-consumidor con casos
reales de SPEC-15; el checker de forma no verifica un permiso ni un ACK durable.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo M transversal. Congelar interfaces sin resolver políticas produce incompatibilidades;
marcarlas pendientes evita atribuirles una semántica inventada y deja visible qué falta cerrar.
