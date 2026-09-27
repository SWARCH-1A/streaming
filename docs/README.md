# Documentación normativa del proyecto STREAMING

## Autoridad

Esta carpeta contiene los requisitos, decisiones de producto, especificaciones, contratos y reglas de
integración vigentes. Es autocontenida para construir el proyecto: no requiere archivos históricos,
material preliminar ni páginas externas. Los requisitos fuera de P1 se conservan aquí para que
aplazarlos no implique perderlos.

El código se organiza según [el mapa de módulos y SPEC](mapa_sdd_p1.md) y las instrucciones raíz
[AGENTS.md](../AGENTS.md). Las especificaciones de esta carpeta, no los artefactos operativos del
tracker, fijan el comportamiento del producto.

## Orden de lectura

1. [Contexto y glosario](glosario_y_contexto.md): actores, términos e identificadores.
2. [Decisiones de alcance](decisiones_alcance_p1.md): decisiones aprobadas y límites P1.
3. [Catálogo RF/RNF](catalogo_requisitos_v2.md): requisitos canónicos, prioridades y cruce de IDs fuente.
4. [Mapa de módulos y SPEC](mapa_sdd_p1.md): responsabilidad de cada dominio y carpeta asignada.
5. [Índice SPEC P1](spec-p1/README.md): las trece especificaciones completas.
6. [Matriz de trazabilidad RNF](matriz_trazabilidad_nfr.md): responsables y evidencia de cierre.
7. Arquitectura e interacción: [C&C y despliegue](arquitectura_c4_cnc_despliegue.md),
   [datos y contratos](contratos_modelo_datos.md), [flujos entre dominios](integracion_sistema_p1.md)
   y [shell/proxy](integracion_frontend_reverse_proxy.md).
8. [Fases futuras](fases_futuras.md), [política ADR](politica_ADR.md) y decisiones registradas en
   [docs/adr](adr/README.md).
9. [Revisión crítica P1](auditoria_ambiguedades_p1.md): riesgos residuales y puertas de implementación.
10. [Plantilla SPEC](sdd_template.md) para una especificación nueva.

## Convenciones

- P1 es el alcance acordado de la primera iteración; no equivale a una demo mínima.
- P2/Futuro conserva requisitos fuera de P1; no se eliminan por no implementarse todavía.
- RF-NNN es el identificador funcional canónico. El catálogo conserva los IDs fuente y su cruce,
  incluidas las divisiones entre capacidades P1 y futuras.
- Los títulos empiezan por el identificador ordenable: SPEC-XX Título y RF-NNN Título.
- Cada RF/RNF tiene una SPEC primaria responsable de evidenciar su cierre; las SPEC contribuyentes
  verifican la parte que les corresponde.
- La carpeta del módulo expresa propiedad de código y no obliga a desplegar un proceso independiente.
- Una cifra de aceptación no se cambia implícitamente. Los cambios de requisito y las decisiones
  técnicas deben registrarse en los documentos correspondientes.
