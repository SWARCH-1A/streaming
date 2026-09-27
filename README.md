# STREAMING

Repositorio principal del proyecto académico STREAMING. Contiene el código, los contratos generados,
la configuración compartida y la documentación normativa necesaria para implementar la primera
iteración.

## Fuente normativa

La documentación canónica vive en `docs/`. Empieza por [docs/README.md](docs/README.md), que indica
el orden de lectura, alcance, contratos y decisiones. El trabajo de implementación debe seguir
[AGENTS.md](AGENTS.md): cada encargo se asigna a un módulo y ese módulo tiene rutas propias para
backend, frontend y pruebas.

La versión 0 de esta documentación define el alcance P1 y las capacidades futuras conservadas. Las
decisiones de implementación pendientes se registran en ADR dentro de `docs/adr/` antes de tratarse
como aprobadas.

## Módulos

| Módulo | SPEC primaria | Backend | Frontend |
| --- | --- | --- | --- |
| Identidad | SPEC-01 | `services/identity/` | `apps/web/modules/identity/` |
| Perfil | SPEC-02 | `services/profile/` | `apps/web/modules/profile/` |
| Canales | SPEC-03 | `services/channels/` | `apps/web/modules/channels/` |
| Streaming | SPEC-04 | `services/streaming/` | `apps/web/modules/streaming/` |
| Chat | SPEC-05 | `services/chat/` | `apps/web/modules/chat/` |
| Taxonomía | SPEC-06 | `services/taxonomy/` | `apps/web/modules/taxonomy/` |
| Descubrimiento | SPEC-07 | `services/discovery/` | `apps/web/modules/discovery/` |
| Accesibilidad | SPEC-08 | Sin servicio propio | `apps/web/accessibility/` y criterios aplicados en la UI |
| Integración | SPEC-09 a SPEC-13 | Sin servicio de dominio | Shell, contratos, infraestructura y pruebas integradas |

La estructura define propiedad lógica del código, no obliga a desplegar un proceso por carpeta. La
topología, herramientas y tecnologías que siguen abiertas se deciden mediante ADR y deben respetar
los contratos vigentes.
