# Decisiones pendientes de P2

Las políticas de producto aceptadas están en [alcance P2](alcance_p2.md). Cada responsable resuelve
las preguntas de su componente antes de fijar la interfaz afectada y registra su tecnología en
un ADR. Las fechas de trabajo se organizan en Plane; el cierre de código es el 30 de octubre.

| ID | Responsable | Pregunta o resultado necesario | Momento de cierre |
| --- | --- | --- | --- |
| P2-01 | Watch parties | ¿Las salas son públicas, privadas o accesibles por enlace? ¿Quién puede entrar, crear y modificar? | Antes de implementar permisos y acceso |
| P2-02 | Watch parties | ¿Cuántas emisiones admite una sala, cómo se disponen y qué ocurre al finalizar una fuente o salir el creador? | Antes de implementar sala y reproductores |
| P2-03 | Watch parties | ¿Se requiere sincronización entre participantes, chat propio, invitaciones o mezcla audiovisual? ¿Cuál es el ciclo de vida y cierre de la sala? RF-046…051 exigen varios streams visibles, no fijan esas extensiones. | Antes de cerrar la SPEC funcional |
| P2-04 | Subtítulos / accesibilidad | Definido por alcance: solo VOD, archivos preparados por propietario; rutas VOD en SPEC-22. Proveedor/permiso real pendiente de VOD. | Revisión de interfaces antes de integrar |
| P2-05 | Subtítulos / accesibilidad | Perfil propuesto p2-webvtt-text-v1, carga manual UTF-8; formato, límites, idioma y mapping definidos en SPEC-22/ADR-018 y comprobados offline. Falta ratificación y reproducción real. | Antes de aceptar controles del player |
| P2-06 | Gateway | Elegir lenguaje/framework, proxy HTTP/WS/HLS, terminación TLS y límites. Ratificar agregados y errores parciales de SPEC-16/contratos P2; fijar límites y presupuestos concretos. | Antes de integrar SSR y móvil |
| P2-07 | Mensajería / integración | RabbitMQ/AMQP, clientes, topología y parámetros seleccionados en [ADR-016](adr/ADR-016-rabbitmq-eventos-p2.md). Integración inicial de ciclo/Discovery; recuperación, ACL y cobertura completa de SPEC-17 por acreditar. | Antes de publicar eventos compartidos |
| P2-08 | Streaming / Media | Resolver empaquetado HLS de hasta cuatro rendiciones codificadas en origen. Probar OBS → ingesta → manifiesto maestro → selección manual/automática, keyframes, discontinuidades y VOD. | Antes de dar por válida la estrategia multicalidad |
| P2-09 | VOD | Elegir runtime, motor de metadatos y proveedor S3; definir identidad de objeto, escritor de segmentos, permisos y transferencia de responsabilidad tras carga confirmada. Seleccionar proveedor S3 local; MinIO y RustFS son candidatos. | Antes de integrar grabación |
| P2-10 | Chat / VOD | Elegir persistencia durable y archivo sin pérdida de mensajes confirmados. Definir mapping entre tiempo de sesión y video, tratamiento de huecos de reconexión, paginación/seek y limpieza coordinada. | Antes de habilitar replay |
| P2-11 | Chat | Concretar formato/límites de duración de bloqueos, modificación concurrente y alcance/selección del borrado de mensajes por usuario. Bloqueo temporal/permanente y borrado opcional son capacidades obligatorias. | Antes de publicar contratos de moderación |
| P2-12 | Notificaciones | Elegir runtime/almacén y concretar deduplicación de intentos, preferencias, vencimiento de avisos atrasados y tratamiento de dispositivos inválidos. | Antes de integrar entrega por medios |
| P2-13 | Notificaciones / móvil | Provisionar proveedor de push, registro de dispositivos y configuración Android/iOS; SMTP está disponible. Verificar qué plataformas pueden probarse realmente. | Antes de acreditar push y cobertura iOS |
| P2-14 | Cuentas / móvil / Chat | Implementar/verificar login Bearer y handshake WS nativo definidos en SPEC-01/19 y contratos P2; elegir adaptadores seguros. Conservar cookie/CSRF para web y revocación común. | Antes de integrar autenticación móvil |
| P2-15 | Cuentas | Definir tokens de recuperación, uso único, expiración, cuotas y entrega SMTP sin exponer existencia de cuentas. | Antes de implementar RF-004 |
| P2-16 | Canales | Ratificar contratos p2.channels.*, snapshot/paginación de destinatarios, vencimiento y política de canales indisponibles con Notificaciones. | Antes de integrar seguimiento y avisos |
| P2-17 | Administración / Core / Gateway / Streaming / VOD | Definir propagación de suspensión, interrupción de reproducción y autorización ante dependencias caídas. Canal, VOD, pistas y replay deben quedar indisponibles; las URLs de objetos no pueden eludirlo. | Antes de cerrar acceso público y RF-079 |
| P2-18 | Integración | Justificar seis componentes de lógica y cuatro de datos con inventario de ejecución. Validar la clasificación del Gateway de agregación como lógica además de comunicación; si la clasificación docente lo excluye, resolver la sexta unidad lógica. No contar un broker como BD de negocio ni módulos Core como procesos separados. | Antes de fijar el diagrama de despliegue |

## Límite de la definición

Las políticas de watch parties quedan a cargo de sus responsables; Subtítulos tiene alcance VOD
y contrato propuesto en SPEC-22, pendiente de integración y revisión de consumidores. Las tecnologías
de Gateway/VOD/Notificaciones y el almacenamiento de metadatos siguen pendientes. RabbitMQ
está seleccionado para el broker por ADR-016. La independencia y propiedad de datos de VOD sí están definidas.

La aceptación de una interfaz requiere esquema, permisos, fallos, idempotencia y consumidor
identificados. Una dependencia de provisión o una prueba pendiente debe permanecer visible;
no se presenta como una función ejecutada.
