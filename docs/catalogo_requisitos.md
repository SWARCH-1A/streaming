# Catálogo de requisitos — STREAMING

El catálogo contiene 83 requisitos funcionales y 51 no funcionales. Los identificadores son
estables. P2 conserva las capacidades de P1 e incorpora todas las funciones pendientes salvo
pagos, suscripciones de pago y premium (RF-038…RF-045).

Referencias: [alcance P2](alcance_p2.md), [decisiones pendientes](decisiones_pendientes_p2.md),
[mapa de responsabilidades](mapa_sdd_p1.md) y [SPEC P2](spec-p2/README.md).

## Convenciones

- P1 identifica capacidades de la primera entrega que continúan en P2.
- P2 identifica capacidades incorporadas en la segunda entrega.
- Futuro identifica funciones fuera de la segunda entrega.
- Un requisito o una decisión aceptada no acredita implementación ni pruebas.

## Requisitos funcionales

| ID y nombre | Dominio | Prioridad | Requisito |
| --- | --- | --- | --- |
| RF-001 Registro de cuenta | Autenticación | P1 | El sistema debe permitir que un visitante registre una cuenta con un correo electrónico único, un handle único y una contraseña. |
| RF-002 Inicio de sesión | Autenticación | P1 | El sistema deberá permitir a un usuario autenticarse mediante sus credenciales. |
| RF-003 Cierre de sesión | Autenticación | P1 | El sistema debe permitir a un usuario cerrar su sesión e invalidar su sesión autenticada actual. |
| RF-004 Recuperación de credenciales | Autenticación | P2 | El sistema deberá permitir recuperar o restablecer las credenciales de acceso de una cuenta. |
| RF-005 Autorización por identidad y propiedad | Autenticación | P1 | El sistema debe identificar al usuario autenticado y autorizar cada operación según identidad, propiedad, moderación y permisos administrativos; cada dominio valida las operaciones sobre sus datos. |
| RF-006 Consulta de perfil público | Perfil | P1 | El sistema debe permitir consultar la información pública de un perfil; los datos privados solo son visibles para su propietario. |
| RF-007 Edición de perfil y avatar | Perfil | P1 | El sistema debe permitir editar el nombre visible, la biografía y el avatar del usuario. En P1 el handle es único, inmutable y forma la URL estable. |
| RF-008 Provisión de canal por cuenta | Canales | P1 | El sistema debe crear automáticamente un canal asociado a una cuenta cuando se registra; cada cuenta tiene exactamente un canal en P1. |
| RF-009 Edición de descripción y portada del canal | Canales | P1 | El sistema debe permitir al propietario editar la descripción y la imagen de portada del canal. El handle permanece inmutable durante P1; el nombre visible y el avatar pertenecen al Perfil. |
| RF-010 Consulta de canal público | Canales | P1 | El sistema deberá permitir consultar la información pública de un canal. |
| RF-011 Estado de canal durante reconexión | Canales | P1 | El ciclo de sesión conserva la misma sesión durante 30 segundos de gracia; su disponibilidad pública cambia a RECONNECTING, el canal indica LIVE · reconectando, Discovery no lo incluye como reproducible y al vencer pasa a ENDED/OFFLINE. |
| RF-012 Streams activos del canal | Canales | P1 | El sistema debe mostrar las transmisiones en vivo activas de un canal. |
| RF-013 Catálogo VOD por canal | VOD | P2 | El sistema debe mostrar el catálogo VOD disponible de un canal. |
| RF-014 Seguir canal | Seguimiento | P2 | El sistema deberá permitir a un usuario seguir un canal. |
| RF-015 Dejar de seguir canal | Seguimiento | P2 | El sistema deberá permitir a un usuario dejar de seguir un canal. |
| RF-016 Consultar canales seguidos | Seguimiento | P2 | El sistema deberá permitir consultar los canales seguidos por un usuario. |
| RF-017 Inicio automático de sesión RTMP | Streaming | P1 | El sistema debe iniciar automáticamente una sesión en vivo para el canal propietario cuando llegan metadatos válidos y una fuente RTMP reproducible. |
| RF-018 Asociación de stream al canal | Streaming | P1 | El sistema deberá asociar cada transmisión en vivo con el canal desde el cual fue iniciada. |
| RF-019 Metadatos de emisión | Streaming | P1 | El sistema debe exigir título y una categoría, aceptar de cero a cinco etiquetas del catálogo controlado, y permitir editar esos metadatos durante LIVE con actualización inmediata en canal y descubrimiento. |
| RF-020 Estados de sesión y disponibilidad | Streaming | P1 | El sistema debe modelar PREPARING, LIVE, RECONNECT_GRACE y ENDED; LIVE comienza cuando la fuente puede reproducirse. RECONNECT_GRACE conserva la identidad de sesión durante 30 segundos con availability=RECONNECTING. |
| RF-021 Finalización voluntaria de emisión | Streaming | P1 | El sistema debe permitir al propietario finalizar voluntariamente su transmisión activa. |
| RF-022 Reconexión y expiración de sesión | Streaming | P1 | El sistema debe finalizar la sesión si la fuente no regresa dentro de los 30 segundos de gracia. Una reconexión dentro de la ventana continúa la misma sesión; una posterior inicia otra. |
| RF-023 Acceso público a streams | Streaming | P1 | El sistema debe permitir a visitantes y usuarios acceder a transmisiones activas sin iniciar sesión. |
| RF-024 Reproducción HLS | Streaming | P1 | El sistema debe reproducir la transmisión en vivo mediante el manifiesto HLS disponible para espectadores. |
| RF-025 Información de la emisión | Streaming | P1 | El sistema deberá mostrar al espectador información de la transmisión que está consumiendo, incluyendo canal, título y categoría. |
| RF-026 Conteo de reproducciones activas | Streaming | P1 | El sistema debe contar leases de instancias de reproducción anónimas o autenticadas tras el primer frame; heartbeat objetivo cada 10 segundos y expiración a los 30 segundos del último heartbeat válido. |
| RF-027 Recibir niveles de calidad | Calidad | P2 | El sistema debe recibir y empaquetar hasta cuatro calidades codificadas en origen como una única emisión. No genera calidades mediante transcoding en el servidor ni ofrece resoluciones superiores a las recibidas. |
| RF-028 Consultar calidades disponibles | Calidad | P2 | El sistema debe permitir consultar las calidades realmente disponibles para una emisión. |
| RF-029 Selección manual de calidad | Calidad | P2 | El sistema debe permitir al espectador seleccionar manualmente una calidad recibida y disponible, tanto en vivo como en VOD. |
| RF-030 Selección automática de calidad | Calidad | P2 | El sistema debe permitir selección automática entre las calidades recibidas y disponibles según las condiciones de reproducción. |
| RF-031 Sala de chat por sesión | Chat | P1 | El sistema debe ofrecer una sala de chat asociada a cada sesión LIVE. Durante la ventana de reconexión continúa disponible; al finalizar la sesión pasa a solo lectura. |
| RF-032 Envío autenticado de mensajes | Chat | P1 | El sistema debe permitir a usuarios autenticados enviar mensajes de texto NFC no vacío al chat de una sesión LIVE; el texto normalizado y recortado no supera 500 puntos de código Unicode. |
| RF-033 Distribución y cuota de mensajes | Chat | P1 | El sistema debe distribuir los mensajes a los espectadores conectados con latencia inferior a 1 segundo en el 95 % de los mensajes bajo la carga objetivo. Cada cuenta puede enviar como máximo un mensaje en cualquier ventana móvil de 1000 ms, compartida entre todas las salas y sin ráfaga acumulada. |
| RF-034 Identidad visible e historial de chat | Chat | P1 | El sistema debe mostrar el nombre visible del autor y el contenido seguro de cada mensaje. El historial inicial para quien llega tarde muestra hasta los últimos 50 mensajes disponibles. |
| RF-035 Rechazo en chat no disponible | Chat | P1 | El sistema debe rechazar el envío cuando la sala esté cerrada o no disponible e informar claramente al usuario. |
| RF-036 Eliminar mensajes de chat | Chat | P2 | El sistema debe permitir al propietario del canal eliminar mensajes de su chat; la supresión se aplica a la distribución en vivo y al replay del VOD. |
| RF-037 Bloquear usuarios en chat | Chat | P2 | El sistema debe permitir al propietario bloquear la escritura de un usuario en el chat de su canal, temporalmente con vencimiento o permanentemente hasta retirarlo. Puede levantar el bloqueo; bloquear no elimina mensajes anteriores automáticamente. |
| RF-038 Consultar opciones de suscripción | Suscripciones | Futuro | El sistema deberá permitir a un usuario consultar las opciones de suscripción de pago disponibles para un canal. |
| RF-039 Registrar suscripción | Suscripciones | Futuro | El sistema deberá permitir registrar una suscripción de pago de un usuario a un canal. |
| RF-040 Estado de suscripción | Suscripciones | Futuro | El sistema deberá mantener el estado de una suscripción, incluyendo si se encuentra activa o inactiva. |
| RF-041 Consultar suscripciones activas | Suscripciones | Futuro | El sistema deberá permitir a un usuario consultar sus suscripciones activas. |
| RF-042 Identificar acceso premium | Premium | Futuro | El sistema deberá identificar si un usuario tiene acceso a las características premium asociadas a una transmisión o canal. |
| RF-043 Restringir funciones premium | Premium | Futuro | El sistema deberá restringir las funcionalidades premium a los usuarios que cumplan las condiciones de acceso correspondientes. |
| RF-044 Definir requisitos premium | Premium | Futuro | El sistema deberá permitir al streamer definir qué funcionalidades o contenido de su canal requieren una suscripción activa. |
| RF-045 Mostrar contenido exclusivo premium | Premium | Futuro | El sistema deberá permitir mostrar elementos exclusivos dentro de una transmisión a los usuarios con acceso premium. |
| RF-046 Crear watch party | Watch Party | P2 | El sistema deberá permitir a un usuario autorizado crear una sesión de visualización conjunta. |
| RF-047 Agregar streams a watch party | Watch Party | P2 | El sistema deberá permitir agregar varias transmisiones activas a una sesión de visualización conjunta. |
| RF-048 Retirar streams de watch party | Watch Party | P2 | El sistema deberá permitir retirar una transmisión de una sesión de visualización conjunta. |
| RF-049 Reproducir streams simultáneamente | Watch Party | P2 | El sistema deberá mostrar simultáneamente las transmisiones asociadas a una sesión de visualización conjunta. |
| RF-050 Acceder a watch party | Watch Party | P2 | El sistema deberá permitir a otros usuarios acceder a una sesión de visualización conjunta. |
| RF-051 Mostrar canales de watch party | Watch Party | P2 | El sistema deberá mostrar información de cada uno de los canales incluidos en una sesión de visualización conjunta. |
| RF-052 Notificar inicio de stream | Notificaciones | P2 | El sistema debe generar una notificación lógica por destinatario y nueva sesión en vivo de un canal seguido, con entrega independiente en bandeja, correo y push según preferencias. Reconectar la misma sesión no genera otro aviso. |
| RF-053 Consultar notificaciones | Notificaciones | P2 | El sistema debe permitir consultar la bandeja de notificaciones en web y móvil. |
| RF-054 Marcar notificación leída | Notificaciones | P2 | El sistema deberá permitir marcar una notificación como leída. |
| RF-055 Configurar notificaciones | Notificaciones | P2 | El sistema debe permitir configurar avisos por canal seguido y medios de entrega. La bandeja es el medio inicial; correo y push requieren activación expresa y pueden deshabilitarse globalmente por medio. |
| RF-056 Guardar emisión como VOD | VOD | P2 | El sistema debe grabar automáticamente todas las calidades recibidas de una emisión en almacenamiento externo S3 y conservarlas para consumo bajo demanda, con metadatos y publicación administrados por VOD. |
| RF-057 Asociar VOD a canal y emisión | VOD | P2 | El sistema deberá asociar el contenido almacenado con el canal y la transmisión de origen. |
| RF-058 Consultar catálogo VOD | VOD | P2 | El sistema deberá permitir consultar el catálogo de transmisiones almacenadas de un canal. |
| RF-059 Reproducir VOD | VOD | P2 | El sistema debe permitir reproducir un VOD publicado con todas las calidades conservadas, selección manual/automática y respeto de su disponibilidad y permisos. |
| RF-060 Editar metadatos VOD | VOD | P2 | El sistema deberá permitir al propietario del canal modificar la información de una transmisión almacenada. |
| RF-061 Eliminar VOD | VOD | P2 | El sistema debe permitir al propietario eliminar manualmente un VOD. Se retira su acceso y se coordina la limpieza de objetos, pistas y replay mediante operaciones reintentables; no hay caducidad automática del VOD. |
| RF-062 Mostrar duración y metadatos VOD | VOD | P2 | El sistema deberá mostrar la duración y los metadatos asociados al contenido bajo demanda. |
| RF-063 Asociar subtítulos | Accesibilidad | P2 | El sistema deberá permitir asociar pistas de subtítulos a una transmisión o contenido almacenado. |
| RF-064 Activar o desactivar subtítulos | Accesibilidad | P2 | El sistema deberá permitir al espectador activar o desactivar los subtítulos disponibles. |
| RF-065 Seleccionar pista de subtítulos | Accesibilidad | P2 | El sistema deberá permitir al espectador seleccionar entre las pistas de subtítulos disponibles. |
| RF-066 Asociar categoría activa | Categorías | P1 | El sistema debe asociar cada transmisión con exactamente una categoría activa del catálogo controlado. |
| RF-067 Asociar etiquetas activas | Categorías | P1 | El sistema debe asociar de cero a cinco etiquetas activas del catálogo controlado a cada transmisión. |
| RF-068 Consultar streams por categoría | Categorías | P1 | El sistema debe permitir consultar transmisiones LIVE por categoría. |
| RF-069 Consultar streams por etiqueta | Categorías | P1 | El sistema debe permitir consultar transmisiones LIVE por una etiqueta seleccionada mediante su ID controlado; la consulta puede combinar un categoryId y un tagId con AND. |
| RF-070 Listar streams por popularidad | Descubrimiento | P1 | El sistema debe mostrar transmisiones LIVE en orden descendente de espectadores concurrentes. |
| RF-071 Buscar canales públicos | Descubrimiento | P1 | El sistema debe buscar canales públicos, estén LIVE u OFFLINE, por coincidencia parcial en handle o nombre visible, sin distinguir mayúsculas y minúsculas; el resultado indica su estado actual. |
| RF-072 Buscar streams por título | Descubrimiento | P1 | El sistema debe buscar transmisiones LIVE reproducibles por coincidencia parcial en su título. |
| RF-073 Filtrar streams LIVE | Descubrimiento | P1 | El sistema debe filtrar transmisiones LIVE reproducibles por categoryId o tagId controlado; permite como máximo un ID de cada tipo y combina ambos con AND. |
| RF-074 Buscar VOD por título | VOD / Descubrimiento | P2 | El sistema deberá buscar contenido VOD disponible por coincidencia parcial en su título. |
| RF-075 Filtrar VOD por categoría o etiqueta | VOD / Descubrimiento | P2 | El sistema deberá filtrar contenido VOD disponible por categoría o etiqueta seleccionada. |
| RF-076 Consultar usuarios como administrador | Administración | P2 | El sistema deberá permitir a un administrador consultar usuarios registrados. |
| RF-077 Consultar canales como administrador | Administración | P2 | El sistema deberá permitir a un administrador consultar canales registrados. |
| RF-078 Retirar contenido infractor | Administración | P2 | El sistema deberá permitir a un administrador retirar contenido que incumpla las reglas establecidas por la plataforma. |
| RF-079 Suspender o reactivar cuentas | Administración | P2 | El sistema debe permitir al administrador suspender o reactivar una cuenta. La suspensión revoca sesiones, impide autenticación, escritura de chat y nuevas emisiones, interrumpe la emisión activa y deja indisponibles canal, VOD, pistas y replay, incluso mediante URLs directas. La reactivación respeta el estado previo de publicación, borrado y moderación. |
| RF-080 Configurar autopublicación VOD | VOD / Canales | P2 | El sistema debe ofrecer autopublicación de VOD por canal, activada por defecto. La emisión toma una copia de la preferencia al iniciar; los cambios afectan a emisiones futuras. Desactivarla conserva la grabación como borrador privado. |
| RF-081 Publicar borrador VOD | VOD | P2 | El sistema debe permitir al propietario revisar, editar y publicar manualmente un borrador cuyo medio haya sido validado. |
| RF-082 Reproducir chat del VOD | Chat / VOD | P2 | El sistema debe ofrecer replay de mensajes sincronizado con la posición del VOD, incluyendo búsqueda temporal, paginación y supresiones de moderación. Los mensajes necesarios se conservan mientras exista el VOD asociado. |
| RF-083 Eliminar mensajes de un usuario | Chat | P2 | El sistema debe permitir al streamer solicitar explícitamente la eliminación de mensajes de un usuario de su canal, con el alcance definido por el responsable de Chat. La acción es opcional e independiente del bloqueo y afecta tanto a live como a replay. |

## Requisitos no funcionales

| ID | Categoría | Aplicabilidad | Requisito |
| --- | --- | --- | --- |
| RNF-001 | Arquitectura | P2 sistema o componente aplicable | El sistema deberá seguir una **arquitectura distribuida**, en la que existan componentes ejecutables independientes que se comuniquen mediante conectores de red. |
| RNF-002 | Arquitectura | P2 sistema o componente aplicable | El sistema debe incluir al menos dos componentes de presentación: una web con SSR y una aplicación móvil multiplataforma. |
| RNF-003 | Arquitectura | P2 sistema o componente aplicable | El sistema debe incluir al menos seis componentes de lógica ejecutables independientes, de acuerdo con los seis integrantes. Su inventario y clasificación deben verificarse con el criterio de la asignatura. |
| RNF-004 | Datos | P2 sistema o componente aplicable | El sistema debe incluir al menos cuatro componentes de datos y utilizar almacenamiento relacional. El inventario debe distinguir componentes desplegados de bases lógicas compartidas. |
| RNF-005 | Datos | P2 sistema o componente aplicable | El sistema deberá utilizar al menos **un componente de almacenamiento NoSQL**. |
| RNF-006 | Conectividad | P2 sistema o componente aplicable | El sistema debe utilizar conectores basados en HTTP entre componentes y documentar su semántica junto con los conectores de mensajería. |
| RNF-007 | Tecnología | P2 sistema o componente aplicable | La implementación debe utilizar al menos cinco lenguajes de programación de propósito general diferentes, acreditados por componentes implementados. |
| RNF-008 | Despliegue | P2 sistema o componente aplicable | Los componentes de servidor desplegables deben ejecutarse mediante contenedores; los clientes móviles se instalan en sus plataformas. |
| RNF-009 | Despliegue | P2 sistema o componente aplicable | Cada componente lógico deberá poder iniciarse, detenerse y reiniciarse de forma independiente de los demás componentes lógicos. |
| RNF-010 | Portabilidad | P2 sistema o componente aplicable | El entorno completo del prototipo deberá poder desplegarse a partir de instrucciones reproducibles sin depender de configuración manual específica de la máquina del desarrollador. |
| RNF-011 | Rendimiento | P2 sistema o componente aplicable | Bajo el perfil integrado P1 de SPEC-13, la aplicación procesará 10 solicitudes HTTP de API por segundo durante 10 minutos: 2 logins correctos, 3 consultas Discovery, 1 búsqueda de canal, 1 lectura de Taxonomy, 1 lectura de canal, 1 lectura de perfil y 1 lectura de estado de stream por segundo. Cada endpoint tendrá p95 ≤2 s. Se reportarán tasa de solicitudes, tamaño de muestra, latencia y fallos por endpoint; una operación válida con timeout o respuesta no exitosa reprueba el run, aunque el p95 de las respuestas 2xx cumpla. HLS, lease y heartbeat se reportan separados. |
| RNF-012 | Rendimiento | P2 sistema o componente aplicable | El inicio de reproducción de una transmisión deberá ocurrir en un máximo de **5 segundos** después de que el usuario solicite reproducirla, bajo condiciones normales de red y carga. |
| RNF-013 | Rendimiento | P2 sistema o componente aplicable | Los mensajes enviados al chat deberán ser visibles para los participantes conectados en un máximo de **1 segundo para el 95 % de los mensajes**, bajo la carga objetivo. |
| RNF-014 | Rendimiento | P2 sistema o componente aplicable | El cambio de estado de un canal entre OFFLINE y LIVE debe reflejarse en la interfaz en un máximo nominal de cinco segundos. El presupuesto incluye publicación, broker, proyección, consulta y actualización del cliente. |
| RNF-015 | Escalabilidad | P2 sistema o componente aplicable | El sistema deberá permitir incrementar la capacidad de los componentes de lógica sin requerir modificaciones al frontend ni a los demás componentes no afectados. |
| RNF-016 | Escalabilidad | P2 sistema o componente aplicable | Los componentes encargados de transmisión, aplicación y comunicación en tiempo real deberán poder escalar independientemente según su carga. |
| RNF-017 | Escalabilidad | P2 sistema o componente aplicable | El sistema deberá soportar al menos 5 transmisiones simultáneas y 100 reproducciones concurrentes totales en la plataforma durante las pruebas objetivo. |
| RNF-018 | Escalabilidad | P2 sistema o componente aplicable | El sistema deberá soportar al menos 20 mensajes de chat por segundo agregados entre las salas activas durante 10 minutos. Para cada cuenta autenticada, Chat aceptará como máximo un mensaje en cualquier ventana móvil de 1000 ms, sin ráfaga adicional y global entre salas. Se reportarán latencia, pérdidas, duplicados y fallos. |
| RNF-019 | Disponibilidad | P2 sistema o componente aplicable | Una falla del componente de chat no deberá impedir que los espectadores continúen consumiendo una transmisión que ya se encuentre disponible. |
| RNF-020 | Disponibilidad | P2 sistema o componente aplicable | Una falla temporal de un componente no esencial, como notificaciones, no deberá provocar la indisponibilidad total del sistema. |
| RNF-021 | Confiabilidad | P2 sistema o componente aplicable | El sistema deberá detectar la pérdida de fuente y finalizar una sesión si la fuente no regresa en 30 segundos. |
| RNF-022 | Confiabilidad | P2 sistema o componente aplicable | La información persistente de usuarios, canales y contenido almacenado no deberá perderse cuando un componente de lógica sea reiniciado. |
| RNF-023 | Tolerancia a fallos | P2 sistema o componente aplicable | Los consumidores y el Gateway deben manejar errores, tiempos de espera y respuestas no disponibles. Un agregado identifica las secciones disponibles, vacías, denegadas o fallidas y conserva el contenido utilizable; una autorización no comprobada se rechaza. |
| RNF-024 | Consistencia | P2 sistema o componente aplicable | Las operaciones que modifiquen información persistente deberán mantener la integridad de los datos incluso ante fallos parciales durante su ejecución. |
| RNF-025 | Seguridad | P2 sistema o componente aplicable | Toda comunicación que transporte credenciales, tokens de autenticación o información privada deberá realizarse mediante **HTTPS/TLS** en los ambientes donde el sistema sea accesible por red. |
| RNF-026 | Seguridad | P2 sistema o componente aplicable | Las contraseñas de los usuarios no deberán almacenarse en texto plano y deberán mantenerse utilizando un mecanismo de hashing apropiado para contraseñas. |
| RNF-027 | Seguridad | P2 sistema o componente aplicable | El sistema deberá verificar la identidad y los permisos del usuario antes de ejecutar operaciones protegidas. |
| RNF-028 | Seguridad | P2 sistema o componente aplicable | Un usuario no deberá poder modificar canales, transmisiones o contenido perteneciente a otro usuario salvo que disponga explícitamente de permisos administrativos. |
| RNF-029 | Seguridad | P2 sistema o componente aplicable | Los mecanismos de autenticación deberán utilizar credenciales o tokens con expiración y deberán rechazar credenciales inválidas o expiradas. |
| RNF-030 | Seguridad | P2 sistema o componente aplicable | Las entradas proporcionadas por los usuarios deberán ser validadas antes de ser procesadas o almacenadas. |
| RNF-031 | Seguridad | P2 sistema o componente aplicable | Identity permitirá como máximo 5 intentos de login fallidos por identificador normalizado y ventana móvil de 15 minutos, y 50 intentos fallidos por IP en esa ventana; un login correcto reinicia el contador del identificador. El registro permitirá como máximo 10 claves de idempotencia nuevas por IP y ventana móvil de una hora. Chat permitirá como máximo un mensaje aceptado por cuenta en cualquier ventana móvil de 1000 ms, global entre salas y sin ráfaga adicional. Identity responde HTTP `429 RATE_LIMITED` con `Retry-After`; Chat, cuyo transporte de escritura es WebSocket, envía un frame `error` con `code=RATE_LIMITED` y `retryAfterMs`. No se bloquea permanentemente la cuenta. |
| RNF-032 | Privacidad | P2 sistema o componente aplicable | La información privada de un usuario no deberá exponerse mediante las interfaces públicas de usuarios o canales. |
| RNF-033 | Usabilidad | P2 sistema o componente aplicable | Las principales funciones de espectador, incluyendo descubrir una transmisión, abrir un canal y comenzar la reproducción, deberán estar disponibles desde una interfaz web sin requerir software adicional. |
| RNF-034 | Usabilidad | P2 sistema o componente aplicable | La interfaz deberá informar al usuario cuando una transmisión se encuentre fuera de línea, esté cargando o haya ocurrido un error de reproducción. |
| RNF-035 | Accesibilidad | P2 sistema o componente aplicable | La interfaz web debe permitir operar las funciones esenciales mediante teclado, con orden de foco lógico y foco visible. |
| RNF-036 | Accesibilidad | P2 sistema o componente aplicable | Los controles esenciales deben exponer nombre, rol y estado accesibles; texto y controles deben mantener contraste legible. |
| RNF-037 | Compatibilidad | P2 sistema o componente aplicable | La aplicación web deberá funcionar correctamente en las versiones estables actuales de al menos Chrome y Firefox. |
| RNF-038 | Evolutividad | P2 sistema o componente aplicable | Los componentes de lógica deberán exponer interfaces claramente definidas de forma que puedan ser reemplazados o modificados sin exigir cambios internos en sus consumidores. |
| RNF-039 | Evolutividad | P2 sistema o componente aplicable | La incorporación de nuevas categorías o etiquetas no deberá requerir modificaciones del código fuente del cliente. |
| RNF-040 | Evolutividad | P2 sistema o componente aplicable | La arquitectura debe permitir incorporar pagos, suscripciones de pago y funciones premium sin modificar sustancialmente los componentes no relacionados. |
| RNF-041 | Mantenibilidad | P2 sistema o componente aplicable | Cada componente deberá mantener separadas sus responsabilidades principales y evitar el acceso directo a los datos internos pertenecientes a otro componente lógico. |
| RNF-042 | Mantenibilidad | P2 sistema o componente aplicable | Las interfaces entre componentes deberán estar documentadas, incluyendo operaciones, datos intercambiados y posibles errores. |
| RNF-043 | Observabilidad | P2 sistema o componente aplicable | Cada componente lógico deberá producir registros que permitan identificar al menos el momento, tipo de evento, componente de origen y resultado de las operaciones relevantes. |
| RNF-044 | Observabilidad | P2 sistema o componente aplicable | Los errores de los componentes distribuidos deberán registrarse con suficiente información para identificar el servicio y la operación que los produjo. |
| RNF-045 | Observabilidad | P2 sistema o componente aplicable | El sistema deberá permitir consultar el estado operativo de sus principales componentes mediante mecanismos de verificación de salud. |
| RNF-046 | Interoperabilidad | P2 sistema o componente aplicable | Los datos intercambiados entre componentes mediante interfaces HTTP deberán utilizar formatos documentados e independientes del lenguaje de implementación. |
| RNF-047 | Interoperabilidad | P2 sistema o componente aplicable | Los servicios no deberán depender de bibliotecas internas del lenguaje de otro componente para poder comunicarse con él. |
| RNF-048 | Datos | P2 sistema o componente aplicable | Las relaciones que requieran integridad referencial, como usuarios, canales y transmisiones, deberán almacenarse utilizando el componente relacional cuando corresponda. |
| RNF-049 | Datos | P2 sistema o componente aplicable | La utilización del almacenamiento NoSQL deberá corresponder a información cuya forma de acceso o naturaleza justifique dicho modelo, como estado efímero de transmisiones, sesiones o información en tiempo real. |
| RNF-050 | Recuperación | P2 sistema o componente aplicable | El sistema deberá permitir reconstruir su estado persistente después del reinicio de los componentes sin requerir la creación manual nuevamente de usuarios, canales o contenido previamente almacenado. |
| RNF-051 | Procesamiento asíncrono | P2 sistema o componente aplicable | El sistema debe incluir procesamiento asíncrono con un único broker compartido. Los consumidores de ciclo de emisión, segmentos y notificaciones tienen suscripciones o colas independientes, confirmación durable, deduplicación, reintentos y recuperación observable. Las consultas y autorizaciones inmediatas pueden usar HTTP síncrono. |

## Catálogo de datos inicial aprobado

### Categorías

Conversación; Videojuegos; Música; Arte; Educación; Ciencia y tecnología; Deportes.

### Etiquetas

Español; Inglés; Educativo; Competitivo; Casual; Principiantes; Programación; IRL.

El catálogo inicial se entrega como datos controlados y se consulta mediante su API de lectura.
En P1 no hay una interfaz para que usuarios o administradores agreguen categorías o etiquetas.

RNF-039 exige que los valores incorporados en el backend, mediante la configuración o el
almacenamiento elegido, lleguen al cliente sin modificar su código ni reconstruir Web.
La API debe devolver el catálogo actualizado. Esa capacidad permite ampliar la semilla,
pero no añade una operación de alta de valores para usuarios en P1.

## Parámetros de imágenes de perfil/canal

El avatar y la portada son opcionales. Admitimos JPEG, PNG o GIF de hasta 10 MB por imagen.
El avatar debe medir al menos 200×200 px; para la portada recomendamos 1200×480 px.
Si el usuario no sube una imagen, se muestra el recurso predeterminado.

## Decisiones transversales relacionadas

- Una sesión activa por canal y máximo cinco emisiones simultáneas; las rendiciones comparten sesión y cupo.
- LIVE requiere evidencia de medio reproducible. La gracia de reconexión conserva sesión y chat durante 30 segundos.
- Mirar contenido público no requiere login; escribir en chat requiere identidad válida y ausencia de bloqueo.
- Chat conserva mensajes durables para replay y aplica las mismas supresiones en vivo y en VOD.
- VOD tiene contenedor, API y persistencia propios; los objetos audiovisuales residen en S3 externo.
- Web usa React Router SSR y móvil KMP con Compose Multiplatform. HLS y WebSocket se ejecutan en el cliente.
- El Gateway compone datos por pantalla y preserva fallos parciales; cada servicio conserva reglas y datos de negocio.
- El perfil de carga de P1 se conserva como referencia de regresión. La aceptación integrada de P2 incluye los recorridos y fallos de [SPEC-15](spec-p2/spec_15_int.md).
- Las políticas de watch parties, subtítulos y las tecnologías nuevas se cierran por sus responsables en [decisiones pendientes](decisiones_pendientes_p2.md).

## Fuera de P2

RF-038…RF-045: pagos, suscripciones de pago y acceso premium. Seguimiento, notificaciones,
calidades, moderación, VOD, replay, watch parties, subtítulos y administración forman parte de P2.
