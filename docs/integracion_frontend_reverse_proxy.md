# Web, móvil y API Gateway

[ADR-015](adr/ADR-015-arquitectura-p2.md) define una web React Router SSR, una aplicación
KMP + Compose Multiplatform y un Gateway de entrada y agregación. La aceptación integrada
está en [SPEC-15](spec-p2/spec_15_int.md).

## Renderizado y datos

El servidor Web resuelve las rutas y sus loaders mediante agregados del Gateway y devuelve
HTML con datos reales. La hidratación conserva ese estado sin repetir la carga inicial ni abrir
conexiones duplicadas. Los módulos actuales de React se reutilizan con acceso a navegador
limitado a efectos o componentes cliente. Los clientes HTTP deben admitir ejecución por
solicitud en el servidor, sin depender de window ni compartir cookies entre usuarios.

El Gateway devuelve un agregado por pantalla; el servidor SSR compone el HTML. Una navegación
o revalidación móvil/web no necesita una solicitud de cliente por cada servicio. Formularios,
paginación, seguimiento y comandos usan operaciones explícitas del dominio a través del Gateway.
Una respuesta parcial identifica sección, disponibilidad, error y correlación, sin convertir
un fallo en una colección vacía. Permisos y autenticación no se infieren de secciones faltantes.

HLS, WebSocket, controles del reproductor y envío de chat se ejecutan en los clientes. El estado
de canal y conteos se refresca mediante consultas periódicas con un presupuesto total nominal
de cinco segundos que incluye eventos y proyecciones. Reconexión, cambios de ruta y cancelación
de solicitudes deben descartar respuestas tardías y cerrar conexiones anteriores.

## Entrada y contratos

| Tráfico | Destino / comportamiento |
| --- | --- |
| Navegación web | Servidor SSR; respuesta HTML y recursos de Web |
| Agregados de pantalla | Gateway consulta Core, Streaming y VOD según la vista; Chat y Notificaciones conservan sus APIs |
| Identidad, perfil, canal, catálogo y Discovery | Core; se conservan permisos y semántica de las APIs existentes |
| Emisión, configuración, estado y leases | Streaming; la ruta de streams de canal tiene precedencia sobre el prefijo Canales |
| Chat y moderación | Chat; lectura inicial, mensajes y acciones autorizadas |
| WebSocket chat | Upgrade retransmitido; sin buffering ni envoltorio JSON del Gateway |
| HLS live/VOD | Manifiestos maestros, variantes, segmentos, Range y content types; sin agregación ni buffering de la respuesta completa |
| Biblioteca, borradores y publicación | VOD; acceso según publicación, propiedad y disponibilidad de cuenta |
| Bandeja, preferencias y dispositivos | Notificaciones; contratos pendientes por responsable |
| Rutas internas | Bloqueadas en el listener público |
| Ingesta | Listener del medio separado; no es una ruta HTTP de navegación |

Las rutas y schemas de los agregados y las APIs nuevas están definidos en
[contratos HTTP P2](contratos_p2_http.md) y [su fuente estructural](../contracts/p2.json).
P2-06 conserva la ratificación de los agregados y los parámetros operativos pendientes.
El enrutamiento HLS debe admitir manifiestos de variantes y subrutas. Los timeouts de streaming/WS
se configuran por su naturaleza; no heredan el timeout corto de una consulta agregada.

## Autenticación

Core mantiene la autoridad de sesión opaca, expiración y revocación. Web conserva cookie HttpOnly,
protección CSRF y validación de Origin en operaciones y WS de navegador. El servidor SSR reenvía
solo el contexto autorizado de la solicitud actual, nunca mediante un cliente global con cookies
compartidas. No cachear HTML privado como contenido público.

Móvil usa la misma sesión opaca mediante Bearer en almacenamiento seguro. Login/logout Core y
Upgrade Chat están implementados bajo los contratos de SPEC-01/19 y ADR-021; el mecanismo actual de credenciales
entre servicios no es una sesión móvil. La ausencia de Origin de un cliente nativo debe tratarse
por ese contrato, sin debilitar las verificaciones web. No enviar tokens en query strings ni logs.

Caddy sobrescribe headers de IP reenviada y elimina los de credenciales privadas aportados
por el público. El Gateway solo copia una lista explícita de headers y reenvía la dirección
observada únicamente si su par es un proxy de confianza; Core confía solo en Caddy y el Gateway,
así las cuotas por IP siguen siendo por cliente. Los servicios verifican siempre sus rutas internas. La autorización por mensaje,
propiedad y bloqueo pertenece a Core/Streaming/Chat, no a una decisión cacheada del Gateway.

## Fallos y experiencia

Cada sección distingue carga, vacío, error, acceso denegado e indisponibilidad. Si Chat falla,
el reproductor continúa. Si Notificaciones falla, el canal continúa. No usar datos demo como
respuesta a una API fallida. Un recurso suspendido o retirado deja de ser accesible también
por enlaces de manifiestos, segmentos, pistas y replay.

El player selecciona entre calidades realmente recibidas, manual o automáticamente. Las cuatro
rendiciones son una emisión y comparten cupo. La lectura del chat live sigue secuencia; el replay
consulta ventanas temporales del VOD y respeta seek, moderación y disponibilidad.

La accesibilidad cubre formularios, navegación, reproductor, chat y acciones administrativas:
teclado, nombres accesibles, foco y contraste. El chat permite detener autoscroll sin robar foco.

## Organización y ejecución

Core permanece en services/core; Streaming, en services/streaming; Chat, en services/chat;
Web, en apps/web. La ubicación de los nuevos componentes se fija al implementarlos. No se
crean microfrontends por módulo. React Router SSR requiere runtime de servidor y adaptación
de entrada, rutas/loaders y build; servir únicamente el dist de la SPA no cumple SSR.

El [README del repositorio](../README.md), [Core](../services/core/README.md) y el
[proxy P1](../infra/reverse-proxy/README.md) documentan comandos del código existente.
El producto y runtime del Gateway aún no están seleccionados. Los comandos y configuración
P2 se publican cuando existan, incluyendo HTTPS por dominio y un perfil local sencillo.
