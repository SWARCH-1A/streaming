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

## 7. Diseño técnico y datos

- Artefactos: vista de contexto, C&C, despliegue, matriz de contratos y errores, ownership/ERD lógico, tabla de rutas, runbook, matriz de restricciones, [matriz de trazabilidad RNF](../matriz_trazabilidad_rnf.md) y ADR index.

- Separar requisito de sistema de decisión de implementación; cada decisión tiene ADR con estado propuesta/aceptada/rechazada/sustituida.

- Los RF y RNF relacionados con cada SDD hijo se identifican con sus IDs canónicos del catálogo.

- La documentación `/docs` contiene el alcance y los contratos necesarios para implementar el proyecto.

En P1, ADR-011 integra el adaptador técnico en el proceso Streaming. Autorización/callbacks internos conservan HTTP loopback autenticado y persistencia separada. HLS público se enruta al listener 8888 del contenedor Streaming; RTMP sigue en MediaMTX. Reinicios/fallos del proceso afectan al control y al adaptador juntos.

## 8. Dependencias y contratos de integración

SPEC-10 define APIs públicas/internas públicas y privadas; SPEC-11 registro local, sesión Media/Chat y proyección Streaming→Discovery; SPEC-12 tabla única de upstreams; SPEC-13 topología/evidencia. Los RF permanecen en sus SPEC funcionales y la matriz RNF conserva responsables.

## 9. Decisiones y preguntas abiertas

Arquitectura en ADR-005; base Web React/TypeScript/SWC y pnpm en [ADR-007](../adr/ADR-007-web-react-typescript.md). La selección de Chat, la integración y los artefactos de despliegue siguen pendientes. No fijar stacks diferentes para cada módulo Core ni contabilizar un candidato como evidencia.

## 10. Verificación

- Revisión cruzada de cada contrato por productor y consumidor.

- Comprobar que la jerarquía de SPEC, la matriz de trazabilidad, las carpetas propietarias y los artefactos de docs/ coinciden.

- Ejecutar secuencias, pruebas de contrato, ruta de proxy, despliegue reproducible, reinicio aislado y recorrido E2E descritos por los hijos.

- Comparar diagramas, configuración real y evidencias de entrega; registrar discrepancias como trabajo y corregir antes del cierre P1.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** L transversal. **Riesgos:** convertir integración en responsabilidad sin autoridad, contratos tardíos, grupos sin owners, diagrams desactualizados y restricciones de curso descubiertas al final. **Consecuencia:** módulos futuros consumen contratos estabilizados, pero no se impone una plataforma productiva.
