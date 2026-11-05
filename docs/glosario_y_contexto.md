# Contexto y glosario del sistema

## Sistema

STREAMING es nuestro prototipo de una plataforma web y móvil donde los usuarios pueden tener un canal,
transmitir video en vivo y conversar con los espectadores. La interfaz se presenta como Vault tv.

P2 incluye web SSR, aplicación móvil, seguimiento, multicalidad, moderación, VOD/replay,
notificaciones, watch parties, subtítulos y administración. Pagos, suscripciones de pago y
premium quedan fuera. El [alcance P2](alcance_p2.md) fija las políticas y las
[decisiones pendientes](decisiones_pendientes_p2.md) asignan las preguntas abiertas.

## Actores

- **Visitante/espectador:** consulta canales y streams de forma anónima, reproduce y lee chat; su
  playback cuenta para viewers mientras envía heartbeats.
- **Usuario autenticado:** además puede publicar mensajes y administrar su propio perfil/canal.
- **Propietario/streamer:** dueño de una cuenta/canal; administra perfil, portada/descripción y metadata
  del stream propio, inicia/finaliza emisión.
- **Administrador:** rol provisionado para consultar usuarios/canales, retirar contenido y suspender/reactivar cuentas.
- **Responsable de módulo:** persona que el equipo asigna para decidir e implementar su módulo y registrar
  ADRs.
- **Consumidor/proveedor:** entre unidades desplegables se intercambian datos mediante contrato neutral; dentro
  de Core se usan interfaces locales y esquema SQL con FK/lecturas compuestas revisadas.

## Términos que usamos en el proyecto

| Término | Significado en este proyecto |
| --- | --- |
| Cuenta/Identity | Credenciales, email, handle, sesión autenticada y principal. No es el perfil público. |
| Handle | Identificador único e inmutable, parte de la URL estable del canal. |
| Profile | Nombre visible, bio y avatar. Nunca contiene contraseña, hash, token ni email público. |
| Channel | Página pública propiedad de una cuenta; tiene descripción y banner. Un canal por cuenta. |
| Stream | La emisión/contenido lógico asociado a un canal; agrupa configuración y sucesivas sesiones de emisión. |
| Stream session / sessionId | Una ejecución temporal concreta de un stream. Se conserva durante reconexión menor a 30 s; nuevo inicio posterior crea otro ID. |
| LIVE | Estado interno con HLS reproducible confirmado. El estado público LIVE del canal incluye RECONNECT_GRACE, con availability=RECONNECTING. |
| RECONNECT_GRACE | Período de hasta 30 s sin fuente reproducible durante el cual se conserva sessionId y chat; el público sigue considerando esa emisión actual. |
| OFFLINE/ENDED | No existe sesión que cumpla la regla LIVE/grace; chat ya no acepta mensajes y una próxima emisión obtiene nuevo sessionId. |
| Metadata | Título, una categoría y entre cero y cinco etiquetas de catálogo; streamer puede editarlas durante LIVE. |
| Categoría | Valor requerido del vocabulario controlado para una emisión. El catálogo inicial contiene siete valores semilla. |
| Tag/etiqueta | Valor opcional controlado para describir una emisión; máximo cinco. El catálogo inicial contiene ocho valores semilla. |
| Viewer/playback session | Reproducción abierta, anónima o autenticada, contada una vez al hacerse reproducible y expirada tras 30 s sin heartbeat. No equivale a usuario de chat. |
| Chat room | Sala asociada a un único sessionId. Lectura anónima, escritura autenticada; read-only tras fin de sesión. |
| Chat Replay | Consulta de mensajes sincronizada con el VOD, mediante mapping temporal y ventanas/cursor; conserva moderación y disponibilidad del contenido. |
| VOD | Grabación de una sesión y sus calidades recibidas; VOD administra metadatos, publicación y referencias S3 en su propia unidad. |
| API Gateway | Entrada HTTPS y composición de datos por pantalla, errores parciales y retransmisión WS/HLS; los dominios conservan permisos y escrituras. |
| SSR | Renderizado HTML en servidor con datos iniciales; el navegador hidrata y ejecuta HLS/WS. |
| Broker | Infraestructura única de mensajería, con consumidores independientes y entrega durable; no sustituye consultas ni autorización inmediata. |
| Rendición | Una calidad codificada en origen de la misma sesión; hasta cuatro recibidas, sin otro cupo por calidad. |
| Módulo | Responsabilidad interna con repositorio/interfaz; puede compartir proceso y transacción con otros módulos Core. |
| Core | Núcleo de negocio modular: cuentas, canales, catálogo y Discovery; un proceso y PostgreSQL con proyección pública Streaming. |
| Servicio desplegable | Runtime/release/health/escala propios; Core, Streaming, Chat, VOD y Notificaciones como unidades de dominio; Media y Gateway tienen responsabilidades técnicas propias. |
| Integration | Responsabilidad transversal de contratos, Gateway, broker, configuración y aceptación integrada. |
| SDD | Especificación de diseño de software con once secciones; agrupa requisitos de responsabilidad coherente. |
| RF/RNF | Requisito funcional/no funcional; `RF-NNN` y `RNF-NNN` son IDs globales definidos en `catalogo_requisitos.md`. |
| P1 / P2 / Futuro | Capacidades de la primera entrega que continúan, incorporaciones de la segunda y funciones fuera de P2, respectivamente. |
| ADR | Registro versionado de una decisión arquitectónica o tecnológica con opciones, racional y consecuencias. |

## Identificadores que no se deben confundir

- `userId` identifica cuenta/persona dentro de los contratos.
- `channelId` identifica el canal público, único por userId.
- `streamId` identifica una definición/emisión lógica asociada a un canal.
- `sessionId` identifica una ejecución del stream y enlaza Chat Replay.
- `messageId` identifica un evento/mensaje y es estable para deduplicación y moderación.
- `leaseId` identifica una instancia de reproducción computable para viewers; el lease puede pertenecer a una persona anónima. Streaming crea y controla el lease y sus expiraciones.
- Los IDs de categoría y etiqueta identifican entradas del vocabulario controlado, no texto libre.

## Límites aceptados

El perfil base continúa en P2 con estos límites:

- Cada emisión tiene una categoría y entre cero y cinco etiquetas. Hay una sesión activa por canal y un máximo de cinco en toda la plataforma.
- El perfil de prueba contempla cinco emisiones y 100 reproducciones concurrentes. La carga de chat es de veinte mensajes por segundo en total durante diez minutos.
- Al entrar al chat se consultan los últimos cincuenta mensajes. Cada mensaje admite hasta 500 puntos de código Unicode. Una cuenta puede enviar como máximo un mensaje en cualquier ventana móvil de 1000 ms, sin acumular ráfagas.
- La latencia de chat debe tener un P95 menor a un segundo y la API un P95 de hasta dos segundos. El inicio de reproducción tiene un máximo de cinco segundos; ese límite no se mide como percentil.
- Los cambios de estado del canal deben aparecer en cinco segundos. Una reproducción deja de contar como espectador después de treinta segundos sin heartbeat.

El catálogo indica a qué dominio corresponde cada límite y cómo se verifica.

Cada emisión admite hasta cuatro calidades recibidas bajo una única sesión y cupo. La frescura
de cinco segundos incluye toda la cadena de publicación, broker, proyección, consulta y cliente.
