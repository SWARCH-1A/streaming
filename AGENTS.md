# Instrucciones para agentes de desarrollo

## Alcance y lectura

Trabaja en las responsabilidades asignadas por el usuario. Los módulos son accounts, channels,
streaming, taxonomy, discovery, chat, accessibility e integration; Core agrupa accounts, channels, taxonomy y discovery; streaming es servicio Rust independiente.
Identity y Profile son responsabilidades internas de accounts. Una tarea global de arquitectura o
consolidación explícitamente autorizada puede cambiar el mapa, contratos y módulos afectados.
Si el alcance no permite identificar una responsabilidad, pide aclaración antes de editar código.

Lee README.md, docs/README.md, el mapa de responsabilidades, la SPEC afectada y sus RF/RNF en
docs/catalogo_requisitos.md. Para interacciones, lee contratos_modelo_datos.md y
integracion_sistema_p1.md; para infraestructura/Web, SPEC-09…SPEC-13 y el documento de proxy.
docs/ es la definición vigente. Usa exclusivamente MCP de Plane y proyecto STREAMING para Plane.

## Propiedad

| Módulo | Unidad | SPEC | UI |
| --- | --- | --- | --- |
| accounts | Core: autenticación, sesión y perfil | SPEC-01 | src/modules/accounts |
| channels | Core: canal y propiedad | SPEC-03 | src/modules/channels |
| streaming | Streaming Rust: emisión, cupos, clock, leases y proyección pública; contrato Media | SPEC-04 | src/modules/streaming |
| taxonomy | Core: categorías y etiquetas | SPEC-06 | src/modules/taxonomy |
| discovery | Core: consultas y GraphQL | SPEC-07 | src/modules/discovery |
| chat | Chat: mensajes, cuota, secuencia, historial y realtime | SPEC-05 | src/modules/chat |
| accessibility | Web y criterios de todas las vistas afectadas | SPEC-08 | src/accessibility y vistas |
| integration | Shell, contracts, infra y pruebas compartidas | SPEC-09…SPEC-13 | src/shell |

Core se ubica en services/core; Streaming Rust en services/streaming; Chat en services/chat; Media en infra/media; UI bajo apps/web.
Las responsabilidades de autenticación y perfil viven en services/core/src/main/java/streaming/core/accounts.
Web organiza módulos bajo apps/web/src/modules, shell y accesibilidad bajo apps/web/src.
Discovery permanece en Core con inbox/proyección SQL pública de Streaming según ADR-005. No crear otro runtime por los módulos que permanecen en Core.
Pruebas locales junto al módulo; contratos/integración/E2E compartidos bajo tests/ según alcance.

## Límites de implementación

- Cada módulo escribe mediante su repositorio. Dentro de Core se usan interfaces locales, un gestor de transacciones y FK; lecturas compuestas mediante vistas/DTO publicados, columnas explícitas y propiedad de escritura clara.
- Ningún proceso externo accede a tablas/modelos internos de otro. Chat no lee PostgreSQL Core y Core no escribe mensajes Chat. Contratos entre lenguajes son schemas neutros, no clases de implementación.
- Registro cuenta/perfil/canal comparte transacción; no introducir saga de provisión o replicación de identidad/perfil para consultas locales.
- Integración no implementa lógica de negocio ni un orquestador central. Las responsabilidades de un caso de uso pertenecen al dueño de sus datos.
- Limita cambios al encargo autorizado; modificar consumidores o infraestructura fuera de ese alcance requiere aclarar el alcance. La frontera Streaming Rust está aceptada en ADR-005; otras extracciones requieren los criterios de fases_futuras.md.
- La semántica de contratos vive en docs/contratos_modelo_datos.md. contracts/generated contiene artefactos generados; no crear otra definición a mano.

## Requisitos y decisiones

- Conserva RF-001…RF-079 y RNF-001…RNF-050, IDs y prioridades; capacidades futuras siguen en el catálogo. No renumerar requisitos por agrupar SPEC.
- Si cambian comportamiento, contrato, propiedad, seguridad o aceptación, actualiza SPEC, contratos, mapa y ADR afectados en el mismo cambio. Mantén una definición vigente, sin anexos de corrección contradictorios.
- Tecnología candidata no equivale a decisión aceptada. Registra opciones y consecuencias en docs/adr/ según politica_ADR.md.
- Una decisión aceptada no demuestra implementación; reporta solo evidencia ejecutada. No inventar políticas de producto pendientes.
- En Plane conserva miembros, responsables y relaciones útiles. Un bug solo se registra cuando es reproducible; no transformar preguntas o hipótesis en bugs.

## Higiene

- No incluir credenciales, tokens, claves de emisión ni datos personales reales. Ejemplos usan variables y datos ficticios.
- No incluir planes locales de implementación, informes de revisión, notas de auditoría, logs ni artefactos temporales en commits. Entregar la definición, código autorizado y evidencia pertinente. Las políticas y plantillas permanecen genéricas; registrar decisiones concretas en ADR.
- Actualiza instrucciones de ejecución/configuración con el código, revisa diff y enlaces, y verifica los checks apropiados al cambio. No afirmar una prueba no ejecutada.
- Para tareas solo documentales no cambies servicios, migraciones, configuración o bases; valida consistencia, trazabilidad y enlaces.
