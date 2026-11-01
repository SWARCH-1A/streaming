# SPEC-16 — API Gateway y agregación por pantalla

**Prioridad:** P2. **Responsable:** Gateway. **Estado:** implementado; aceptación local (ver §10), móvil y carga pendientes.
**RF:** RF-005, 010…016, 023…025, 053, 058…059, 070…079. **RNF:** RNF-002…003, 006, 011…016, 019…020, 023, 025…032, 038, 041…047.

## 1. Contexto y problema

Web SSR y móvil necesitan datos de varios servicios para una pantalla. La composición debe
preservar el contenido disponible y distinguir fallo, ausencia y acceso denegado.

## 2. Definición del componente

Unidad desplegable de entrada HTTPS, enrutamiento, agregación HTTP y retransmisión WS/HLS.
Renderizar HTML pertenece a Web; permisos y escrituras pertenecen a los dominios. El lenguaje,
framework y librería de proxy se eligen por su responsable.

## 3. Historia de usuario

Como espectador, quiero abrir un canal con una respuesta compuesta y seguir viendo el video
si una sección secundaria falla.

## 4. Alcance

Agregados de exploración, canal, live y VOD; rutas de comandos, correlación, límites, timeouts,
HTTPS, WebSocket y manifiestos/segmentos anidados. La administración se enruta al dueño de la
operación. El Gateway no mantiene una base de negocio ni duplica el estado autoritativo.

## 5. Requisitos funcionales

- Conserva identidad y propiedad al reenviar comandos; no acepta cabeceras privadas del público.
- Compone secciones de canal/perfil, emisión, biblioteca y seguimiento según la pantalla.
- Mantiene disponibles APIs individuales necesarias para paginación, comandos, WS y HLS.
- Conserva la indisponibilidad de cuentas/contenido suspendidos incluso por URL directa.

## 6. Criterios de aceptación

- [x] CA-01: `/api/screens/channels/{handle}` obtiene canal, live, VOD y seguimiento con una petición del cliente; las consultas internas independientes se ejecutan concurrentemente.
- [x] CA-02: VOD caído conserva canal/live con sección `UNAVAILABLE`; lista vacía confirmada usa `EMPTY`. Un canal esencial inexistente da 404 y un fallo al resolverlo, 503.
- [x] CA-03: una credencial inválida da 401; un permiso no comprobable da 503, sin convertir al solicitante en visitante para ejecutar una acción.
- [x] CA-04: WS conserva Upgrade, cierre y backpressure; HLS conserva MIME, Range/206, subdirectorios y cancelación sin cargar todo el video en memoria.
- [x] CA-05: ninguna ruta `/internal/*` ni cabecera de servicio suministrada por cliente atraviesa la frontera pública.
- [x] CA-06: respuestas privadas o dependientes de sesión usan `no-store`; dos solicitudes SSR concurrentes no comparten identidad.
- [x] CA-07: timeouts/cancelaciones liberan consultas internas; un comando no se reintenta sin idempotencia.

## 7. Diseño técnico y datos

`contracts/p2.json` define `P2*Screen`, `P2ScreenMeta` y secciones discriminadas por estado.
`meta.partial` indica al menos una sección `UNAVAILABLE`; `DENIED` para seguimiento anónimo no
es una caída. No incluir datos junto a un error. Los datos esenciales de watch/VOD se resuelven
antes de anunciar un recurso reproducible.

Los cuatro agregados tienen contrato concreto. Las demás pantallas usan las APIs de su dueño
hasta necesitar un agregado documentado. No se incluye historial de chat completo ni video en
los agregados. Las lecturas HTTP tienen presupuesto total acotado; WS/HLS usan plazos específicos
de conexión/inactividad. Los límites elegidos deben mantener RNF-011/014 y medirse con carga.

Se elimina la confianza en headers públicos `X-Service-*` y `X-Session-Credential`, se reconstruye
IP observada y se propaga requestId. La sesión recibida se conserva solo durante su solicitud.

Caddy es la única entrada pública: TLS, enrutamiento, WS/HLS y supresión de headers privados. El
Gateway recibe `/api/screens/*` y la retirada administrativa; copia solo `stream_session`,
Authorization (hacia Core/VOD), requestId válido y la dirección del cliente. Esa dirección se toma
de X-Forwarded-For solo si el par es un proxy de `GATEWAY_TRUSTED_PROXIES`; Core confía únicamente
en las IP fijas de Caddy y del Gateway. En `watch`, emisión y sesión son esenciales y coherentes; las
rendiciones se piden solo si ambas están PLAYABLE; un canal confirmado inexistente o suspendido
oculta la pantalla con 404. Como todas sus secciones son públicas y Core solo introspecta Bearer en
rutas públicas, una credencial presentada se comprueba en paralelo con `GET /api/profile/me`; si
Core la rechaza, la pantalla da 401 (CA-03).

## 8. Dependencias y contratos de integración

| Contrato | Proveedor | Fallo |
| --- | --- | --- |
| `p2.gateway.*` | Gateway | 200 compuesto, 401/403/404 o 503/504 del recurso esencial |
| APIs Core/Streaming/Chat/VOD/Notificaciones | Dueño correspondiente | Error por sección o rechazo del comando |
| WS y media | Chat / Media / VOD | Retransmisión de protocolo, no envoltorio de agregado |
| Contexto de acceso | Core | No conceder permiso si no se comprueba |

Firmas y tipos: [contratos P2](../../contracts/README.md) y [contratos HTTP](../contratos_p2_http.md).

## 9. Decisiones y preguntas abiertas

P2-06 mantiene producto, TLS, límites y presupuestos concretos a cargo de Gateway. Los agregados
de esta SPEC son el diseño compartido para sus consumidores. P2-17 cierra propagación de suspensión;
P2-18, su clasificación académica como componente lógico.

## 10. Verificación

Probar respuestas completas/parciales, timeout individual, error de identidad, dos sesiones
simultáneas, headers falsificados y rutas internas. Ejecutar una transmisión real y un WS por
Gateway, seek/Range de VOD y cancelación de cliente. Medir agregado y tiempo hasta la interfaz;
los schemas por sí solos no acreditan proxy de streaming correcto.

Evidencia: `services/gateway/main_test.go` (fallos parciales y esenciales, concurrencia, identidades,
401, headers privados, proxies de confianza, presupuesto 504 con cancelación, desconexión del
cliente, cuerpos acotados y retirada sin reintento); sus respuestas se validan contra
`contracts/p2.json` en el workflow Gateway. [Borde](../../tests/integration/p2-gateway-edge/run.py):
Caddyfile real con upstreams simulados para rutas, `/internal`, headers falsificados, Range/206,
subrutas VOD, streaming con cancelación, Upgrade/cierre y backpressure WS. [Stack local](../../tests/integration/p2-gateway/run.py):
fuente RTMPS real, pantallas canal/watch/explore, HLS live, Range de VOD publicado, WS de chat,
credencial revocada y 20 pantallas concurrentes; la latencia se informa por pantalla. HLS live no
publica byte ranges: Media entrega segmentos completos y el borde los retransmite sin cambios.
Pendiente: loader SSR de `/watch` (SPEC-18), consumo móvil (SPEC-19), carga sostenida para RNF-011/014 y retiradas no VOD (P2-17).

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. El riesgo principal es introducir una caída común o perder semántica de WS/HLS.
La concurrencia acotada y los límites por dependencia evitan que una sección agote la entrada.
