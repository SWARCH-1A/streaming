# Mapa de módulos y especificaciones P1

Este mapa enlaza la responsabilidad normativa con las carpetas del repositorio. Lee
[AGENTS.md](../AGENTS.md) antes de editar y la [SPEC correspondiente](spec-p1/README.md) antes de
implementar. La ubicación del código define propiedad lógica, no una decisión de proceso o contenedor.

| SPEC | Módulo | Responsabilidad P1 | Backend | Interfaz |
| --- | --- | --- | --- | --- |
| [SPEC-01 Identidad y autorización](spec-p1/spec_01_auth.md) | identity | Registro, autenticación, sesión, principal y autorización por propiedad | services/identity/ | apps/web/modules/identity/ |
| [SPEC-02 Perfil público](spec-p1/spec_02_profile.md) | profile | Nombre visible, biografía y avatar | services/profile/ | apps/web/modules/profile/ |
| [SPEC-03 Canal público y propiedad](spec-p1/spec_03_channel.md) | channels | Canal público, descripción, banner, estado y vínculo con su propietario | services/channels/ | apps/web/modules/channels/ |
| [SPEC-04 Sesión en vivo e integración multimedia](spec-p1/spec_04_stream.md) | streaming | Configuración del stream, sesiones, ingestión, reproducción, leases y ciclo de vida | services/streaming/ | apps/web/modules/streaming/ |
| [SPEC-05 Chat en vivo y eventos para Replay](spec-p1/spec_05_chat.md) | chat | Sala por sesión, lectura, escritura autenticada, persistencia y eventos listos para replay futuro | services/chat/ | apps/web/modules/chat/ |
| [SPEC-06 Catálogo de categorías y etiquetas](spec-p1/spec_06_tax.md) | taxonomy | Vocabulario controlado y filtros de transmisiones LIVE | services/taxonomy/ | apps/web/modules/taxonomy/ |
| [SPEC-07 Descubrimiento](spec-p1/spec_07_disc.md) | discovery | Listado, búsqueda y filtros de canales y transmisiones LIVE | services/discovery/ | apps/web/modules/discovery/ |
| [SPEC-08 Accesibilidad del recorrido](spec-p1/spec_08_a11y.md) | accessibility | Criterios transversales de accesibilidad en el recorrido web P1 | Sin servicio propio | apps/web/accessibility/ y las vistas de cada módulo |
| [SPEC-09 Integración transversal](spec-p1/spec_09_int.md) | integration | Coordinación, límites compartidos y aceptación integrada | Sin servicio de dominio | apps/web/shell/, contracts/, infra/ y tests compartidas |
| [SPEC-10 Contratos API, propiedad de datos y errores](spec-p1/spec_10_int.md) | integration | Interfaces, ownership, autenticación, errores y evolución de contratos | contracts/ | tests/contracts/ |
| [SPEC-11 Flujos de sesión y eventos](spec-p1/spec_11_int.md) | integration | Secuencias y eventos que coordinan dominios | contracts/ | tests/integration/ |
| [SPEC-12 Shell web y reverse proxy](spec-p1/spec_12_int.md) | integration | Rutas, shell, WebSocket, HLS, RTMP y entrada de API | apps/web/shell/ e infra/reverse-proxy/ | tests/integration/ |
| [SPEC-13 Despliegue integrado y E2E](spec-p1/spec_13_int.md) | integration | Arranque reproducible, health, reinicio, carga y recorrido integrado | infra/ | tests/e2e/ |

## Límites entre módulos

- Identity es la fuente de autoridad para identidad y handle. Profile y Channels no almacenan
  credenciales ni se convierten en autoridad del handle.
- Cada dominio posee sus propios datos. Las interacciones entre dominios usan los contratos de
  [datos e interfaces](contratos_modelo_datos.md) y [flujos](integracion_sistema_p1.md); no se accede a
  tablas privadas ajenas.
- Accessibility no es un servicio backend ni una autorización general para editar todas las vistas.
  Las tareas sobre pantallas concretas deben nombrar también los módulos que se modificarán.
- Integration no es un servicio de dominio. Su carpeta agrupa la composición compartida, artefactos
  generados, infraestructura y pruebas entre componentes; no contiene lógica interna de otros dominios.
- VOD, subtítulos y demás capacidades futuras permanecen en el catálogo y [fases futuras](fases_futuras.md).
  Que queden fuera de P1 no autoriza a borrarlas ni a implementarlas en una iteración P1.
