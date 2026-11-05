# SPEC-10 Contratos API, propiedad de datos y errores P1

> Perfil de la primera entrega. Para P2 rigen el [alcance vigente](../alcance_p2.md),
> las [SPEC P2](../spec-p2/README.md) y [ADR-015](../adr/ADR-015-arquitectura-p2.md).
> Este documento conserva contratos y aceptación de P1; no acredita las ampliaciones de P2.


- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Web, Core, Streaming, Chat y Media necesitan compartir datos sin depender de los modelos internos
del otro. Esta SPEC define sus contratos de red y las interfaces locales de Core.
Los módulos Core pueden conservar la integridad referencial dentro de su base.

## 2. Definición del componente

Los límites lógicos, las rutas canónicas P1, el payload mínimo de las operaciones críticas y los errores/fallos observables están especificados en el inventario `contratos_modelo_datos.md`, con schemas y ejemplos en `contracts/p1.json`. ADR-012 selecciona JSON Schema 2020-12 y SDL, derivados mediante `contracts/generate.py`. `--check` verifica ejemplos, referencias locales, privacidad, inventario, SDL Core/queries y drift; CI usa el mismo comando. La prueba Core–Streaming importa el cliente Rust del checkout actual; una referencia histórica requiere selección explícita.

## 3. Historia de usuario

Como responsable o consumidor de un módulo, quiero interfaces versionadas, esquemas y errores compatibles, para desarrollar e integrar independientemente.

## 4. Alcance

### Dentro de P1

Documentar métodos, rutas, mensajes, esquemas neutrales, errores, identificadores, versiones,
seguridad, paginación y compatibilidad. El inventario distingue APIs públicas, contratos entre
procesos e interfaces locales Core. Incluye Core–Streaming/Chat y Streaming–Chat/Media.

### Fuera de P1

Contratos de capacidades futuras que aún no se han priorizado y una plataforma universal de
gateway o broker.

### Supuestos

Discovery conserva GraphQL dentro de Core. Usar REST o WebSocket no obliga a elegir un framework
distinto por módulo. Las claves foráneas son locales a una base; las referencias entre bases
se validan mediante contrato.

## 5. Requisitos de integración

Cada dato tiene un módulo responsable de escribirlo. Dentro de Core, los demás módulos consumen
interfaces locales y DTO o modelos de lectura públicos. Cuentas autentica y Catálogo valida los IDs.
Streaming controla configuración, claves, estado, cupos, línea temporal, leases y snapshots públicos;
Chat controla mensajes, orden y cuota.

Los payloads públicos excluyen secretos y email privado. Entre procesos usamos esquemas neutrales,
sin compartir clases internas. La idempotencia se define por operación; la red puede entregar
una solicitud más de una vez.

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

- **CA-07:** el inventario cubre todas las operaciones de la matriz SPEC-09, incluidas obtención de CSRF, uploads/lectura de imágenes, bootstrap de canal, contexto/snapshot Chat, frames WS, leases y HLS. Cada fila identifica schema/ejemplo de éxito y error, auth por ruta, límites, timeout, idempotencia y consumidor; las interfaces locales Core se distinguen de los contratos de red.

- **CA-08:** las pruebas de proveedor y consumidor usan los componentes del checkout integrado identificado por commit; un harness fijado a una versión histórica se conserva como regresión, pero no acredita compatibilidad del código actual. Cada contrato pendiente de implementación permanece abierto aunque existan schema o mock.

## 7. Diseño técnico y datos

Fuente semántica única contratos_modelo_datos.md; artefactos generados según ADR de herramienta. Cambio incompatible identifica transición/migración/retiro. Core usa puertos/adaptadores locales, repositorios privados y FK; Streaming/Chat/Media consumen schemas HTTP neutros. Los criterios de aceptación requieren evidencia ejecutable.

En P1, ADR-011 integra el adaptador técnico en el proceso Streaming. Autorización/callbacks internos conservan HTTP loopback autenticado en desarrollo; el perfil persistente ADR-014 usa HTTPS con CA explícita y conserva persistencia separada. HLS público se enruta al listener 8888 del contenedor Streaming; RTMP sigue en MediaMTX. Reinicios/fallos del proceso afectan al control y al adaptador juntos.

## 8. Dependencias y contratos de integración

SPEC-01, SPEC-03…SPEC-08 aportan comportamiento; SPEC-11 secuencia; SPEC-12 proxy; SPEC-13 evidencia. Core–Chat usa contexto/snapshot compuesto con estado actual Streaming; Streaming–Chat entrega lifecycle. Core–Streaming valida owner/catálogo y recibe proyección Discovery. Streaming–Media autoriza ingesta/callbacks/control técnico. Snapshots/versiones/frescura/reconstrucción siguen el contrato canónico. Las interfaces de Cuentas/Canales/Catálogo/Consultas son locales a Core.

## 9. Decisiones y preguntas abiertas

Preservar contratos públicos donde no contradigan topología; registrar cambios de registro/lectura compuesta/contexto Chat. La aceptación requiere schemas neutrales y verificación de presupuestos entre proveedor y consumidor.

Los artefactos incluyen p1.d.ts derivado de schemas JSON y tipos GraphQL derivados del SDL; Web consume esos tipos y las queries sin DTO paralelos.

## 10. Verificación

- Validar esquemas y ejemplos en CI con contracts/generate.py --check, según ADR-012.

- Consumer-driven contract tests para operaciones críticas entre módulos.

- Pruebas de auth, errores, timeout, duplicado, compatibilidad y privacidad por proveedor.

- Matriz de trazabilidad interfaz→RF/RNF→SDD→responsable y evidencia de revisión de consumidores.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. Los principales riesgos son esquemas divergentes, datos privados en un contrato,
cambios incompatibles sin coordinación e identificadores que cada lenguaje interprete de forma distinta.

Los clientes externos y la gestión de una API productiva quedan fuera de P1.
