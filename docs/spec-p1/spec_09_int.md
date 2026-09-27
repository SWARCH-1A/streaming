# SPEC-09 Integración transversal P1

- **Módulo:** integration
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

SPEC transversal padre de SPEC-10 a SPEC-13. Define coordinación y evidencia de integración para P1, manteniendo contratos entre Identity, Profile, Channels, Streaming, Chat, Taxonomy, Discovery y la web. Evita que cada equipo improvise rutas, datos o secuencias incompatibles.

## 2. Estado del sistema y brecha

Los dominios P1 y sus responsabilidades están definidos. Esta SPEC coordina los contratos, límites de integración, configuración compartida y evidencia del sistema integrado. La frontera del código por carpeta no determina por sí misma qué procesos se despliegan.

## 3. Historia de usuario

Como equipo que integra componentes independientes, queremos una arquitectura coherente, contratos revisables y un recorrido vertical verificable, para construir el prototipo sin acoplar ni romper los módulos de otros.

## 4. Alcance

### Dentro de P1

- Coordinar cuatro especificaciones hijas: **SPEC-10** contratos API/ownership/errores; **SPEC-11** flujos y eventos; **SPEC-12** shell web y reverse proxy; **SPEC-13** despliegue integrado y E2E.

- Publicar matriz proveedor/consumidor, límites C&C, conectores y ownership; hacer visibles versiones, fallos y criterios de integración.

- Conservar las restricciones de la entrega: frontend web, dos procesos lógicos como mínimo, SQL+NoSQL justificadas, dos tipos HTTP, tres lenguajes generales y contenedores/reinicio independiente/despliegue reproducible.

- Establecer aceptación de integración entre dominios, trazabilidad RNF con una SPEC primaria y SPEC contribuyentes, evidencia consolidada y documentación autocontenida en el repositorio.

### Fuera de P1

- Reemplazar decisiones de producto o escoger lenguaje/framework/DB de cada dominio.

- Alta disponibilidad productiva, CDN global, microfrontend obligatorio o plataforma de CI/CD completa.

### Supuestos acordados

- El dueño de cada módulo decide y justifica tecnología mediante ADR; el módulo Integration coordina contratos sin implementar dominio ajeno.

- El monorepo modular está establecido para este proyecto; una separación futura en varios repositorios requiere decisión explícita, contratos versionados y despliegue reproducible.

- Esta especificación forma parte de la documentación normativa versionada junto con el software.

## 5. Requisitos de integración

- Todo límite identifica propietario, proveedor, consumidor, autoridad de dato, protocolo, auth, versión, error, timeout y resiliencia.

- La interacción de cuenta/canal inicial, sesión y media, sala Chat, taxonomía, proyección de Discovery, perfil público, conteo de espectadores, shell, proxy y despliegue aparece en SDD hijos.

- Ningún módulo lee/escribe tablas de otro. Fallo de Chat/Discovery no interrumpe reproducción disponible. Eventos y reproyecciones son idempotentes y observables.

- SPEC-10…SPEC-13 mantienen responsabilidades distintas bajo el ámbito de integración transversal; cada una es verificable de forma independiente.

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

## 8. Dependencias y contratos de integración

- **SPEC-10:** APIs, auth, datos, errores y evolución de contratos.

- **SPEC-11:** secuencias de Identity/Channel, media/Session/Chat/Discovery, metadata y viewer count.

- **SPEC-12:** contrato de shell, rutas y reglas de reverse proxy para HTTP, WebSocket, HLS y listener RTMP.

- **SPEC-13:** procesos/containers, health, configuración, observabilidad, reinicio y E2E/carga.

- Estos SDD consumen SDDs funcionales por dominio; no los reemplazan ni cambian prioridades acordadas.

## 9. Decisiones y preguntas abiertas

**Acordado:** cuatro especificaciones subordinadas SPEC-10…SPEC-13, P1 integrado, autonomía tecnológica con ADR, monorepo modular, datos propiedad por dominio y requisitos de despliegue de la asignatura. **No bloqueante:** los responsables concretan herramienta/tecnología, paths/puertos, timeouts, herramienta de despliegue y evidencia de demo en sus ADR, con revisión de consumidores.

## 10. Verificación

- Revisión cruzada de cada contrato por productor y consumidor.

- Comprobar que la jerarquía de SPEC, la matriz de trazabilidad, las carpetas propietarias y los artefactos de docs/ coinciden.

- Ejecutar secuencias, pruebas de contrato, ruta de proxy, despliegue reproducible, reinicio aislado y recorrido E2E descritos por los hijos.

- Comparar diagramas, configuración real y evidencias de entrega; registrar discrepancias como trabajo y corregir antes del cierre P1.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** L transversal. **Riesgos:** convertir integración en responsabilidad sin autoridad, contratos tardíos, grupos sin owners, diagrams desactualizados y restricciones de curso descubiertas al final. **Consecuencia:** módulos futuros consumen contratos estabilizados, pero no se impone una plataforma productiva.
