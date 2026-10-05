# SPEC-10 Contratos API, propiedad de datos y errores P1

- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Define contratos de red entre Web/Core/Streaming/Chat/Media y límites locales Core. No convierte cada responsabilidad funcional en un servicio ni impide integridad referencial local.

## 2. Definición del componente

Los límites lógicos, las rutas canónicas P1, el payload mínimo de las operaciones críticas y los errores/fallos observables están especificados en el inventario autocontenido `contratos_modelo_datos.md`. OpenAPI/JSON Schema/AsyncAPI y las herramientas de validación se seleccionan mediante ADR, sin cambiar el contrato semántico.

## 3. Historia de usuario

Como responsable o consumidor de un módulo, quiero interfaces versionadas, esquemas y errores compatibles, para desarrollar e integrar independientemente.

## 4. Alcance

### Dentro de P1
Inventario de métodos/path/mensajes, schemas neutros, errores, IDs/versiones, seguridad, paginación y compatibilidad. Distinguir API externa, contrato entre procesos e interfaz de aplicación local. Publicar las interfaces locales Core y los contratos Core–Streaming/Chat y Streaming–Chat/Media.

### Fuera de P1
Contratos de capacidades futuras aún no priorizadas o una plataforma universal de gateway/broker.

### Supuestos
GraphQL Discovery público se conserva dentro de Core; REST/WS no requieren frameworks distintos por módulo. FK locales son válidas, cross-database no.

## 5. Requisitos de integración

Un dueño por escritura; interfaces locales/Core y DTO/read models públicos. Cuentas autentica, Catálogo valida IDs, Streaming Rust controla configuración/claves/estado/cupos/timeline/leases y snapshots públicos, Chat controla mensajes/orden/cuota. Todos los payload públicos excluyen secretos/email; no compartir clases internas entre procesos. Idempotencia se define por operación, no se promete exactamente una vez en red.

## 6. Criterios de aceptación

- **CA-01:** cada SDD de dominio tiene tabla de interfaz con proveedor/consumidor, propiedad, auth y fallo.

- **CA-02:** existe schema y ejemplos para cada API/evento consumido por otro dominio; el consumidor valida una respuesta y error sin importar la implementación del proveedor.

- **CA-03:** errores estructurados no filtran secretos/stack/SQL e incluyen correlación suficiente.

La autorización privada verifica consumidor y permiso de cada ruta. El token Core–Streaming
principal permite owner-context y catalog-values; el token adicional limitado al catálogo debe
recibir 401 en owner-context aun con sesión válida. Ambos reciben 404 en la entrada pública.

- **CA-04:** una solicitud repetida no duplica provisión de canal, sesión, mensaje u operación que el contrato marque idempotente.

- **CA-05:** una modificación incompatible identifica consumidores, transición, coexistencia/versiones y condición de retiro.

- **CA-06:** respuestas de canal, discovery y chat no exponen atributos privados; verificación con payload y revisión de schema.

## 7. Diseño técnico y datos

Fuente semántica única contratos_modelo_datos.md; artefactos generados según ADR de herramienta. Cambio incompatible identifica transición/migración/retiro. Core usa puertos/adaptadores locales, repositorios privados y FK; Streaming/Chat/Media consumen schemas HTTP neutros. Los criterios de aceptación requieren evidencia ejecutable.

## 8. Dependencias y contratos de integración

SPEC-01, SPEC-03…SPEC-08 aportan comportamiento; SPEC-11 secuencia; SPEC-12 proxy; SPEC-13 evidencia. Core–Chat usa contexto/snapshot compuesto con estado actual Streaming; Streaming–Chat entrega lifecycle. Core–Streaming valida owner/catálogo y recibe proyección Discovery. Streaming–Media autoriza ingesta/callbacks/control técnico. Snapshots/versiones/frescura/reconstrucción siguen el contrato canónico. Las interfaces de Cuentas/Canales/Catálogo/Consultas son locales a Core.

## 9. Decisiones y preguntas abiertas

Preservar contratos públicos donde no contradigan topología; registrar cambios de registro/lectura compuesta/contexto Chat. La aceptación requiere schemas neutrales y verificación de presupuestos entre proveedor y consumidor.

## 10. Verificación

- Validación schema y ejemplos por CI una vez elegida la herramienta.

- Consumer-driven contract tests para operaciones críticas entre módulos.

- Pruebas de auth, errores, timeout, duplicado, compatibilidad y privacidad por proveedor.

- Matriz de trazabilidad interfaz→RF/RNF→SDD→responsable y evidencia de revisión de consumidores.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. **Riesgos:** esquemas divergentes, contrato que revela datos privados, cambio breaking sin coordinación e identificadores reinterpretados por lenguaje. **Consecuencia:** clientes externos públicos y gobierno de API productiva quedan fuera de P1.
