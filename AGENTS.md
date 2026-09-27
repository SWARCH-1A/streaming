# Instrucciones para agentes de desarrollo

Estas reglas aplican a cualquier agente que trabaje en este repositorio. Su objetivo es mantener los
módulos aislados y las implementaciones fieles a los requisitos acordados.

## 1. Asignación obligatoria de módulo

Antes de editar código, el usuario debe indicar el módulo en el que se trabajará. Los nombres válidos
son `identity`, `profile`, `channels`, `streaming`, `chat`, `taxonomy`, `discovery`, `accessibility` e
`integration`.

- Si el encargo no identifica un módulo, pregunta cuál corresponde y espera la respuesta antes de
  cambiar archivos.
- No infieras el módulo a partir del archivo abierto, una conversación previa o una dependencia que
  parezca relacionada.
- Trata un encargo de integración compartida como `integration`. No lo conviertas por tu cuenta en
  cambios a varios módulos.
- Si el usuario nombra más de un módulo, limita el trabajo a los que nombró expresamente. No amplíes
  el alcance a módulos consumidores o proveedores sin indicación explícita.

## 2. Lectura obligatoria antes de trabajar

1. Lee este archivo y el [README raíz](README.md).
2. Lee [docs/README.md](docs/README.md) para entender la autoridad y el orden de lectura documental.
3. Abre el README de la ruta asignada, la SPEC indicada en la tabla y sus RF/RNF en
   [docs/catalogo_requisitos_v2.md](docs/catalogo_requisitos_v2.md).
4. Antes de cambiar una interacción entre dominios, lee
   [docs/contratos_modelo_datos.md](docs/contratos_modelo_datos.md),
   [docs/integracion_sistema_p1.md](docs/integracion_sistema_p1.md) y las SPEC afectadas.
5. Para un cambio de integración, lee SPEC-09 a SPEC-13, la vista C&C y los documentos de proxy,
   despliegue y trazabilidad enlazados desde docs/README.md.

Los documentos en `docs/` son la fuente canónica del proyecto. No bases decisiones en documentos
preliminares omitidos, contenido externo, comentarios aislados de Plane ni suposiciones. Si el usuario
pide operar sobre Plane, utiliza exclusivamente el MCP de Plane y el proyecto STREAMING; no uses el
navegador para editar Plane.

## 3. Mapa de propiedad y rutas

| Módulo asignado | Backend | UI del módulo | Especificación |
| --- | --- | --- | --- |
| `identity` | `services/identity/` | `apps/web/modules/identity/` | `docs/spec-p1/spec_01_auth.md` |
| `profile` | `services/profile/` | `apps/web/modules/profile/` | `docs/spec-p1/spec_02_profile.md` |
| `channels` | `services/channels/` | `apps/web/modules/channels/` | `docs/spec-p1/spec_03_channel.md` |
| `streaming` | `services/streaming/` | `apps/web/modules/streaming/` | `docs/spec-p1/spec_04_stream.md` |
| `chat` | `services/chat/` | `apps/web/modules/chat/` | `docs/spec-p1/spec_05_chat.md` |
| `taxonomy` | `services/taxonomy/` | `apps/web/modules/taxonomy/` | `docs/spec-p1/spec_06_tax.md` |
| `discovery` | `services/discovery/` | `apps/web/modules/discovery/` | `docs/spec-p1/spec_07_disc.md` |
| `accessibility` | Sin backend propio | `apps/web/accessibility/` | `docs/spec-p1/spec_08_a11y.md` |
| `integration` | Sin servicio de dominio | `apps/web/shell/`, `contracts/`, `infra/` y pruebas compartidas | `docs/spec-p1/spec_09_int.md` a `spec_13_int.md` |

Las pruebas propias del módulo se guardan junto al módulo. Las pruebas que cruzan dominios van en
`tests/contracts/`, `tests/integration/` o `tests/e2e/`, dentro de una subcarpeta que identifique el
alcance. Accesibilidad es transversal: una tarea general de accesibilidad solo puede cambiar
`apps/web/accessibility/`; para modificar pantallas concretas el usuario debe nombrar también los
módulos correspondientes.

## 4. Límites de cambio

- Trabaja únicamente en las rutas del módulo asignado y en sus pruebas. No escribas código de otro
  módulo ni muevas carpetas, contratos, configuración compartida o archivos raíz sin una asignación
  explícita de integración.
- Una tarea `integration` puede cambiar el shell, los artefactos comunes de contratos, `infra/`,
  pruebas entre módulos y SPEC-09…SPEC-13. No puede implementar lógica interna de dominios.
- No crees nuevas carpetas de módulo. Si una responsabilidad no cabe en el mapa, explica el conflicto
  y pide que el usuario asigne o redefina la frontera.
- No compartas tablas, modelos ORM internos, secretos o clases específicas de un lenguaje entre
  módulos. Comunícate mediante los contratos publicados.
- Si una dependencia exige cambiar el contrato o código de otro módulo, no lo cambies en silencio.
  Describe la incompatibilidad y solicita una asignación explícita para el módulo propietario.
- No dupliques la fuente semántica de contratos. `docs/contratos_modelo_datos.md` define su contenido;
  `contracts/generated/` solo contiene artefactos generados y no se edita a mano.

## 5. Requisitos, decisiones y conflictos

- Mantén la estructura de IDs `SPEC-XX Título` y `RF-NNN Título`. No renumeres ni elimines requisitos
  fuente, incluidos los de VOD y otras fases futuras.
- Si una implementación requiere cambiar alcance, comportamiento observable, contrato, ownership,
  seguridad o una cifra de aceptación, actualiza los documentos canónicos afectados en el mismo
  cambio. Registra decisiones técnicas en `docs/adr/` con la plantilla de
  [docs/politica_ADR.md](docs/politica_ADR.md).
- No marques como aceptada una tecnología que siga siendo candidata. Toda decisión tecnológica debe
  indicar alternativas, consecuencias y compatibilidad con consumidores.
- Si los documentos se contradicen o dejan una decisión de producto sin resolver, no inventes una
  regla. Expón el conflicto y continúa solo con el trabajo que no dependa de él.
- Conserva los límites de datos: cada dominio es autoridad de sus propios datos y publica los datos
  que otros consumen mediante API o eventos definidos.

## 6. Higiene de cambios

- No pongas credenciales, tokens, claves de emisión ni datos personales reales en el repositorio. Usa
  variables y valores ficticios en archivos de ejemplo.
- Mantén cada cambio dentro del módulo asignado y explica archivos compartidos que hayan sido
  autorizados explícitamente.
- No ejecutes pruebas ni agregues pruebas solo para verificar un cambio salvo que el usuario lo pida.
  Si se solicitan, reporta exactamente los comandos ejecutados y su resultado; nunca afirmes una
  verificación que no realizaste.
- Antes de entregar, revisa el diff, confirma que no invadiste otros módulos e informa decisiones
  pendientes, supuestos y limitaciones.
