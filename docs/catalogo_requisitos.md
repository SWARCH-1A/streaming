# Catálogo de requisitos — STREAMING, versión 0

> Línea base inicial del proyecto. RF-001…RF-079 son los identificadores funcionales; RNF-001…RNF-050 son los identificadores no funcionales. Las prioridades y decisiones P1 constan en el registro de decisiones.

> Documentos relacionados: [decisiones de alcance](decisiones_alcance_p1.md), [mapa de módulos y SPEC](mapa_sdd_p1.md) y [plantilla SPEC](sdd_template.md).

## Convenciones

- Los RF usan numeración global RF-001…RF-079 y los RNF RNF-001…RNF-050; cada identificador permanece estable. El nombre visible antepone el ID al título.
- P1 significa que debe implementarse en la primera iteración. P2/P3/Futuro se mantienen documentados para fases posteriores.

## Requisitos funcionales

| ID y nombre | Dominio | Prioridad | Requisito |
| --- | --- | --- | --- |
| RF-001 Registro de cuenta | Autenticación | P1 | El sistema debe permitir que un visitante registre una cuenta con un correo electrónico único, un handle único y una contraseña. |
| RF-002 Inicio de sesión | Autenticación | P1 | El sistema deberá permitir a un usuario autenticarse mediante sus credenciales. |
| RF-003 Cierre de sesión | Autenticación | P1 | El sistema debe permitir a un usuario cerrar su sesión e invalidar su sesión autenticada actual. |
| RF-004 Recuperación de credenciales | Autenticación | P2 / Futuro | El sistema deberá permitir recuperar o restablecer las credenciales de acceso de una cuenta. |
| RF-005 Autorización por identidad y propiedad | Autenticación | P1 | El sistema debe identificar al usuario autenticado y autorizar cada operación P1 según identidad y propiedad; un usuario solo modifica su perfil, canal y transmisiones propias. |
| RF-006 Consulta de perfil público | Perfil | P1 | El sistema debe permitir consultar la información pública de un perfil; los datos privados solo son visibles para su propietario. |
| RF-007 Edición de perfil y avatar | Perfil | P1 | El sistema debe permitir editar el nombre visible, la biografía y el avatar del usuario. En P1 el handle es único, inmutable y forma la URL estable. |
| RF-008 Provisión de canal por cuenta | Canales | P1 | El sistema debe crear automáticamente un canal asociado a una cuenta cuando se registra; cada cuenta tiene exactamente un canal en P1. |
| RF-009 Edición de descripción y portada del canal | Canales | P1 | El sistema debe permitir al propietario editar la descripción y la imagen de portada del canal. El handle permanece inmutable durante P1; el nombre visible y el avatar pertenecen al Perfil. |
| RF-010 Consulta de canal público | Canales | P1 | El sistema deberá permitir consultar la información pública de un canal. |
| RF-011 Estado de canal durante reconexión | Canales | P1 | El ciclo de sesión conserva la misma sesión durante 30 segundos de gracia; su disponibilidad pública cambia a RECONNECTING, el canal indica LIVE · reconectando, Discovery no lo incluye como reproducible y al vencer pasa a ENDED/OFFLINE. |
| RF-012 Streams activos del canal | Canales | P1 | El sistema debe mostrar las transmisiones en vivo activas de un canal. |
| RF-013 Catálogo VOD por canal | VOD | P2 / Futuro | El sistema debe mostrar el catálogo VOD disponible de un canal. |
| RF-014 Seguir canal | Seguimiento | P2 / Futuro | El sistema deberá permitir a un usuario seguir un canal. |
| RF-015 Dejar de seguir canal | Seguimiento | P2 / Futuro | El sistema deberá permitir a un usuario dejar de seguir un canal. |
| RF-016 Consultar canales seguidos | Seguimiento | P2 / Futuro | El sistema deberá permitir consultar los canales seguidos por un usuario. |
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
| RF-027 Producir niveles de calidad | Calidad | P2 / Futuro | El sistema deberá permitir producir o disponer de diferentes niveles de calidad para una transmisión cuando estos estén disponibles. |
| RF-028 Consultar calidades disponibles | Calidad | P2 / Futuro | El sistema deberá permitir al streamer consultar las calidades disponibles para su transmisión. |
| RF-029 Selección manual de calidad | Calidad | P2 / Futuro | El sistema deberá permitir al espectador seleccionar manualmente una calidad de reproducción disponible. |
| RF-030 Selección automática de calidad | Calidad | P2 / Futuro | El sistema deberá permitir al espectador utilizar un modo de selección automática de calidad. |
| RF-031 Sala de chat por sesión | Chat | P1 | El sistema debe ofrecer una sala de chat asociada a cada sesión LIVE. Durante la ventana de reconexión continúa disponible; al finalizar la sesión pasa a solo lectura. |
| RF-032 Envío autenticado de mensajes | Chat | P1 | El sistema debe permitir a usuarios autenticados enviar mensajes de texto NFC no vacío al chat de una sesión LIVE; el texto normalizado y recortado no supera 500 puntos de código Unicode. |
| RF-033 Distribución y cuota de mensajes | Chat | P1 | El sistema debe distribuir los mensajes a los espectadores conectados con latencia inferior a 1 segundo en el 95 % de los mensajes bajo la carga objetivo. Cada cuenta puede enviar como máximo un mensaje en cualquier ventana móvil de 1000 ms, compartida entre todas las salas y sin ráfaga acumulada. |
| RF-034 Identidad visible e historial de chat | Chat | P1 | El sistema debe mostrar el nombre visible del autor y el contenido seguro de cada mensaje. El historial inicial para quien llega tarde muestra hasta los últimos 50 mensajes disponibles. |
| RF-035 Rechazo en chat no disponible | Chat | P1 | El sistema debe rechazar el envío cuando la sala esté cerrada o no disponible e informar claramente al usuario. |
| RF-036 Eliminar mensajes de chat | Chat | P2 / Futuro | El sistema deberá permitir al propietario del canal eliminar mensajes publicados en el chat de su transmisión. |
| RF-037 Bloquear usuarios en chat | Chat | P2 / Futuro | El sistema deberá permitir al propietario del canal bloquear a un usuario para impedir que publique nuevos mensajes en su chat. |
| RF-038 Consultar opciones de suscripción | Suscripciones | Futuro | El sistema deberá permitir a un usuario consultar las opciones de suscripción de pago disponibles para un canal. |
| RF-039 Registrar suscripción | Suscripciones | Futuro | El sistema deberá permitir registrar una suscripción de pago de un usuario a un canal. |
| RF-040 Estado de suscripción | Suscripciones | Futuro | El sistema deberá mantener el estado de una suscripción, incluyendo si se encuentra activa o inactiva. |
| RF-041 Consultar suscripciones activas | Suscripciones | Futuro | El sistema deberá permitir a un usuario consultar sus suscripciones activas. |
| RF-042 Identificar acceso premium | Premium | Futuro | El sistema deberá identificar si un usuario tiene acceso a las características premium asociadas a una transmisión o canal. |
| RF-043 Restringir funciones premium | Premium | Futuro | El sistema deberá restringir las funcionalidades premium a los usuarios que cumplan las condiciones de acceso correspondientes. |
| RF-044 Definir requisitos premium | Premium | Futuro | El sistema deberá permitir al streamer definir qué funcionalidades o contenido de su canal requieren una suscripción activa. |
| RF-045 Mostrar contenido exclusivo premium | Premium | Futuro | El sistema deberá permitir mostrar elementos exclusivos dentro de una transmisión a los usuarios con acceso premium. |
| RF-046 Crear watch party | Watch Party | Futuro | El sistema deberá permitir a un usuario autorizado crear una sesión de visualización conjunta. |
| RF-047 Agregar streams a watch party | Watch Party | Futuro | El sistema deberá permitir agregar varias transmisiones activas a una sesión de visualización conjunta. |
| RF-048 Retirar streams de watch party | Watch Party | Futuro | El sistema deberá permitir retirar una transmisión de una sesión de visualización conjunta. |
| RF-049 Reproducir streams simultáneamente | Watch Party | Futuro | El sistema deberá mostrar simultáneamente las transmisiones asociadas a una sesión de visualización conjunta. |
| RF-050 Acceder a watch party | Watch Party | Futuro | El sistema deberá permitir a otros usuarios acceder a una sesión de visualización conjunta. |
| RF-051 Mostrar canales de watch party | Watch Party | Futuro | El sistema deberá mostrar información de cada uno de los canales incluidos en una sesión de visualización conjunta. |
| RF-052 Notificar inicio de stream | Notificaciones | Futuro | El sistema deberá generar una notificación para los seguidores de un canal cuando este inicie una transmisión. |
| RF-053 Consultar notificaciones | Notificaciones | Futuro | El sistema deberá permitir a un usuario consultar sus notificaciones. |
| RF-054 Marcar notificación leída | Notificaciones | Futuro | El sistema deberá permitir marcar una notificación como leída. |
| RF-055 Configurar notificaciones | Notificaciones | Futuro | El sistema deberá permitir a un usuario configurar si desea recibir notificaciones de los canales que sigue. |
| RF-056 Guardar emisión como VOD | VOD | P2 / Futuro | El sistema deberá permitir almacenar una transmisión finalizada para su posterior consumo bajo demanda. |
| RF-057 Asociar VOD a canal y emisión | VOD | P2 / Futuro | El sistema deberá asociar el contenido almacenado con el canal y la transmisión de origen. |
| RF-058 Consultar catálogo VOD | VOD | P2 / Futuro | El sistema deberá permitir consultar el catálogo de transmisiones almacenadas de un canal. |
| RF-059 Reproducir VOD | VOD | P2 / Futuro | El sistema deberá permitir reproducir una transmisión almacenada. |
| RF-060 Editar metadatos VOD | VOD | P2 / Futuro | El sistema deberá permitir al propietario del canal modificar la información de una transmisión almacenada. |
| RF-061 Eliminar VOD | VOD | P2 / Futuro | El sistema deberá permitir al propietario del canal eliminar una transmisión almacenada. |
| RF-062 Mostrar duración y metadatos VOD | VOD | P2 / Futuro | El sistema deberá mostrar la duración y los metadatos asociados al contenido bajo demanda. |
| RF-063 Asociar subtítulos | Accesibilidad | P2 / Futuro | El sistema deberá permitir asociar pistas de subtítulos a una transmisión o contenido almacenado. |
| RF-064 Activar o desactivar subtítulos | Accesibilidad | P2 / Futuro | El sistema deberá permitir al espectador activar o desactivar los subtítulos disponibles. |
| RF-065 Seleccionar pista de subtítulos | Accesibilidad | P2 / Futuro | El sistema deberá permitir al espectador seleccionar entre las pistas de subtítulos disponibles. |
| RF-066 Asociar categoría activa | Categorías | P1 | El sistema debe asociar cada transmisión con exactamente una categoría activa del catálogo controlado. |
| RF-067 Asociar etiquetas activas | Categorías | P1 | El sistema debe asociar de cero a cinco etiquetas activas del catálogo controlado a cada transmisión. |
| RF-068 Consultar streams por categoría | Categorías | P1 | El sistema debe permitir consultar transmisiones LIVE por categoría. |
| RF-069 Consultar streams por etiqueta | Categorías | P1 | El sistema debe permitir consultar transmisiones LIVE por una etiqueta seleccionada mediante su ID controlado; la consulta puede combinar un categoryId y un tagId con AND. |
| RF-070 Listar streams por popularidad | Descubrimiento | P1 | El sistema debe mostrar transmisiones LIVE en orden descendente de espectadores concurrentes. |
| RF-071 Buscar canales públicos | Descubrimiento | P1 | El sistema debe buscar canales públicos, estén LIVE u OFFLINE, por coincidencia parcial en handle o nombre visible, sin distinguir mayúsculas y minúsculas; el resultado indica su estado actual. |
| RF-072 Buscar streams por título | Descubrimiento | P1 | El sistema debe buscar transmisiones LIVE reproducibles por coincidencia parcial en su título. |
| RF-073 Filtrar streams LIVE | Descubrimiento | P1 | El sistema debe filtrar transmisiones LIVE reproducibles por categoryId o tagId controlado; permite como máximo un ID de cada tipo y combina ambos con AND. |
| RF-074 Buscar VOD por título | VOD / Descubrimiento | P2 / Futuro | El sistema deberá buscar contenido VOD disponible por coincidencia parcial en su título. |
| RF-075 Filtrar VOD por categoría o etiqueta | VOD / Descubrimiento | P2 / Futuro | El sistema deberá filtrar contenido VOD disponible por categoría o etiqueta seleccionada. |
| RF-076 Consultar usuarios como administrador | Administración | Futuro | El sistema deberá permitir a un administrador consultar usuarios registrados. |
| RF-077 Consultar canales como administrador | Administración | Futuro | El sistema deberá permitir a un administrador consultar canales registrados. |
| RF-078 Retirar contenido infractor | Administración | Futuro | El sistema deberá permitir a un administrador retirar contenido que incumpla las reglas establecidas por la plataforma. |
| RF-079 Suspender o reactivar cuentas | Administración | Futuro | El sistema deberá permitir a un administrador suspender o reactivar una cuenta. |

## Requisitos no funcionales

| ID | Categoría | Aplicabilidad | Requisito |
| --- | --- | --- | --- |
| RNF-001 | Arquitectura | P1 sistema o componente aplicable | El sistema deberá seguir una **arquitectura distribuida**, en la que existan componentes ejecutables independientes que se comuniquen mediante conectores de red. |
| RNF-002 | Arquitectura | P1 sistema o componente aplicable | El sistema deberá incluir al menos **un componente de presentación de tipo frontend web**. |
| RNF-003 | Arquitectura | P1 sistema o componente aplicable | El sistema deberá incluir al menos **dos componentes de lógica** desplegables como procesos independientes. |
| RNF-004 | Datos | P1 sistema o componente aplicable | El sistema deberá utilizar al menos **un componente de almacenamiento relacional**. |
| RNF-005 | Datos | P1 sistema o componente aplicable | El sistema deberá utilizar al menos **un componente de almacenamiento NoSQL**. |
| RNF-006 | Conectividad | P1 sistema o componente aplicable | El sistema deberá utilizar al menos **dos tipos diferentes de conectores basados en HTTP** para la comunicación entre sus componentes. |
| RNF-007 | Tecnología | P1 sistema o componente aplicable | La implementación deberá utilizar al menos **tres lenguajes de programación de propósito general diferentes**. |
| RNF-008 | Despliegue | P1 sistema o componente aplicable | Los componentes desplegables del sistema deberán ejecutarse mediante **contenedores**. |
| RNF-009 | Despliegue | P1 sistema o componente aplicable | Cada componente lógico deberá poder iniciarse, detenerse y reiniciarse de forma independiente de los demás componentes lógicos. |
| RNF-010 | Portabilidad | P1 sistema o componente aplicable | El entorno completo del prototipo deberá poder desplegarse a partir de instrucciones reproducibles sin depender de configuración manual específica de la máquina del desarrollador. |
| RNF-011 | Rendimiento | P1 sistema o componente aplicable | Bajo el perfil integrado P1 de SPEC-13, la aplicación procesará 10 solicitudes HTTP de API por segundo durante 10 minutos: 2 logins correctos, 3 consultas Discovery, 1 búsqueda de canal, 1 lectura de Taxonomy, 1 lectura de canal, 1 lectura de perfil y 1 lectura de estado de stream por segundo. Cada endpoint tendrá p95 ≤2 s. Se reportarán tasa de solicitudes, tamaño de muestra, latencia y fallos por endpoint; una operación válida con timeout o respuesta no exitosa reprueba el run, aunque el p95 de las respuestas 2xx cumpla. HLS, lease y heartbeat se reportan separados. |
| RNF-012 | Rendimiento | P1 sistema o componente aplicable | El inicio de reproducción de una transmisión deberá ocurrir en un máximo de **5 segundos** después de que el usuario solicite reproducirla, bajo condiciones normales de red y carga. |
| RNF-013 | Rendimiento | P1 sistema o componente aplicable | Los mensajes enviados al chat deberán ser visibles para los participantes conectados en un máximo de **1 segundo para el 95 % de los mensajes**, bajo la carga objetivo. |
| RNF-014 | Rendimiento | P1 sistema o componente aplicable | El cambio de estado de un canal entre `OFFLINE` y `LIVE` deberá reflejarse en las consultas del sistema en un máximo de **5 segundos**. |
| RNF-015 | Escalabilidad | P1 sistema o componente aplicable | El sistema deberá permitir incrementar la capacidad de los componentes de lógica sin requerir modificaciones al frontend ni a los demás componentes no afectados. |
| RNF-016 | Escalabilidad | P1 sistema o componente aplicable | Los componentes encargados de transmisión, aplicación y comunicación en tiempo real deberán poder escalar independientemente según su carga. |
| RNF-017 | Escalabilidad | P1 sistema o componente aplicable | El sistema deberá soportar al menos 5 transmisiones simultáneas y 100 reproducciones concurrentes totales en la plataforma durante las pruebas objetivo. |
| RNF-018 | Escalabilidad | P1 sistema o componente aplicable | El sistema deberá soportar al menos 20 mensajes de chat por segundo agregados entre las salas activas durante 10 minutos. Para cada cuenta autenticada, Chat aceptará como máximo un mensaje en cualquier ventana móvil de 1000 ms, sin ráfaga adicional y global entre salas. Se reportarán latencia, pérdidas, duplicados y fallos. |
| RNF-019 | Disponibilidad | P1 sistema o componente aplicable | Una falla del componente de chat no deberá impedir que los espectadores continúen consumiendo una transmisión que ya se encuentre disponible. |
| RNF-020 | Disponibilidad | Futuro (notificaciones) | Una falla temporal de un componente no esencial, como notificaciones, no deberá provocar la indisponibilidad total del sistema. |
| RNF-021 | Confiabilidad | P1 sistema o componente aplicable | El sistema deberá detectar la pérdida de fuente y finalizar una sesión si la fuente no regresa en 30 segundos. |
| RNF-022 | Confiabilidad | P1 sistema o componente aplicable | La información persistente de usuarios, canales y contenido almacenado no deberá perderse cuando un componente de lógica sea reiniciado. |
| RNF-023 | Tolerancia a fallos | P1 sistema o componente aplicable | Los componentes consumidores de otros servicios deberán manejar errores de comunicación, tiempos de espera y respuestas no disponibles sin finalizar abruptamente su ejecución. |
| RNF-024 | Consistencia | P1 sistema o componente aplicable | Las operaciones que modifiquen información persistente deberán mantener la integridad de los datos incluso ante fallos parciales durante su ejecución. |
| RNF-025 | Seguridad | P1 sistema o componente aplicable | Toda comunicación que transporte credenciales, tokens de autenticación o información privada deberá realizarse mediante **HTTPS/TLS** en los ambientes donde el sistema sea accesible por red. |
| RNF-026 | Seguridad | P1 sistema o componente aplicable | Las contraseñas de los usuarios no deberán almacenarse en texto plano y deberán mantenerse utilizando un mecanismo de hashing apropiado para contraseñas. |
| RNF-027 | Seguridad | P1 sistema o componente aplicable | El sistema deberá verificar la identidad y los permisos del usuario antes de ejecutar operaciones protegidas. |
| RNF-028 | Seguridad | P1 sistema o componente aplicable | Un usuario no deberá poder modificar canales, transmisiones o contenido perteneciente a otro usuario salvo que disponga explícitamente de permisos administrativos. |
| RNF-029 | Seguridad | P1 sistema o componente aplicable | Los mecanismos de autenticación deberán utilizar credenciales o tokens con expiración y deberán rechazar credenciales inválidas o expiradas. |
| RNF-030 | Seguridad | P1 sistema o componente aplicable | Las entradas proporcionadas por los usuarios deberán ser validadas antes de ser procesadas o almacenadas. |
| RNF-031 | Seguridad | P1 sistema o componente aplicable | Identity permitirá como máximo 5 intentos de login fallidos por identificador normalizado y ventana móvil de 15 minutos, y 50 intentos fallidos por IP en esa ventana; un login correcto reinicia el contador del identificador. El registro permitirá como máximo 10 claves de idempotencia nuevas por IP y ventana móvil de una hora. Chat permitirá como máximo un mensaje aceptado por cuenta en cualquier ventana móvil de 1000 ms, global entre salas y sin ráfaga adicional. Identity responde HTTP `429 RATE_LIMITED` con `Retry-After`; Chat, cuyo transporte de escritura es WebSocket, envía un frame `error` con `code=RATE_LIMITED` y `retryAfterMs`. No se bloquea permanentemente la cuenta. |
| RNF-032 | Privacidad | P1 sistema o componente aplicable | La información privada de un usuario no deberá exponerse mediante las interfaces públicas de usuarios o canales. |
| RNF-033 | Usabilidad | P1 sistema o componente aplicable | Las principales funciones de espectador, incluyendo descubrir una transmisión, abrir un canal y comenzar la reproducción, deberán estar disponibles desde una interfaz web sin requerir software adicional. |
| RNF-034 | Usabilidad | P1 sistema o componente aplicable | La interfaz deberá informar al usuario cuando una transmisión se encuentre fuera de línea, esté cargando o haya ocurrido un error de reproducción. |
| RNF-035 | Accesibilidad | P1 sistema o componente aplicable | La interfaz web deberá permitir operar todas las funciones esenciales de P1 mediante teclado, con orden de foco lógico y foco visible. |
| RNF-036 | Accesibilidad | P1 sistema o componente aplicable | Los controles esenciales de P1 deberán exponer nombre, rol y estado accesibles; texto y controles deberán mantener contraste legible. |
| RNF-037 | Compatibilidad | P1 sistema o componente aplicable | La aplicación web deberá funcionar correctamente en las versiones estables actuales de al menos Chrome y Firefox. |
| RNF-038 | Evolutividad | P1 sistema o componente aplicable | Los componentes de lógica deberán exponer interfaces claramente definidas de forma que puedan ser reemplazados o modificados sin exigir cambios internos en sus consumidores. |
| RNF-039 | Evolutividad | P1 sistema o componente aplicable | La incorporación de nuevas categorías o etiquetas no deberá requerir modificaciones del código fuente del cliente. |
| RNF-040 | Evolutividad | P1 diseño / Futuro funcional | La arquitectura deberá permitir incorporar posteriormente funcionalidades como VOD, notificaciones, watch parties y características premium sin modificar sustancialmente los componentes existentes no relacionados. |
| RNF-041 | Mantenibilidad | P1 sistema o componente aplicable | Cada componente deberá mantener separadas sus responsabilidades principales y evitar el acceso directo a los datos internos pertenecientes a otro componente lógico. |
| RNF-042 | Mantenibilidad | P1 sistema o componente aplicable | Las interfaces entre componentes deberán estar documentadas, incluyendo operaciones, datos intercambiados y posibles errores. |
| RNF-043 | Observabilidad | P1 sistema o componente aplicable | Cada componente lógico deberá producir registros que permitan identificar al menos el momento, tipo de evento, componente de origen y resultado de las operaciones relevantes. |
| RNF-044 | Observabilidad | P1 sistema o componente aplicable | Los errores de los componentes distribuidos deberán registrarse con suficiente información para identificar el servicio y la operación que los produjo. |
| RNF-045 | Observabilidad | P1 sistema o componente aplicable | El sistema deberá permitir consultar el estado operativo de sus principales componentes mediante mecanismos de verificación de salud. |
| RNF-046 | Interoperabilidad | P1 sistema o componente aplicable | Los datos intercambiados entre componentes mediante interfaces HTTP deberán utilizar formatos documentados e independientes del lenguaje de implementación. |
| RNF-047 | Interoperabilidad | P1 sistema o componente aplicable | Los servicios no deberán depender de bibliotecas internas del lenguaje de otro componente para poder comunicarse con él. |
| RNF-048 | Datos | P1 sistema o componente aplicable | Las relaciones que requieran integridad referencial, como usuarios, canales y transmisiones, deberán almacenarse utilizando el componente relacional cuando corresponda. |
| RNF-049 | Datos | P1 sistema o componente aplicable | La utilización del almacenamiento NoSQL deberá corresponder a información cuya forma de acceso o naturaleza justifique dicho modelo, como estado efímero de transmisiones, sesiones o información en tiempo real. |
| RNF-050 | Recuperación | P1 sistema o componente aplicable | El sistema deberá permitir reconstruir su estado persistente después del reinicio de los componentes sin requerir la creación manual nuevamente de usuarios, canales o contenido previamente almacenado. |

## Catálogo de datos inicial aprobado

### Categorías

Conversación; Videojuegos; Música; Arte; Educación; Ciencia y tecnología; Deportes.

### Etiquetas

Español; Inglés; Educativo; Competitivo; Casual; Principiantes; Programación; IRL.

El catálogo inicial se entrega como datos controlados y está disponible por contrato de lectura. P1 no incluye interfaz de administración para que usuarios o administradores agreguen categorías o etiquetas. Para cumplir RNF-039, incorporar una categoría o etiqueta al catálogo en el backend (por configuración o almacenamiento elegido por el responsable) no puede requerir cambios al código fuente ni reconstrucción del cliente web; el cliente obtiene el catálogo actualizado mediante el contrato de lectura. La semilla inicial no impide esa capacidad y no convierte el alta de valores en una operación P1 de usuario.

## Parámetros de imágenes de perfil/canal

Regla acordada para el prototipo: avatar e imagen de portada opcionales; JPEG, PNG o GIF; máximo 10 MB por imagen; avatar de al menos 200×200 px; imagen de portada recomendada de 1200×480 px. Si falta una imagen, se muestra un recurso predeterminado. Los límites y el comportamiento están descritos aquí para que no sea necesario consultar otra especificación.

## Decisiones transversales relacionadas

- Ingesta RTMP; salida reproducible HLS. El dueño de Streaming selecciona y justifica la infraestructura multimedia en su ADR.
- Una sola sesión activa por canal y máximo cinco en la plataforma. LIVE se publica al confirmar medio reproducible.
- La pérdida de fuente conserva la sesión y el chat durante 30 segundos; después el estado termina y una reconexión inicia una sesión nueva.
- Espectadores pueden mirar sin login; enviar chat requiere cuenta autenticada.
- Chat Replay se habilita en una fase VOD futura. P1 persiste eventos con autor, sesión, contenido, timestamp de servidor y posición relativa al medio; no implementa la reproducción VOD.
- Descubrimiento P1 busca canales y títulos con coincidencias parciales, aplica filtros de categoría/etiquetas solo a contenido LIVE y ordena por espectadores descendentes.
- El objetivo de carga se mide durante 10 minutos: 5 streams, 100 espectadores concurrentes totales y 20 mensajes por segundo agregados.
- P1 incluye operación por teclado, semántica para tecnologías de asistencia, foco visible, contraste y control de autoscroll del chat. Subtítulos quedan fuera; no se declara conformidad global WCAG 2.2 AA.

## Fases fuera de P1

Las capacidades de seguimiento, calidad/transcoding, moderación, suscripciones y premium, watch parties, notificaciones, VOD, subtítulos y administración se conservan en esta versión para fases posteriores. Las decisiones de retención de VOD/chat, pagos, roles administrativos, políticas de moderación, pistas y escalamiento se deben registrar en esta documentación antes de implementar esas capacidades.
