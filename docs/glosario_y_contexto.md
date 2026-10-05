# Contexto y glosario del sistema

## Sistema

`STREAMING` es un prototipo web de una plataforma de canales con transmisión audiovisual en vivo,
chat, taxonomía y descubrimiento. P1 incluye identidad, perfil, canal, streaming en vivo, chat,
categorías/etiquetas, búsqueda, accesibilidad del recorrido e integración. Seguimiento, calidad,
pagos, VOD, moderación avanzada, subtítulos y otras capacidades se conservan fuera de P1 en el catálogo.

## Actores

- **Visitante/espectador:** consulta canales y streams de forma anónima, reproduce y lee chat; su
  playback cuenta para viewers mientras envía heartbeats.
- **Usuario autenticado:** además puede publicar mensajes y administrar su propio perfil/canal.
- **Propietario/streamer:** dueño de una cuenta/canal; administra perfil, portada/descripción y metadata
  del stream propio, inicia/finaliza emisión.
- **Responsable de módulo:** persona que el equipo asigna para decidir e implementar su módulo y registrar
  ADRs.
- **Consumidor/proveedor:** entre unidades desplegables se intercambian datos mediante contrato neutral; dentro
  de Core se usan interfaces locales y esquema SQL con FK/lecturas compuestas revisadas.

## Términos normativos

| Término | Significado en este proyecto |
| --- | --- |
| Cuenta/Identity | Credenciales, email, handle, sesión autenticada y principal. No es el perfil público. |
| Handle | Identificador único, inmutable en P1, parte de la URL estable del canal. |
| Profile | Nombre visible, bio y avatar. Nunca contiene contraseña, hash, token ni email público. |
| Channel | Página pública propiedad de una cuenta; tiene descripción y banner. Un canal por cuenta en P1. |
| Stream | La emisión/contenido lógico asociado a un canal; en P1 describe metadata y reproducción LIVE. |
| Stream session / sessionId | Una ejecución temporal concreta de un stream. Se conserva durante reconexión menor a 30 s; nuevo inicio posterior crea otro ID. |
| LIVE | Estado interno con HLS reproducible confirmado. El estado público LIVE del canal incluye RECONNECT_GRACE, con availability=RECONNECTING. |
| RECONNECT_GRACE | Período de hasta 30 s sin fuente reproducible durante el cual se conserva sessionId y chat; el público sigue considerando esa emisión actual. |
| OFFLINE/ENDED | No existe sesión que cumpla la regla LIVE/grace; chat ya no acepta mensajes y una próxima emisión obtiene nuevo sessionId. |
| Metadata | Título, una categoría y entre cero y cinco etiquetas de catálogo; streamer puede editarlas durante LIVE. |
| Categoría | Valor requerido del vocabulario controlado para una emisión. P1 inicia con siete valores semilla. |
| Tag/etiqueta | Valor opcional controlado para describir una emisión; máximo cinco. P1 inicia con ocho valores semilla. |
| Viewer/playback session | Reproducción abierta, anónima o autenticada, contada una vez al hacerse reproducible y expirada tras 30 s sin heartbeat. No equivale a usuario de chat. |
| Chat room | Sala asociada a un único sessionId. Lectura anónima, escritura autenticada; read-only tras fin de sesión. |
| Chat Replay | Futuro comportamiento que sincroniza eventos de chat con una reproducción VOD. P1 no conserva el chat tras la retención de 5 minutos posterior al fin ni almacena/reproduce VOD. |
| VOD | Contenido de una transmisión pasada almacenado para reproducirse bajo demanda; todo VOD está fuera de P1. |
| Reverse proxy | Componente de entrada que enruta HTTPS, API, WebSocket y media a upstreams definidos; no posee lógica de dominio. |
| Módulo | Responsabilidad interna con repositorio/interfaz; puede compartir proceso y transacción con otros módulos Core. |
| Core | Núcleo de negocio modular: cuentas, canales, catálogo y Discovery; un proceso y PostgreSQL con proyección pública Streaming. |
| Servicio desplegable | Runtime/release/health/escala propios; Core, Streaming Rust, Chat y Media como unidades backend. |
| Integration | Trabajo de contratos/configuración/evidencia; no servicio de negocio ni coordinador global. |
| SDD | Especificación de diseño de software con once secciones; agrupa requisitos de responsabilidad coherente. |
| RF/RNF | Requisito funcional/no funcional; `RF-NNN` y `RNF-NNN` son IDs globales definidos en `catalogo_requisitos.md`. |
| P1 | Prioridad de la primera iteración acordada por el equipo y registrada en el catálogo y decisiones. |
| ADR | Registro versionado de una decisión arquitectónica o tecnológica con opciones, racional y consecuencias. |

## Identificadores que no se deben confundir

- `userId` identifica cuenta/persona dentro de los contratos.
- `channelId` identifica el canal público, único por userId en P1.
- `streamId` identifica una definición/emisión lógica asociada a un canal.
- `sessionId` identifica una ejecución del stream y enlaza Chat Replay futuro.
- `messageId` identifica un evento/mensaje y es estable para deduplicación y futura moderación.
- `leaseId` identifica una instancia de reproducción computable para viewers; el lease puede pertenecer a una persona anónima. Streaming crea y controla el lease y sus expiraciones.
- Los IDs de categoría y etiqueta identifican entradas del vocabulario controlado, no texto libre.

## Límites aceptados

Una sola categoría, cero a cinco tags, una sesión activa por canal, máximo cinco sesiones de toda la
plataforma, cinco streams concurrentes/100 viewers para perfil de prueba global, veinte mensajes por
segundo agregados durante diez minutos, cincuenta mensajes de historial inicial, 500 puntos de código
Unicode por mensaje, como máximo un envío por cuenta en toda ventana móvil de 1000 ms (sin ráfaga),
P95 chat menor a un segundo, API P95 ≤2 segundos, reproducción inicia en ≤5 segundos,
estado de canal se actualiza en cinco segundos y
conteo expira a los treinta segundos sin heartbeat. El catálogo especifica dominio y método de
verificación para cada límite.
