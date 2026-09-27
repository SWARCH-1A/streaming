# Política de ADR y justificación tecnológica

## Quién decide

La persona responsable del módulo puede seleccionar su lenguaje, framework, almacenamiento y librerías.
La decisión se acepta cuando cumple los contratos del sistema y restricciones de la asignatura,
documenta alternativas y consecuencias, y no rompe a consumidores. El SDD especifica la obligación y
la interfaz; el ADR registra la selección concreta y su motivo.

## Cuándo registrar

Crear ADR antes de:

- escoger o cambiar tecnología que afecte a contrato, almacenamiento, runtime, deploy, auth, media,
  tiempo real o frontend;
- modificar frontera de componente, protocolo, propietario de datos o ruta pública;
- seleccionar SQL/NoSQL, mecanismos de sesión, broker, reverse proxy, player o carga de archivos;
- hacer incompatible una decisión vigente.

Cambios menores locales pueden explicarse en el PR. Un ADR aceptado no impide reemplazo; un ADR nuevo
debe identificar cuál revierte y cómo migra datos/consumidores.

## Plantilla

```markdown
# ADR-NNN: <decisión en una frase>

- Estado: propuesta | aceptada | rechazada | sustituida
- Fecha: YYYY-MM-DD
- Responsable: <nombre/módulo>
- SDD/contratos afectados: <IDs>

## Contexto
Problema, requisitos y restricciones verificables.

## Decisión
Selección concreta y alcance de la decisión.

## Opciones consideradas
Para cada opción: ajuste a requisitos, esfuerzo, complejidad, integración y riesgos.

## Consecuencias
Beneficios, costos, fallos/degradación, operación, seguridad, migración y lock-in aceptados.

## Verificación
Prueba o evidencia que demuestra el requisito que motivó la decisión.

## Revisión
Señales que justificarían revisar la elección y quién la revisa.
```

## Revisión de equipo

Compartir ADR con módulos consumidores cuando cambie su contrato; dejar sus observaciones registradas.
Una selección tecnológica de módulo no debe:

- exigir acceso a la base privada de otro módulo;
- imponer su lenguaje o tipos internos como contrato;
- requerir que el shell del frontend incorpore una excepción global no justificada;
- impedir una prueba integrada y despliegue reproducible;
- dejar sin explicación la contribución a las restricciones de SQL, NoSQL, conectores HTTP, lenguajes,
  contenedores o reinicio independiente.

Las decisiones del equipo van en ADR separado de decisiones de producto. Una opción de este catálogo
o de un SDD marcada como candidata no se vuelve selección aprobada por aparecer en el texto.
