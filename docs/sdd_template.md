# SPEC-<NN> — <Título>

> **Estado:** Borrador | **Prioridad:** P1/P2/P3/Futuro | **Dominio:** <dominio>
> **RF relacionados:** <RF-NNN globales> | **RNF:** <IDs relacionados>
> **Módulo:** <nombre> | **Responsable:** <por asignar>
> **Versión:** 1.0 | **Actualizado:** <AAAA-MM-DD>

## 1. Contexto y problema

Describir la necesidad del usuario, su origen y el problema que resuelve. Distinguir hechos de
decisiones acordadas y de propuestas. No inventar citas de reuniones. Referenciar el catálogo y las
especificaciones arquitectónicas normativas de `docs/`.

## 2. Definición del componente

Indicar qué existe en el repositorio y qué debe construirse o integrarse. Si todavía no existe
implementación, decirlo explícitamente. Incluir los componentes, pantallas y contratos afectados sin
suponer una estructura de código que no se haya acordado.

## 3. Historia de usuario

> Como **<actor>**, quiero **<capacidad>**, para **<resultado>**.

Incluir actores secundarios y permisos relevantes cuando apliquen.

## 4. Alcance

### Dentro de esta iteración

- …

### Fuera de esta iteración

- …

### Supuestos acordados

- …

## 5. Requisitos funcionales

Usar IDs estables del catálogo y redacción comprobable, preferiblemente en formato EARS.

- **RF-001** — El sistema debe …
- **RF-002** — Cuando …, el sistema debe …
- **RF-003** — Mientras …, el sistema debe …

Incluir reglas de negocio, precondiciones, permisos, estados y errores de validación. No duplicar un
requisito que pertenece a otro dominio: referenciarlo y describir el contrato entre ambos.

## 6. Criterios de aceptación

Escribir escenarios Given/When/Then que puedan verificarse manual o automáticamente. Cubrir el
recorrido nominal, límites, errores y permisos. Cada RF debe tener al menos un criterio asociado.

- [ ] **CA-01 — <nombre>** Dado … Cuando … Entonces …
- [ ] **CA-02 — <nombre>** Dado … Cuando … Entonces …

## 7. Diseño técnico y datos

Este diseño fija contratos y responsabilidades; las tecnologías concretas siguen siendo propuesta
hasta que la persona responsable del módulo registre y justifique su ADR.

- **Ubicación arquitectónica:** unidad desplegable y módulo interno; identificar fronteras y autoridad de datos.
  Justificar cualquier nueva frontera por carga, fallo, invariantes y costo operativo, no por nombre de SPEC.
- **Propiedad de datos:** entidades/atributos que administra el módulo; incluir diagrama ER o esquema
  cuando aplique. Escritura mediante repositorio dueño; FK y lecturas SQL compuestas revisadas dentro de Core.
  Ningún otro servicio lee/escribe esas tablas.
- **Interfaz backend:** operaciones, rutas, métodos, esquemas de petición/respuesta, autenticación,
  códigos de error, idempotencia y paginación cuando aplique.
- **Eventos o tiempo real:** nombre, productor, consumidor, identificador, orden, marcas de tiempo,
  semántica de reintento y duplicados.
- **Frontend:** estados, componentes visibles, accesibilidad y coordinación con el shell web.
- **Proxy y despliegue local:** ruta pública, destino, puerto de desarrollo y configuración compartida.
- **Tecnologías candidatas y ADR:** opciones, razones, trade-offs y riesgos; marcar cada selección como
  propuesta o decisión aprobada.

## 8. Dependencias y contratos de integración

| Dependencia/consumidor | Propósito | Contrato o dato intercambiado | Modo de fallo |
| --- | --- | --- | --- |
| … | … | … | … |

Indicar dirección de llamadas, autoridad de cada dato, autenticación entre componentes, timeouts,
reintentos y cómo se evita el acoplamiento al lenguaje o almacenamiento privado de otra unidad desplegable; indicar interfaz local cuando no exista salto de red.

## 9. Decisiones y preguntas abiertas

### Decisiones tomadas

- **D-01** — … (origen y fecha).

### Preguntas abiertas

- **P-01 — <bloqueante/no bloqueante>** … Responsable y fecha objetivo: …

No iniciar desarrollo de una interfaz incompatible mientras tenga una pregunta bloqueante.

## 10. Verificación

| RF/RNF | Prueba o inspección | Evidencia esperada |
| --- | --- | --- |
| … | … | … |

Incluir pruebas unitarias, de integración, contrato, interfaz y carga cuando correspondan. La matriz
debe cubrir cada RF y RNF aplicable; distinguir pruebas ejecutadas de pruebas propuestas.

## 11. Esfuerzo, riesgos y consecuencias

- **Esfuerzo:** S/M/L, con las principales tareas que lo explican.
- **Riesgos:** técnicos, de integración, seguridad, datos y dependencias del equipo.
- **Consecuencias:** limitaciones aceptadas y trabajo aplazado a otra fase.
