# SPEC-10 Contratos API, propiedad de datos y errores P1

- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Especifica cómo intercambian datos los módulos P1 sin compartir tablas, clases internas o decisiones tecnológicas. Es hijo de SPEC-09 y fija el acuerdo proveedor/consumidor para APIs y esquemas.

## 2. Estado del sistema y brecha

Los límites lógicos, las rutas canónicas P1, el payload mínimo de las operaciones críticas y los errores/fallos observables están especificados en el inventario autocontenido `contratos_modelo_datos.md`. OpenAPI/JSON Schema/AsyncAPI y las herramientas de validación se seleccionan mediante ADR, sin cambiar el contrato semántico.

## 3. Historia de usuario

Como responsable o consumidor de un módulo, quiero interfaces versionadas, esquemas y errores compatibles, para desarrollar e integrar independientemente.

## 4. Alcance

### Dentro de P1

- Contrato de lectura/escritura entre Identity, Profile, Channels, Streaming, Chat, Taxonomy y Discovery; registrar proveedor, consumidor y dato autoritativo.

- Definir formato HTTP/JSON, IDs opacos, fecha UTC, request/correlation ID, autenticación/autorización y matriz de errores por operación.

- Definir versionado compatible, paginación/búsqueda, límites y comportamiento ante timeout, duplicado, 401, 403, 404, 409, 422, 429 y 5xx según aplique.

- En eventos, fijar `aggregateId` namespaced por flujo: `identity:{userId}`, `profile:{userId}`, `channel:{channelId}`, `stream:{streamId}`, `session:{sessionId}`, `viewer-count:{sessionId}` y `chat-session:{sessionId}`. `sequence` crece solo dentro del agregado; las versiones de dominio correspondientes la definen según `contratos_modelo_datos.md`, y ningún consumidor compara valores entre aggregateIds distintos.

- Incluir ejemplos de request/response y payload/event schemas sin compartir entidades de una base privada.

### Fuera de P1

- Imponer una misma herramienta, framework, librería de cliente, broker o API gateway a todos los dominios; la interfaz de Discovery sí queda definida como GraphQL en su contrato P1.

- Contratos de pagos, suscripciones, VOD, administración, moderación avanzada y subtítulos.

### Supuestos acordados

- La persona dueña de módulo elige herramienta y registra ADR compatible con este límite lógico.

- Un ID de otro módulo es una referencia de contrato, no una foreign key cross-database ni autorización para consultar su tabla.

- La interfaz pública de Discovery usa GraphQL sobre HTTP/JSON; esto no impone GraphQL a los otros dominios ni selecciona su framework o lenguaje.

## 5. Requisitos de integración

- Cada operación publicada especifica método/ruta o mensaje, actor, auth, petición/respuesta, errores, idempotencia, límite, latencia objetivo y dueño del dato.

- Identity emite principal/userId opaco; Profile no recibe credenciales; Channels separa perfil público, descripción/banner y estado de Streaming; Taxonomy devuelve IDs controlados.

- Streaming es autoridad de sesión, metadata LIVE y viewerCount; Chat de eventos de mensaje; Discovery de su proyección reconstruible.

- Respuestas públicas excluyen email, contraseña/hash, sesión/token privado y datos internos de autorización.

## 6. Criterios de aceptación

- **CA-01:** cada SDD de dominio tiene tabla de interfaz con proveedor/consumidor, propiedad, auth y fallo.

- **CA-02:** existe schema y ejemplos para cada API/evento consumido por otro dominio; el consumidor valida una respuesta y error sin importar la implementación del proveedor.

- **CA-03:** errores estructurados no filtran secretos/stack/SQL e incluyen correlación suficiente.

- **CA-04:** una solicitud repetida no duplica provisión de canal, sesión, mensaje u operación que el contrato marque idempotente.

- **CA-05:** una modificación incompatible identifica consumidores, transición, coexistencia/versiones y condición de retiro.

- **CA-06:** respuestas de canal, discovery y chat no exponen atributos privados; verificación con payload y revisión de schema.

## 7. Diseño técnico y datos

- Contrato neutral recomienda OpenAPI para HTTP y JSON Schema/AsyncAPI para eventos como candidatos; el dueño selecciona e incluye ADR.

- Envelope de error propuesto: code estable, mensaje seguro, fieldErrors opcionales y requestId; status HTTP diferenciado por operación.

- IDs opacos, UTC, UTF-8 y tamaños máximos; canonicalización de email/handle, búsqueda Unicode, autenticación y errores están definidos en el contrato transversal.

- Usar puertos/adaptadores o cliente generado con contrato; no compartir repositorios/modelos ORM entre dominios.

- Credenciales viajan por canal seguro; no se registran en logs; contratos no imprimen token ni stream key.

## 8. Dependencias y contratos de integración

- Depende de SPEC-01 a SPEC-08; cada SPEC sigue siendo dueño de su funcionalidad de dominio.

- Identity, Profile, Channels, Streaming, Taxonomy y Chat usan las interfaces HTTP/JSON o WebSocket de cada contrato. Discovery usa GraphQL sobre HTTP/JSON según la decisión existente de su módulo; su endpoint y schema están definidos en el contrato transversal.

- La fuente canónica de ruta, schemas de ejemplo, propiedad, auth, errores, idempotencia, orden y frescura es `contratos_modelo_datos.md`. SPEC-11 gobierna secuencia/eventos; SPEC-12 gobierna shell/proxy; SPEC-13 gobierna despliegue y verificación integrada.

- Una dependencia lenta falla con timeout acotado y error traducido; consultas/proyecciones declaran frescura. No bloquear reproducción por chat o discovery.

## 9. Decisiones y preguntas abiertas

**Acordado:** contratos independientes de lenguaje/almacenamiento; rutas P1 canónicas; ownership por módulo; compatibilidad; WebSocket para Chat; schemas neutrales y errores estructurados. **ADR técnico:** la herramienta para materializar schemas, timeout numérico por operación y delivery de eventos, siempre respetando los límites semánticos de los contratos documentados y revisados por ambos extremos.

## 10. Verificación

- Validación schema y ejemplos por CI una vez elegida la herramienta.

- Consumer-driven contract tests para operaciones críticas entre módulos.

- Pruebas de auth, errores, timeout, duplicado, compatibilidad y privacidad por proveedor.

- Matriz de trazabilidad interfaz→RF/RNF→SDD→responsable y evidencia de revisión de consumidores.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. **Riesgos:** esquemas divergentes, contrato que revela datos privados, cambio breaking sin coordinación e identificadores reinterpretados por lenguaje. **Consecuencia:** clientes externos públicos y gobierno de API productiva quedan fuera de P1.
