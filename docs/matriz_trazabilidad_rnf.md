# Matriz de trazabilidad y cierre de RNF

Esta matriz asigna un único SPEC primario a cada requisito no funcional, identifica contribuyentes y
explica la evidencia que cierra el requisito. El SPEC primario responde por el resultado integrado; los
contribuyentes demuestran su parte en su dominio. La matriz no transfiere la propiedad de datos.

Los IDs y textos normativos completos están en [Catálogo de requisitos](catalogo_requisitos.md).
Las evidencias se ejecutarán al implementar P1; no son afirmaciones de que ya exista software o una
prueba ejecutada.

| RNF canónico | Aplicabilidad | SPEC primario | SPEC contribuyentes | Evidencia de cierre |
| --- | --- | --- | --- | --- |
| RNF-001 | P1 | SPEC-09 | SPEC-01, SPEC-03…SPEC-08 y SPEC-10…SPEC-13 | C&C/despliegue identifica procesos independientes, conectores y límites; SPEC-13 ejecuta Core, Streaming y Chat como procesos propios separados. |
| RNF-002 | P1 | SPEC-12 | SPEC-08 | Shell web y navegación principal aparecen en el diagrama/rutas e2e; el prototipo se usa desde Chrome/Firefox sin cliente adicional. |
| RNF-003 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Inventario demuestra al menos dos procesos de lógica desplegables, con health y reinicio individual. |
| RNF-004 | P1 | SPEC-13 | SPEC-01, SPEC-03, SPEC-04, SPEC-05, SPEC-06 | Artefacto ejecutable usa SQL para relaciones persistentes que lo requieren; ADR enlaza esquema, dueño y consultas reales. |
| RNF-005 | P1 | SPEC-13 | SPEC-04, SPEC-05 | Artefacto usa NoSQL para un acceso/estado justificado; ADR y evidencia descartan uso ceremonial. |
| RNF-006 | P1 | SPEC-13 | SPEC-10 e SPEC-12 | E2E demuestra REST, GraphQL sobre HTTP/JSON y WebSocket Upgrade con request, error y timeout; registrar qué dos patrones reconoce la guía/docente antes de cerrar el RNF. RTMP/HLS no sustituyen esa evidencia. |
| RNF-007 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Inventario de procesos y artefactos ejecutables confirma tres lenguajes generales; markup, configuración y SQL no cuentan. |
| RNF-008 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Arranque local muestra cada componente desplegable en contenedor y su health. |
| RNF-009 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Reiniciar, detener e iniciar un proceso por separado no apaga los demás; se observa su readiness al volver. |
| RNF-010 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Integrante distinto ejecuta runbook desde checkout limpio con env de ejemplo y sin pasos manuales omitidos. |
| RNF-011 | P1 | SPEC-13 | SPEC-01, SPEC-03, SPEC-06, SPEC-07 | Durante 10 min, el perfil integrado ejecuta 10 solicitudes de API/s totales: 2 login correctos, 3 Discovery, 1 búsqueda de canal, 1 Taxonomy, 1 lectura de canal, 1 perfil y 1 estado de stream. Cada endpoint reporta su muestra y p95 ≤2 s; toda operación válida fallida reprueba el run aunque los p95 de respuestas 2xx cumplan. |
| RNF-012 | P1 | SPEC-04 | SPEC-12 e SPEC-13 | Solicitud del player→primer frame visible ≤5 s bajo perfil de red/carga P1 registrado; es máximo, no p95. |
| RNF-013 | P1 | SPEC-05 | SPEC-12 e SPEC-13 | Mensaje aceptado→entrega a clientes conectados p95 <1 s bajo 20 msg/s agregados (cumple RF-033 y RNF-013 ≤1 s); se mide con relojes de servidor correlacionados. |
| RNF-014 | P1 | SPEC-03 | SPEC-04 e SPEC-11 | Commit Streaming de cambio de disponibilidad→proyección Discovery/consulta pública ≤5 s; estados PLAYABLE/RECONNECTING/OFFLINE diferenciados. |
| RNF-015 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-10…SPEC-12 | Escalar una instancia/capacidad del servicio seleccionado conserva schema/path y no requiere cambios de código/configuración en frontend ni consumidores ajenos. |
| RNF-016 | P1 | SPEC-13 | SPEC-04, SPEC-05, SPEC-12 | Prueba demuestra capacidad independiente de Chat, Core, Streaming y Media sin modificar schemas/paths/consumidores; módulos Core se escalan juntos. Documentar orden/fan-out/cuotas de Chat y SQL/volumen Core y fencing/clock/owner Streaming antes de varias réplicas. En P1 el adaptador escala/reinicia junto a Streaming; el motor MediaMTX conserva contenedor propio según ADR-011. |
| RNF-017 | P1 | SPEC-04 | SPEC-13 | Cinco fuentes simultáneas y 100 reproducciones concurrentes totales durante 10 min; el sexto stream recibe rechazo controlado. |
| RNF-018 | P1 | SPEC-05 | SPEC-13 | 20 mensajes/s agregados entre salas durante 10 min; cada cuenta puede enviar como máximo un mensaje aceptado en cualquier ventana móvil de 1000 ms, sin burst allowance. Se documentan pérdidas, duplicados, error rate y latencia. |
| RNF-019 | P1 | SPEC-05 | SPEC-04, SPEC-12, SPEC-13 | Se detiene Chat durante playback y se verifica que HLS continúa; UI muestra chat no disponible y recupera lectura. |
| RNF-020 | Futuro | Sin SPEC P1 | Notificaciones junto a Core inicialmente | Requisito preservado; se asigna al plan de Notificaciones cuando se priorice. |
| RNF-021 | P1 | SPEC-04 | SPEC-11 | Pérdida de fuente a 29/30/31 s, stop y callback duplicado muestran transición única y finalización al vencer 30 s. |
| RNF-022 | P1 | SPEC-13 | SPEC-01, SPEC-03, SPEC-04, SPEC-05, SPEC-06 | Crear cuenta, canal, metadata de stream y eventos persistentes; reiniciar sus procesos; leer los mismos IDs/contenidos sin recreación manual ni pérdida. |
| RNF-023 | P1 | SPEC-11 | SPEC-01, SPEC-03…SPEC-07 y SPEC-10…SPEC-13 | Inyectar timeout, 5xx, respuesta tardía y dependencia no disponible; cada consumidor degrada/recupera sin abortar el proceso ni crear duplicados. |
| RNF-024 | P1 | SPEC-11 | SPEC-01, SPEC-03, SPEC-04, SPEC-05 | Fallo entre cuenta/perfil/canal hace rollback SQL total; retry tras perder respuesta conserva resultado idempotente. Efectos Media/Chat usan IDs durables, sin transacción cross-store. |
| RNF-025 | P1 | SPEC-12 | SPEC-01, SPEC-03…SPEC-07 y SPEC-10…SPEC-11 | Credenciales/tokens/datos privados viajan solo por HTTPS/TLS en red; configuración de proxy prueba terminación y forwarding seguros. |
| RNF-026 | P1 | SPEC-01 | SPEC-10, SPEC-13 | Revisión de persistencia y respuestas confirma hash de contraseña apropiado y ausencia de texto reversible/logs. |
| RNF-027 | P1 | SPEC-01 | SPEC-03, SPEC-04, SPEC-05 | Pruebas own/other/anonymous/expired verifican principal y permiso para cada escritura protegida. |
| RNF-028 | P1 | SPEC-01 | SPEC-03, SPEC-04, SPEC-05 | Usuario A no altera canal/stream/mensaje de B; principal, ownerId y resourceId se contrastan en el proveedor. |
| RNF-029 | P1 | SPEC-01 | SPEC-10 | Sesión/token tiene expiración y revocación; credenciales inválidas/expiradas fallan tanto REST como WebSocket. |
| RNF-030 | P1 | SPEC-10 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Schemas y pruebas rechazan entrada inválida antes de persistir/procesar; errores no filtran stack, SQL ni secretos. |
| RNF-031 | P1 | SPEC-01 | SPEC-10, SPEC-05 | Login: máximo 5 fallos por identificador normalizado y ventana móvil de 15 min, y 50 fallos por IP y 15 min; éxito reinicia el contador del identificador. Registro: máximo 10 claves de idempotencia nuevas por IP y ventana móvil de 1 h. Identity devuelve HTTP `429 RATE_LIMITED` con `Retry-After`; Chat aplica máximo 1 mensaje aceptado por cuenta en cualquier ventana móvil de 1000 ms, global entre salas, sin burst, y devuelve un frame WebSocket `error` con `code=RATE_LIMITED` y `retryAfterMs`. No hay bloqueo permanente de cuenta. |
| RNF-032 | P1 | SPEC-01 | SPEC-03, SPEC-04, SPEC-05, SPEC-07 | Matriz de payloads públicos demuestra ausencia de email, contraseña/hash, sesión privada y datos privados; test negativo por endpoint. |
| RNF-033 | P1 | SPEC-12 | SPEC-03, SPEC-07, SPEC-04, SPEC-08 | Recorrido web completo descubre, abre canal y comienza HLS sin instalar software adicional. |
| RNF-034 | P1 | SPEC-04 | SPEC-03, SPEC-12, SPEC-08 | Player informa OFFLINE, PREPARING/cargando, RECONNECTING y error HLS en estados distintos, con acción/mensaje visible. |
| RNF-035 | P1 | SPEC-08 | SPEC-01, SPEC-03, SPEC-04, SPEC-05, SPEC-07, SPEC-12 | Recorrido esencial completo por teclado; orden lógico, foco visible y cero bloqueo por mouse en rutas P1. |
| RNF-036 | P1 | SPEC-08 | SPEC-01, SPEC-03, SPEC-04, SPEC-05, SPEC-07, SPEC-12 | Inspección de nombre/rol/estado, foco y contraste para controles esenciales, incluidos player y composer. |
| RNF-037 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-08 y SPEC-12 | Recorrido E2E registrado en versiones estables de Chrome y Firefox disponibles en la fecha de prueba; anotar versiones y fallos. |
| RNF-038 | P1 | SPEC-10 | SPEC-01, SPEC-03…SPEC-08 y SPEC-11…SPEC-12 | Consumer valida schema público sin importar paquetes internos; cambio aditivo conserva contrato y una prueba detecta breaking change. |
| RNF-039 | P1 | SPEC-06 | SPEC-04, SPEC-07, SPEC-12 | Se añade valor controlado al backend, se incrementa versión del catálogo y un cliente sin cambio/rebuild obtiene el valor por API. |
| RNF-040 | P1 diseño | SPEC-09 | SPEC-10, SPEC-03, SPEC-04, SPEC-05, SPEC-07 | Vista de dependencias confirma límites sustituibles; VOD, notificaciones, watch party y premium pueden añadirse mediante contratos nuevos/extensiones sin cambio sustancial en componentes no relacionados. No exige implementar esas capacidades en P1. |
| RNF-041 | P1 | SPEC-09 | SPEC-01, SPEC-03…SPEC-07 | Revisión de dependencias/código y ADR confirma que cada dato tiene un dueño y cada módulo escribe mediante su repositorio; lectura SQL compuesta revisada/FK permitida dentro de Core; ningún servicio externo consulta su base. |
| RNF-042 | P1 | SPEC-10 | SPEC-01, SPEC-03…SPEC-08 y SPEC-11…SPEC-12 | Inventario de contrato enumera método/path o mensaje, actor, schema, auth, éxito/error, timeout, idempotencia y consumidor. |
| RNF-043 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Log estructurado de operación relevante contiene timestamp UTC, evento, componente y resultado; se verifica en recorrido E2E. |
| RNF-044 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-10…SPEC-12 | Fallo inyectado permite identificar servicio/operación/requestId sin imprimir password, token, email privado o stream key. |
| RNF-045 | P1 | SPEC-13 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Health/readiness distingue servicio listo, degradado y no disponible; prueba no se bloquea por health dependiente infinito. |
| RNF-046 | P1 | SPEC-10 | SPEC-01, SPEC-03…SPEC-07 y SPEC-12 | Schemas de request/response/evento son neutrales al lenguaje, serializables y validados por proveedor y consumidor. |
| RNF-047 | P1 | SPEC-10 | SPEC-01, SPEC-03…SPEC-08 y SPEC-11…SPEC-12 | Cliente consumidor compila/valida desde schema público; no importa modelo/SDK privado del lenguaje del proveedor. |
| RNF-048 | P1 | SPEC-13 | SPEC-01, SPEC-03, SPEC-04 | Dueños justifican relaciones con integridad en SQL; prueba valida FK cuenta-canal en Core y config-sesión/leases en Rust; referencias entre bases validadas por contexto Core, sin FK entre bases. |
| RNF-049 | P1 | SPEC-13 | SPEC-04, SPEC-05 | ADR vincula cada uso NoSQL a estado/acceso temporal o eventos; si no hay justificación, no se cuenta como cumplimiento. |
| RNF-050 | P1 | SPEC-13 | SPEC-01, SPEC-03, SPEC-04, SPEC-05 | Reiniciar procesos y consultar Core y Streaming desde sus SQL privados, reconstruir proyección Discovery con snapshot/watermark y recuperar Chat por backup/snapshot/eventos preserva IDs/estado sin volver a crear cuentas/canales/contenido manualmente. |

## Reglas de ownership de la evidencia

1. El SDD primario conserva el criterio verificable y entrega la evidencia (resultado, log/reporte o
   contrato probado) al SPEC-13.
2. SPEC-13 consolida los RNF de sistema: arquitectura, carga, disponibilidad integrada, reinicio,
   portabilidad, compatibilidad y tecnologías/conectores requeridos.
3. La cobertura de un contribuyente no significa que posea ni pueda cambiar el dato del dueño.
4. RNF marcados Futuro se preservan en el catálogo, pero no reciben criterio P1 ni se declaran cerrados.
