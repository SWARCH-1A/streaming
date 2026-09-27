# Registro de decisiones — Streaming

**Estado:** decisiones de alcance P1 acordadas en conversación; se mantienen abiertas las decisiones
de implementación que cada módulo debe justificar en su ADR.
**Fecha de consolidación:** 2026-09-26
**Organización de trabajo:** SWARCH-1A; el repositorio principal es este monorepo.

Este archivo conserva las decisiones tomadas durante la entrevista de requisitos. Es la referencia
de producto para actualizar el catálogo, las especificaciones P1 y el índice de fases futuras. Los detalles
de implementación observables en Twitch se usan como referencia pública; no se infiere su arquitectura
interna.

## 1. Alcance y organización de requisitos

| ID | Decisión |
| --- | --- |
| D-01 | La primera iteración implementa todos los requisitos acordados como P1. |
| D-02 | Se conserva el catálogo fuente completo, incluidos RF de VOD. Solo se fusionan ideas que sean duplicadas; las ideas únicas se preservan. |
| D-03 | VOD queda fuera de P1. Sus requisitos y decisiones futuras se documentan ahora para evitar rehacer el análisis. |
| D-04 | Subtítulos quedan fuera de P1. Las demás medidas de accesibilidad de la interfaz sí se consideran en P1. |
| D-05 | Reorganizar y renumerar RF/RNF por dominio, manteniendo un cruce explícito de IDs antiguos a nuevos. |
| D-06 | Agrupar RF relacionados en SDDs por responsabilidad acotada; evitar tanto un SDD por detalle trivial como SDDs que abarquen muchos dominios. |
| D-07 | Mantener documentos transversales para arquitectura, propiedad de datos, contratos de interacción, integración del frontend y reverse proxy. |
| D-08 | Adaptar la plantilla SDD existente y conservar sus once secciones. Añadir trazabilidad de RF/RNF, criterios verificables, modelos de datos, contratos y ADRs; eliminar supuestos heredados de Promotores de Convivencia. |
| D-09 | Plane es el registro operativo de trabajo, pero los requisitos, contratos y decisiones técnicas canónicas viven en este repositorio. Toda operación sobre Plane se realiza con su MCP y dentro del proyecto STREAMING. |
| D-10 | Cada SPEC pertenece al módulo indicado en el mapa de módulos y SPEC. Esa frontera define responsabilidad lógica y de código, no implica un proceso independiente ni determina por sí sola una asignación personal. |
| D-11 | Cada responsable puede seleccionar las tecnologías de su módulo, registra un ADR con alternativas y trade-offs, y entrega los contratos necesarios a sus consumidores. Las tecnologías mencionadas en propuestas no son decisiones impuestas. |
| D-12 | El proyecto se implementará en este monorepo modular. Las carpetas definen ownership lógico; no fijan procesos ni tecnologías. Separar repositorios en el futuro requiere una decisión explícita y conservar contratos/versiones de integración. |
| D-13 | Identity es la única fuente autoritativa de `handle`. Channels solo guarda `ownerUserId` y `channelId`; cualquier copia de handle usada para resolver una URL es una proyección derivada, de solo lectura y actualizable desde Identity. El handle no se edita en P1; el diseño conserva la posibilidad de cambio futuro. |
| D-14 | Un error tras reservar una identidad pero antes de tener canal no se presenta como registro completado ni deja una cuenta activa huérfana. La operación permanece PENDING y recuperable con la misma clave durante 24 h desde su instante persistido `pendingSinceUtc`; después pasa a EXPIRED, libera email/handle y el usuario debe iniciar otro registro con una clave nueva. ACTIVE solo al provisionar exactamente un canal. El registro no inicia sesión; el usuario hace login después de ACTIVE. La implementación (outbox/saga/transacción) queda en ADR. |
| D-15 | La sesión Streaming tiene estados internos PREPARING, LIVE, RECONNECT_GRACE y ENDED; durante RECONNECT_GRACE el canal informa LIVE con disponibilidad RECONNECTING, el directorio de reproducción excluye el stream no reproducible y Chat conserva lectura/escritura hasta el límite de 30 s. El dueño vigente mide el límite con reloj monotónico: reconectar solo gana con elapsed < 30 s; con elapsed >= 30 s la transición serializada termina la sesión. El timestamp UTC publicado es informativo. Un traspaso de dueño transfiere el restante y cerca al dueño anterior; si no puede establecerse el restante, termina sin dar nueva gracia. El mecanismo distribuido se define en ADR. |
| D-16 | P1 expone lectura de sesión para el player, manifiesto HLS, y creación/renovación/cierre de un lease de playback emitido por servidor. `viewerCount` deriva de leases validados por instancia de reproducción, no de un valor indicado por el navegador. |
| D-17 | Chat usa WebSocket bidireccional en `/realtime/chat/sessions/{sessionId}` y REST para leer historial en `/api/chat/sessions/{sessionId}/messages`; lectura anónima, escritura autenticada. El cliente abre primero el WebSocket, espera `chat.ready`, solicita el historial y fusiona ambas fuentes por sequence. |
| D-18 | El límite P1 es como máximo un mensaje aceptado por cuenta en cualquier ventana móvil de 1000 ms en todo el sistema, no uno por sala; no se acumulan ráfagas. El objetivo de carga sigue siendo 20 mensajes/s agregados entre sesiones durante 10 minutos. |
| D-19 | La medición de inicio HLS es un máximo de 5 segundos, según PERF-NFR-02; no se convierte a percentil. Los umbrales definidos explícitamente como p95 conservan ese percentil. |
| D-20 | SRC-RF-71 y SRC-RF-72 se dividen semánticamente: búsqueda/filtro LIVE en P1 y búsqueda/filtro VOD en futuro (`RF-074` y `RF-075`). No se pierde la parte VOD de los requisitos fuente. |
| D-21 | Cada canal tiene cero o una configuración persistente de stream (`streamId` estable) con título, categoría, tags y clave RTMP. Cada emisión aceptada crea un `sessionId` nuevo; al acabar se conserva `streamId`/metadata para la siguiente emisión. La clave se muestra una sola vez al crear/rotar y solo se rota sin sesión activa. |
| D-22 | El inicio RTMP reserva un slot y pasa por PREPARING. Solo HLS comprobado habilita LIVE/PLAYABLE; si no es reproducible dentro de 30 s de autorizado el inicio, Streaming finaliza la sesión y libera el slot. El límite cinco cuenta PREPARING, LIVE y RECONNECT_GRACE; la gracia posterior a LIVE dura 30 s. |
| D-23 | La interfaz pública de Discovery usa GraphQL sobre HTTP/JSON según el contrato P1 vigente. Esto no impone GraphQL a Identity, Profile, Channels, Streaming, Taxonomy o Chat. La implementación concreta debe respetar esta interfaz y registrar su tecnología en ADR. |
| D-24 | Navegador se autentica con sesión opaca en cookie `HttpOnly; SameSite=Lax; Secure` en cualquier entorno HTTPS; no expone bearer/session token a JavaScript. La sesión dura 24 h desde login, sin extensión deslizante, y logout la revoca inmediatamente. El entorno local puede omitir `Secure` solo si funciona por HTTP local sin TLS. Mutaciones requieren defensa CSRF. Almacenamiento y token CSRF concretos se registran en ADR de Identity. |
| D-25 | Un handle P1 tiene 4–25 caracteres ASCII alfanuméricos o `_`, unicidad insensible a mayúsculas y forma canónica en minúsculas; no se aceptan espacios ni transliteración. Es inmutable en P1. El login admite email o handle. |
| D-26 | `POST /registrations` y la consulta de su operación nunca emiten credencial de sesión. Al alcanzar ACTIVE la cuenta se autentica por `POST /sessions`; esto hace seguros los reintentos y la recuperación por `Idempotency-Key` sin convertirla en sesión bearer. |
| D-27 | Email recorta espacios iniciales/finales y se compara en minúsculas para unicidad; no se colapsan alias con `+` ni puntos. En Discovery, `q` recorta espacios, se normaliza a Unicode NFKC y se compara sin distinguir mayúsculas, conservando diferencias de acentos; subcadena puede aparecer en cualquier posición. Streams ordena siempre por popularidad/tie-break. Channels ordena matches exactos, prefijos, subcadenas (en handle antes de displayName), luego handle ascendente y userId ascendente. |
| D-28 | Chat normaliza texto a Unicode NFC, recorta whitespace Unicode en los extremos, rechaza vacío y limita a 500 puntos de código Unicode. `clientMessageId` es UUID generado una vez por intento lógico y deduplica en `(sessionId,userId,clientMessageId)` mientras se retenga el mensaje; el servidor persiste antes del ACK y asigna sequence creciente por sesión. |
| D-29 | Cada mensaje usa la última muestra de timeline recibida por Chat en los últimos 3 s, extrapolada con reloj monotónico local; `timelineSampleVersion` es el `sessionVersion` de esa muestra. Streaming publica muestras al menos cada segundo durante LIVE y toda RECONNECT_GRACE. Si falta muestra fresca, WebSocket devuelve frame de error `TIMELINE_UNAVAILABLE` y no persiste; el offset P1 es aproximado, no una promesa de sincronía con VOD futuro. |
| D-30 | Contraseña P1: entre 12 y 128 puntos de código Unicode. Se conserva exactamente como se introduce: no recortar espacios ni normalizar Unicode; se permiten espacios y frases largas. No exigir mezcla de clases de caracteres ni caducidad periódica. Nunca truncar silenciosamente. |

## 2. P1 acordado

| Dominio | Incluido en P1 | Fuera de P1 o aplazado |
| --- | --- | --- |
| Identidad y autorización | Registro, login, logout, unicidad de email/handle, sesión y autorización mínima por propietario. | Verificación de email, recuperación de contraseña y endurecimiento avanzado. |
| Perfil | Consultar/editar nombre visible, biografía y avatar. | Cambiar handle y preferencias avanzadas. La portada pertenece al canal. |
| Canales | Un canal por cuenta, creado automáticamente con el registro; editar y consultar perfil público; consultar estado y stream activo. | Seguimiento SRC-RF-13…SRC-RF-15; catálogo VOD del canal. |
| Streaming | Ingesta y reproducción real en vivo, ciclo de sesión, metadatos, acceso de espectadores y conteo. | Calidad/transcoding SRC-RF-26…SRC-RF-29. |
| Chat | Sala por sesión en vivo, lectura pública, escritura autenticada, distribución, historial reciente y persistencia de eventos para Chat Replay futuro. | Moderación avanzada SRC-RF-35…SRC-RF-36, modo lento configurable y reproducción de VOD. |
| Taxonomía | Categoría requerida y hasta cinco etiquetas seleccionables desde catálogo controlado; consulta/filtrado de streams activos. | Administración dinámica del catálogo. |
| Descubrimiento | Listado de streams activos, búsqueda parcial de canales/títulos y filtros por categoría/etiquetas, únicamente sobre streams activos. | Búsqueda y filtros de VOD. |
| Accesibilidad | Operación por teclado, nombres/roles/estados semánticos, foco visible, contraste suficiente y control para pausar/ocultar autodesplazamiento del chat. | Subtítulos/captions; no declarar conformidad global WCAG 2.2 AA sin cubrir sus criterios aplicables. |
| Repositorios e integración | Documentar contrato del shell frontend, rutas del reverse proxy, puertos de desarrollo, propiedad de datos y comandos reproducibles. | El monorepo modular ya está establecido; procesos, puertos y stack se concretan mediante ADR. |

La partición de SRC-RF-12 evita mezclar dos capacidades: vista del stream en vivo en P1 y listado de
VOD en una fase futura. SRC-RF-18 describe la edición de metadatos de un stream; RF de Taxonomía definen
la asociación y consulta reutilizable de categoría/etiquetas.

## 3. Reglas funcionales acordadas

### Identidad, perfil y canal

- El registro requiere email único, handle único y contraseña. P1 no requiere verificación de email
  ni recuperación de contraseña.
- La contraseña P1 debe tener 12–128 puntos de código Unicode, se conserva exactamente como se
  introdujo y no tiene reglas de composición ni caducidad periódica.
- Cada cuenta tiene un solo canal, creado automáticamente al registrar la cuenta.
- El handle es único, inmutable durante P1 y forma la URL estable del canal. El nombre visible es
  editable y no cambia esa URL.
- El Perfil posee y permite editar el nombre visible, biografía y avatar. Canales posee la descripción pública y la imagen de portada del canal. El handle inmutable identifica la URL.
- Avatar e imagen de portada son opcionales; si no hay imagen se muestra una imagen predeterminada.
- Regla P1 para imágenes: JPEG/PNG/GIF; máximo 10 MB por imagen; avatar de al menos 200×200 px;
  imagen de portada recomendada de 1200×480 px. Se explicitan aquí los límites aceptados, sin requerir
  consultar otro documento para aplicarlos.

### Emisión en vivo

- El flujo real propuesto es ingestión RTMP y reproducción HLS. MediaMTX es una recomendación, no
  una selección tecnológica obligatoria; el responsable de Streaming debe justificar su decisión.
- El broadcaster prepara los metadatos y la fuente RTMP válida inicia la sesión automáticamente. Estado interno PREPARING dura hasta que el servidor confirma HLS reproducible. En una pérdida de origen la sesión entra RECONNECT_GRACE por 30 s; el canal muestra LIVE · reconectando con availability=RECONNECTING. Solo PLAYABLE se ofrece en browse/reproducción inmediata.
- Se permite una sesión no terminada por canal y hasta cinco en toda la plataforma; los cupos incluyen PREPARING y RECONNECT_GRACE además de LIVE.
- Cada stream requiere título y una categoría. Las etiquetas son opcionales: de cero a cinco, desde el catálogo controlado. El broadcaster puede editar título, categoría y etiquetas durante la emisión; Streaming confirma la escritura persistida y las lecturas de canal/Discovery reflejan la versión nueva dentro de 5 s.
- Al perder la fuente, la sesión conserva su identidad durante una ventana de reconexión de 30 segundos con estado interno RECONNECT_GRACE y availability=RECONNECTING. Channel muestra LIVE · reconectando; Discovery no lo lista como PLAYABLE; Chat permite lectura/escritura. Si la fuente vuelve dentro de la ventana, la misma sesión y sala continúan; si no, termina, cierra escritura, pasa a OFFLINE y el siguiente inicio crea sessionId nuevo.
- El propietario puede detener voluntariamente su sesión.
- Los visitantes pueden ver/listar streams sin cuenta. La cuenta sí es necesaria para enviar chat.
- El conteo representa leases de instancias de playback, sin exigir login, creados tras el primer frame; heartbeat cada 10 segundos y expiración a los 30 segundos sin señal validada. El número no representa participantes del chat ni un entero declarado por el cliente.

### Chat y Chat Replay futuro

- Hay una sala por sesión de stream. Visitantes anónimos pueden leer; solo usuarios autenticados
  pueden enviar.
- Se muestra a quien llega tarde un historial de los últimos 50 mensajes disponibles.
- Reglas P1 de chat: texto NFC, espacios Unicode inicial/final recortados, 1–500 puntos de código Unicode; máximo de un mensaje aceptado por cuenta en cualquier ventana móvil de 1000 ms, compartida entre todas las salas y sin ráfaga acumulada;
  renderizar contenido como texto seguro. Los valores son reglas de producto del prototipo y quedan
  documentados aquí sin depender de una especificación externa.
- La sala acepta mensajes durante la ventana de reconexión. Al terminar el stream pasa a solo lectura
  y no recibe mensajes de sesiones nuevas.
- Persistir cada evento con ID de mensaje, ID de sesión, cuenta/autor visible, contenido, timestamp
  del servidor y posición relativa a la línea temporal del medio. P1 no reproduce VOD; deja preparados
  los eventos para Chat Replay cuando VOD se implemente.
- La persistencia futura del chat se conserva/elimina junto con el VOD asociado según la política de
  retención que se acuerde para VOD. Una moderación futura que retire un mensaje del chat en vivo
  también debe excluirlo del replay.

### Taxonomía y descubrimiento

- Catálogo inicial de categorías aprobado: Conversación, Videojuegos, Música, Arte, Educación,
  Ciencia y tecnología, Deportes.
- Catálogo inicial de etiquetas aprobado: Español, Inglés, Educativo, Competitivo, Casual,
  Principiantes, Programación, IRL.
- Browse muestra sesiones PLAYABLE ordenadas por viewerCount DESC, startedAtUtc DESC y streamId ASC para desempate estable.
- La búsqueda acepta coincidencias parciales en nombre visible/handle de canal y título del stream.
  La búsqueda de canales incluye OFFLINE, LIVE y RECONNECTING y muestra disponibilidad; la búsqueda
  por título/filtros solo incluye streams PLAYABLE. Los filtros aceptan un categoryId y un tagId como
  máximo, y combinan ambos con AND. VOD queda excluido de P1; SRC-RF-71 y SRC-RF-72 conservan ramas VOD futuras.
- P1 permite seleccionar como máximo una categoría y un tag en una consulta; si ambos se indican los combina con AND. Selección simultánea de varios tags y VOD quedan fuera de P1.

### Carga y accesibilidad

- Objetivo de prueba: 5 transmisiones simultáneas, 100 espectadores concurrentes totales y 20
  mensajes de chat por segundo agregados entre salas activas, durante 10 minutos.
- La interfaz permite navegación esencial por teclado; controles con nombre/rol/estado accesibles,
  foco visible, contraste legible, y pausa/ocultación del autodesplazamiento del chat.

## 4. Arquitectura, integración y trazabilidad

- Las restricciones del catálogo de RNF siguen siendo de sistema: distribución en procesos, frontend
  web, al menos dos componentes de lógica desplegables, almacenamiento relacional y NoSQL con uso
  justificado, dos tipos distintos de conectores HTTP, tres lenguajes de propósito general,
  contenedores, reinicio independiente y despliegue reproducible.
- Cada módulo declara qué datos posee, qué API/eventos ofrece, consumidores, errores, autenticación,
  timeouts y reglas de compatibilidad. No se accede directamente al esquema interno de otro módulo.
- Los documentos transversales cubren vista C&C y despliegue, contratos/integración, propiedad de
  datos y ERD, frontend/reverse proxy, estructura del repositorio y política/índice de ADRs.
- El catálogo mantiene un mapa explícito SRC-RF-01…SRC-RF-76 → RF-001…RF-079 y alias de dominio anterior,
  incluyendo la separación de alcance P1/futuro, y una matriz RF/RNF → SPEC primaria y contribuyentes.
