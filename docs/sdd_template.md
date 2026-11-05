# SPEC-<NN> — <Título>

> **Estado:** Borrador | **Prioridad:** P1/P2/P3/Futuro | **Dominio:** <dominio>
> **RF relacionados:** <RF-NNN globales> | **RNF:** <IDs relacionados>
> **Módulo:** <nombre> | **Responsable:** <por asignar>
> **Versión:** 1.0 | **Actualizado:** <AAAA-MM-DD>

## 1. Contexto y problema

Describe la necesidad y el comportamiento esperado con lenguaje directo. Enlaza los requisitos
y la arquitectura aplicables. Las políticas vigentes se redactan como definición del sistema;
las alternativas sin resolver se registran como preguntas pendientes, sin narración de reuniones
ni atribuciones personales.

## 2. Definición del componente

Describe qué partes ya existen y cuáles falta construir o integrar. Si no hay implementación,
indícalo. Incluye los componentes, pantallas y contratos afectados, y usa la estructura de código
acordada por el equipo.

## 3. Historia de usuario

> Como **<actor>**, quiero **<capacidad>**, para **<resultado>**.

Añade actores secundarios y permisos cuando sean necesarios para entender el recorrido.

## 4. Alcance

### Dentro de esta iteración

- …

### Fuera de esta iteración

- …

### Supuestos acordados

- …

## 5. Requisitos funcionales

Usa los IDs estables del catálogo y describe un comportamiento que pueda comprobarse. Puedes usar EARS para expresar las condiciones y la respuesta del sistema.

- **RF-001** — El sistema debe …
- **RF-002** — Cuando …, el sistema debe …
- **RF-003** — Mientras …, el sistema debe …

Incluye reglas de negocio, precondiciones, permisos, estados y errores de validación.
Si un requisito pertenece a otro dominio, enlázalo y explica cómo se relacionan los módulos.

## 6. Criterios de aceptación

Escribe escenarios Dado/Cuando/Entonces (Given/When/Then) que podamos probar de forma manual
o automática. Cubre el caso esperado, los límites, los errores y los permisos.
Relaciona cada RF con al menos un criterio.

- [ ] **CA-01 — <nombre>** Dado … Cuando … Entonces …
- [ ] **CA-02 — <nombre>** Dado … Cuando … Entonces …

## 7. Diseño técnico y datos

Explica cómo se implementará el comportamiento y quién administra cada dato.
Las tecnologías propuestas deben identificarse como tales hasta que su responsable registre
y justifique la elección en un ADR aceptado.

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

Para cada dependencia, explica quién llama a quién, quién decide el valor de cada dato,
cómo se autentican y qué ocurre ante timeout o reintento. El contrato debe poder consumirse
sin depender del lenguaje ni del almacenamiento privado del proveedor. Si la llamada es local,
indica la interfaz de aplicación correspondiente.

## 9. Decisiones y preguntas abiertas

### Definición vigente

- **D-01** — … (referencia al ADR aplicable).

### Preguntas abiertas

- **P-01 — <bloqueante/no bloqueante>** … Responsable y fecha objetivo: …

Resuelve las preguntas bloqueantes antes de desarrollar una interfaz que pueda resultar incompatible.

## 10. Verificación

| RF/RNF | Prueba o inspección | Evidencia esperada |
| --- | --- | --- |
| … | … | … |

Incluye las pruebas unitarias, de integración, contrato, interfaz y carga que correspondan.
La matriz debe cubrir cada RF y RNF aplicable, e indicar qué pruebas están propuestas y
cuáles ya tienen resultados de ejecución.

## 11. Esfuerzo, riesgos y consecuencias

- **Esfuerzo:** S/M/L, con las principales tareas que lo explican.
- **Riesgos:** técnicos, de integración, seguridad, datos y dependencias del equipo.
- **Consecuencias:** limitaciones aceptadas y trabajo aplazado a otra fase.
