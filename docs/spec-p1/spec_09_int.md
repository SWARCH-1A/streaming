# SPEC-09 Integración transversal P1

- **Módulo:** integration
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

SPEC transversal padre de SPEC-10 a SPEC-13. Define coordinación y evidencia de integración para P1, manteniendo contratos entre Core, Streaming, Chat, Media y Web y las interfaces locales de los módulos Core. Evita que cada equipo improvise rutas, datos o secuencias incompatibles.

## 2. Definición del componente

ADR-005 define Core modular, Streaming Rust, Chat y MediaMTX/adaptador Rust. Las SPEC organizan responsabilidades y evidencia. Integración define contratos, infraestructura y verificación; no es un proceso de negocio.

## 3. Historia de usuario

Como equipo que integra componentes independientes, queremos una arquitectura coherente, contratos revisables y un recorrido vertical verificable, para construir el prototipo sin acoplar ni romper los módulos de otros.

## 4. Alcance

### Dentro de P1
SPEC-10 contratos/fronteras, SPEC-11 transacción local y cruces reales Core–Streaming, Core–Chat y Streaming–Chat/Media, SPEC-12 Web/proxy, SPEC-13 entrega integrada. Trazabilidad, mapas, estado de implementación, SQL/NoSQL/lenguajes/conectores y decisiones revisables.

### Fuera de P1
Un servicio Integration, gateway de reglas, saga central, microfrontends, bus universal, HA productiva o capacidades futuras anticipadas.

### Supuestos
Stack por unidad desplegable, modularidad local y una Web; ADR de selección antes de declarar tecnologías aceptadas. Las restricciones del curso se conservan.

## 5. Requisitos de integración

Todo salto real especifica proveedor/dueño/consumidor/auth/deadline/recovery. Dentro de Core interfaces de aplicación, un gestor de transacciones, escritura por dueño y read models SQL revisados; no HTTP ni prohibición indiscriminada de FK. No tablas compartidas entre servicios. Fallo Chat no afecta Media; nueva escritura Chat depende de Core y del estado autoritativo Streaming; Discovery recibe proyección pública con recuperación/frescura. Ningún componente Integration se hace dueño de workflows de negocio.

## 6. Criterios de aceptación

- **CA-01:** existen SPEC-10…SPEC-13 como especificaciones vinculadas a SPEC-09, con responsabilidades no duplicadas y once secciones cada una.

- **CA-02:** todos los módulos P1 tienen proveedor/consumidor y contratos críticos revisados por ambos extremos antes de merge.

- **CA-03:** las cuatro especificaciones cubren desde contrato de datos hasta proxy, health, despliegue y flujo E2E.

- **CA-04:** diagramas, matriz RNF y contratos coinciden con las responsabilidades de módulo, ownership y decisiones vigentes; cada RNF tiene una SPEC primaria y evidencia de cierre.

- **CA-05:** la evidencia demuestra restricciones técnicas de la asignatura sin confundir conectores RTMP/HLS, lenguajes ni procesos.

- **CA-06:** el recorrido vertical P1 puede iniciarse y verificarse conforme al runbook de SPEC-13; fallos aislados tienen resultado esperado.

- **CA-07:** ningún ADR candidato aparece como tecnología ya aprobada sin decisión del responsable correspondiente.

- **CA-08:** cada RF P1 y cada CA de SPEC-01, SPEC-03…SPEC-08 tiene escenario de integración, proveedor/consumidor, SPEC hijo responsable y evidencia de cierre. La matriz de cobertura de esta SPEC y la matriz RNF son obligatorias para aceptar P1; una prueba local o un estado Done del módulo no sustituye su recorrido integrado.

## 7. Diseño técnico y datos

- Artefactos: vista de contexto, C&C, despliegue, matriz de contratos y errores, ownership/ERD lógico, tabla de rutas, runbook, matriz de restricciones, [matriz de trazabilidad RNF](../matriz_trazabilidad_rnf.md) y ADR index.

- Separar requisito de sistema de decisión de implementación; cada decisión tiene ADR con estado propuesta/aceptada/rechazada/sustituida.

- Los RF y RNF relacionados con cada SDD hijo se identifican con sus IDs canónicos del catálogo.

- La documentación `/docs` contiene el alcance y los contratos necesarios para implementar el proyecto.

En P1, ADR-011 integra el adaptador técnico en el proceso Streaming. Autorización/callbacks internos conservan HTTP loopback autenticado en desarrollo y HTTPS con CA explícita en el perfil persistente ADR-014, con persistencia separada. HLS público se enruta al listener 8888 del contenedor Streaming; ingest RTMP/RTMPS sigue en MediaMTX. Reinicios/fallos del proceso afectan al control y al adaptador juntos. La instalación local de equipo usa el Compose raíz de nueve servicios, con Live como nombre desplegado de Streaming y Web/proxy separados. Los fixtures de aceptación conservan ocho contenedores, diez con dos réplicas Core/Chat; el stack propio Streaming mantiene tres.

## 8. Dependencias y contratos de integración

SPEC-10 define APIs públicas/internas públicas y privadas; SPEC-11 registro local, sesión Media/Chat y proyección Streaming→Discovery; SPEC-12 tabla única de upstreams; SPEC-13 topología/evidencia. Los RF permanecen en sus SPEC funcionales y la matriz RNF conserva responsables.

### Cobertura obligatoria de los SPEC funcionales

Esta tabla define el alcance de integración, no acredita implementación. SPEC-10 materializa y valida
todos los contratos de las filas; SPEC-13 ejecuta los recorridos y consolida la evidencia. Cada
escenario identifica los CA funcionales que demuestra, incluidas sus variantes negativas y de fallo.
La [matriz de recorridos P1](../matriz_recorridos_p1.md) desarrolla cada uno de los 68 CA funcionales
con escenario, RF, proveedor/consumidor, hijo responsable y resultado requerido. Es obligatoria junto
a la matriz RNF; la evidencia por escenario/commit y los pendientes se registran en Plane.

| SPEC origen / requisitos | Flujo y consumidores que deben conectarse | Aceptación de integración |
| --- | --- | --- |
| SPEC-01 / RF-001…RF-003, RF-005…RF-007 | Web→Cuentas: registro/login/logout, sesión restaurada tras refresh, edición/perfil/avatar; principal vigente en Streaming y Chat; autor público nuevo y snapshots históricos | SPEC-11 CA-02/05/08/12/14; SPEC-12 CA-03/06/11; SPEC-13 CA-07/12/14 |
| SPEC-03 / RF-008…RF-012 | Registro local 1:1, Web→Canales: propietario/portada/consulta; Core→Streaming batch autoritativo, PLAYABLE/gracia/OFFLINE/UNKNOWN | SPEC-11 CA-08/13/14; SPEC-12 CA-09/11; SPEC-13 CA-07/12/14 |
| SPEC-04 / RF-017…RF-026 | Web→Streaming: configuración/clave/metadata/stop; RTMP→MediaMTX→adaptador→control→HLS/player; leases y proyección pública a Core; lifecycle a Chat | SPEC-11 CA-01/03/04/06/07/09/10/11; SPEC-12 CA-05/10/13; SPEC-13 CA-06/07/12/14/15 |
| SPEC-05 / RF-031…RF-035 | Web WS→Chat→Core→Streaming: contexto nuevo, sala anónima, envío protegido, ACK/historial/dedupe/cuota/fan-out, read-only y retención | SPEC-11 CA-02/04/06/11/12/16; SPEC-12 CA-04/13; SPEC-13 CA-06/07/08/11/12 |
| SPEC-06 / RF-066…RF-069 | Catálogo→Web/Streaming→Discovery: IDs activos/tipados, 0–5 tags, edición LIVE, filtros exactos AND, tombstones y valor nuevo sin rebuild | SPEC-11 CA-05/15; SPEC-12 CA-12; SPEC-13 CA-07/16 |
| SPEC-07 / RF-070…RF-073 | Web GraphQL→Discovery SQL Core: canales LIVE/OFFLINE, título parcial, ranking/cursor, filtros y frescura/reconstrucción de snapshots Streaming | SPEC-11 CA-04/10/15; SPEC-12 CA-09/12; SPEC-13 CA-06/07/12/14 |
| SPEC-08 / RNF-033…RNF-036 | Todos los recorridos Web reales: teclado/foco/semántica/contraste/targets, formularios/player/chat, errores y autoscroll; revisión manual asistiva | SPEC-12 CA-07/10…13; SPEC-13 CA-10/16; SPEC-08 CA-01…08 |

SPEC-02 fue consolidada en SPEC-01; no hay un SPEC de perfil separado en P1. Los requisitos futuros
del catálogo conservan su prioridad y no se convierten en trabajo P1 por esta matriz.

SPEC-09 conserva coordinación y revisión de cierre. El orden de implementación es SPEC-10,
SPEC-11, SPEC-12 y SPEC-13, con revisión final de SPEC-09. Los [criterios de entrega](../puertas_implementacion_p1.md) definen las verificaciones necesarias.
Plane conserva los planes y resultados de ejecución.

## 9. Decisiones y preguntas abiertas

Arquitectura en ADR-005; base Web React/TypeScript/SWC y pnpm en [ADR-007](../adr/ADR-007-web-react-typescript.md); Chat Go/Redis efímero en ADR-010 y stack Streaming de tres contenedores en ADR-011. [ADR-014](../adr/ADR-014-perfil-integrado-tls-p1.md) define el perfil persistente TLS y su [runbook](../../infra/p1/README.md), con filesystem seleccionado explícitamente o S3 privado externo. Los artefactos implementados no acreditan por sí solos aceptación completa: carga, recorridos faltantes y revisiones manuales requieren evidencia por criterio. Catálogo conserva ADR-006 en propuesta hasta decisión de su responsable. No fijar stacks diferentes para cada módulo Core ni contabilizar un candidato como evidencia.

## 10. Verificación

- Revisión cruzada de cada contrato por productor y consumidor.

- Comprobar que la jerarquía de SPEC, la matriz de trazabilidad, las carpetas propietarias y los artefactos de docs/ coinciden.

- Ejecutar secuencias, pruebas de contrato, ruta de proxy, despliegue reproducible, reinicio aislado y recorrido E2E descritos por los hijos.

- Comparar diagramas, configuración real y evidencias de entrega; registrar discrepancias como trabajo y corregir antes del cierre P1.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** L transversal. **Riesgos:** convertir integración en responsabilidad sin autoridad, contratos tardíos, grupos sin owners, diagrams desactualizados y restricciones de curso descubiertas al final. **Consecuencia:** módulos futuros consumen contratos estabilizados, pero no se impone una plataforma productiva.
