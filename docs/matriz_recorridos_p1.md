# Matriz de recorridos de integración P1

Esta definición desarrolla [SPEC-09 CA-08](spec-p1/spec_09_int.md) y complementa la
[matriz RNF](matriz_trazabilidad_rnf.md). No es un informe de pruebas ejecutadas. Los valores,
errores, ventanas y límites exactos son los del CA funcional enlazado y los
[contratos canónicos](contratos_modelo_datos.md); esta matriz no los redefine.

Cada fila es un escenario estable `I-<SPEC>-<CA>`. La flecha identifica consumidor y proveedor;
el último componente de cada salto responde por sus datos. Una lectura pública no concede permiso
de escritura. Los saltos entre procesos usan API, nunca SQL ajeno. Dentro de Core se usan interfaces
locales y su transacción. La columna Hijo asigna la responsabilidad primaria de integración:
10 valida contratos, 11 los cruces de dominios, 12 el recorrido Web y 13 entrega/carga/recuperación.
SPEC-10 aporta schemas a todas las filas y SPEC-13 consolida su evidencia, sin sustituir al dueño.

Para cerrar una fila, Plane debe identificar escenario, RF/CA/RNF, commit y configuración,
pasos/fixture, resultado esperado y observado, muestras y limitaciones. Debe distinguir contrato,
prueba local, recorrido integrado y revisión manual. Registrar pendientes y fallos, sin publicar
credenciales, DTO owner, URLs privadas ni logs sin depurar. Una fila con variantes faltantes permanece
abierta aunque haya pasado su caso nominal. Los fixtures SQL se limitan a datos ficticios propios.

## Cuentas y perfil — SPEC-01

Fuente: [SPEC-01](spec-p1/spec_01_auth.md). Proveedor dueño: Cuentas/Profile dentro de Core;
Canales participa en el registro local. Los recorridos de escritura incluyen owner/otro/anónimo,
CSRF y validación definidos en el contrato.

| Escenario / CA | RF | Hijo | Consumidor → proveedor y resultado exigido |
| --- | --- | --- | --- |
| I-01-01 / CA-01 | RF-001 | 11 | Web→Core: entradas válidas/límites inválidos de handle, email y contraseña; canonicalización y unicidad acordadas sin modificar la contraseña. |
| I-01-02 / CA-02 | RF-001 | 11 | Web→Core/Cuentas→Profile→Canales local: commit conjunto sin sesión; fallo entre escrituras revierte todo y respuesta perdida/retry conserva IDs. |
| I-01-03 / CA-03 | RF-001 | 11 | Web→Core: conflicto uniforme, misma clave/payload recupera resultado, payload diferente rechaza; retención de resultado verificable. |
| I-01-04 / CA-04 | RF-002, RF-003 | 12 | Web→Core→Streaming/Chat: cookie HTTPS con atributos, refresh y duración fija; logout revoca la siguiente autorización en cada consumidor. |
| I-01-05 / CA-05 | RF-005, RF-007 | 11 | Web/Streaming→Core: owner modifica recurso, otro/anónimo rechaza; IDs enviados por cliente no reemplazan el principal. |
| I-01-06 / CA-06 | RF-001, RF-002, RF-006 | 10 | Consumidores públicos→Core: payload y persistencia excluyen secretos; inexistente/no activo uniforme; SQL caído devuelve indisponibilidad. |
| I-01-07 / CA-07 | RF-001, RF-002 | 13 | Web→Core con dos réplicas: límites de login/registro, ventanas, Retry-After y retry de clave; cuota compartida y recuperación sin bloqueo permanente. |
| I-01-08 / CA-08 | RF-005 | 11 | Chat/Streaming→Core→Streaming: contexto vigente por operación, sin permisos cacheados; Core/Streaming caídos rechazan escritura sin persistencia. |
| I-01-09 / CA-09 | RF-001, RF-006 | 11 | Web→Core después del commit: perfil default inmediato con versión inicial, sin proyección ni activación remota. |
| I-01-10 / CA-10 | RF-007 | 11 | Web→Core: PATCH parcial, límites, null/omitido, vacío/no-op y cambio real; handle inmutable y versión correcta. |
| I-01-11 / CA-11 | RF-007 | 12 | Web→Core→almacén de imágenes: bytes JPEG/PNG/GIF reales, tamaño/dimensiones/propiedad/vencimiento/un uso; inválido conserva avatar. |
| I-01-12 / CA-12 | RF-007 | 13 | Core→almacén seleccionado→Web: publicación/rollback/limpieza de objetos, reinicio y dos réplicas conservan referencias y bytes; FS compartido explícito o S3 privado real. |
| I-01-13 / CA-13 | RF-006, RF-007 | 11 | Web/Chat→Core: lectura/autor nuevos reflejan edición; mensaje anterior conserva snapshot histórico. |

## Canales — SPEC-03

Fuente: [SPEC-03](spec-p1/spec_03_channel.md). Proveedor dueño: Canales/Core; Cuentas/Profile
aportan lecturas locales y Streaming aporta estado autoritativo mediante batch.

| Escenario / CA | RF | Hijo | Consumidor → proveedor y resultado exigido |
| --- | --- | --- | --- |
| I-03-01 / CA-01 | RF-008 | 11 | Registro→Core/Canales local: exactamente un canal con FK/UNIQUE y mismo channelId al reintentar. |
| I-03-02 / CA-02 | RF-009 | 12 | Web→Core→almacén: edición owner/otro, portada real, validación y fallo de publicación conservan anterior. |
| I-03-03 / CA-03 | RF-010, RF-012 | 12 | Web→bootstrap Core→batch Streaming: handle/ID resuelven el mismo canal; Watch consulta sesión directa; fallo batch conserva canal UNKNOWN. |
| I-03-04 / CA-04 | RF-011, RF-012 | 11 | Streaming→proyección Core→Web: cambio de disponibilidad confirmado visible en ≤5 s; canal no depende de activación asíncrona. |
| I-03-05 / CA-05 | RF-011, RF-012 | 12 | Web→Core/Streaming: PLAYABLE, gracia reconectando y OFFLINE diferenciados; no inventar VOD. |
| I-03-06 / CA-06 | RF-010 | 11 | Web→Core: visibilidad inmediata, 404 uniforme y channelVersion inicial/cambio/no-op correctos. |
| I-03-07 / CA-07 | RF-009 | 11 | Clientes concurrentes→Core: PATCH campos distintos se conserva; mismo campo último commit; fallo conserva datos/versión. |
| I-03-08 / CA-08 | RF-008 | 11 | Web→registro Core: rollback sin parcial y retry tras respuesta perdida, sin saga/compensación. |
| I-03-09 / CA-09 | RF-008, RF-010 | 11 | Web→Core: canal existe al confirmar cuenta; Core/SQL caídos dan indisponibilidad explícita. |

## Emisiones — SPEC-04

Fuente: [SPEC-04](spec-p1/spec_04_stream.md). Streaming posee configuración, sesión, clock y
leases; Media/adaptador posee observación técnica y MediaMTX transporta el medio.

| Escenario / CA | RF | Hijo | Consumidor → proveedor y resultado exigido |
| --- | --- | --- | --- |
| I-04-01 / CA-01 | RF-017, RF-018, RF-019, RF-020 | 11 | Encoder→Media→Streaming→Core: metadata/ingest válidos, PREPARING→playback real→LIVE; deadline sin playback libera slot; retry conserva intento/IDs. |
| I-04-02 / CA-02 | RF-017, RF-018 | 13 | Seis fuentes→Media/Streaming: segunda fuente del canal y sexto slot global rechazan, contando PREPARING/LIVE/gracia. |
| I-04-03 / CA-03 | RF-023, RF-024 | 13 | Player anónimo→HLS Media/Streaming: todos los inicios del perfil de cien players llegan a frame visible ≤5 s. |
| I-04-04 / CA-04 | RF-020, RF-022 | 11 | Encoder→Media→Streaming→Core/Chat/Web: pérdida/retorno antes, en y después de 30 s; contexto cerca de 29 s; transición atómica, callback tardío y SID nuevo fuera de gracia. |
| I-04-05 / CA-05 | RF-021 | 11 | Owner Web→Streaming→Media/Chat/Core: stop inmediato y cambio visible ≤5 s; fuente no revive sesión terminada. |
| I-04-06 / CA-06 | RF-019 | 11 | Owner Web→Streaming→Core/Discovery: edición LIVE confirmada con categoría/tags válidos visible en canal y búsqueda. |
| I-04-07 / CA-07 | RF-025 | 12 | Player/Web→Core/Streaming: autor/canal/título/categoría actuales y ausencia de secretos en DTO/HTML. |
| I-04-08 / CA-08 | RF-026 | 12 | Player→Streaming: lease solo tras frame, heartbeat 10 s, borrado al cerrar y expiración 30 s; anónimo y autenticado, sin conteo enviado por cliente. |
| I-04-09 / CA-09 | RF-023, RF-024, RF-026 | 13 | Cinco encoders→Media→cien players: perfil completo diez minutos, continuidad, tasas y errores separados. |
| I-04-10 / CA-10 | RF-019 | 11 | Web→Streaming→Catálogo Core: IDs activos/tipados al elegir; título conserva asociación inactiva y label histórico. |
| I-04-11 / CA-11 | RF-026 | 10 | Streaming→Discovery/Web: conteo estimado de leases, no autorización ni beneficios; contrato explicita freshness. |
| I-04-12 / CA-12 | RF-017, RF-020, RF-022 | 13 | Media→Streaming: envelope/auth/path/generaciones, durable ACK, duplicado/conflicto/obsoleto, retries con reloj controlable, alerta única/DLQ/redrive y 410. |
| I-04-13 / CA-13 | RF-026 | 11 | Player→Streaming outbox→Core inbox→Discovery: conteo fresco ≤5 s, expiry, duplicado/desorden/ENDED y rebuild con watermark sin regresión. |

## Chat — SPEC-05

Fuente: [SPEC-05](spec-p1/spec_05_chat.md). Chat/Redis poseen mensajes, cuota y entrega;
Core compone contexto de identidad/autor y consulta timeline actual de Streaming por envío nuevo.

| Escenario / CA | RF | Hijo | Consumidor → proveedor y resultado exigido |
| --- | --- | --- | --- |
| I-05-01 / CA-01 | RF-031, RF-032, RF-034 | 12 | Web anónimo→Chat REST/WS: historial/live accesibles y envío rechazado sin mensaje persistente. |
| I-05-02 / CA-02 | RF-032, RF-033 | 11 | Web→Chat→Core→Streaming: normalización/límites y ventana móvil global por cuenta en distintas salas/réplicas; sin burst. |
| I-05-03 / CA-03 | RF-033 | 13 | Web→Chat/Redis→participantes: persistencia antes de ACK, retry mismo ID, orden/dedupe/fan-out y p95 bajo perfil de veinte mensajes/s. |
| I-05-04 / CA-04 | RF-034 | 12 | Web→WS antes de REST Chat: últimos cincuenta, aislamiento de sesión y merge por sequence sin huecos/duplicados durante llegada concurrente. |
| I-05-05 / CA-05 | RF-031, RF-035 | 11 | Encoder→Streaming→Chat/Web: sala en gracia, envío cerca de 29 s, ENDED read-only y eliminación tras retención. |
| I-05-06 / CA-06 | RF-035 | 13 | Web→Chat caído con Media sano: HLS continúa; UI informa indisponibilidad/deshabilita composer y recupera lectura. |
| I-05-07 / CA-07 | RF-034 | 12 | Chat→Web: autor/SID/offset conservados, texto seguro sin ejecución de HTML ni secretos. |
| I-05-08 / CA-08 | RF-032, RF-035 | 11 | Chat→Core→Streaming: autor actual/default y rechazos por dependencia, principal, ENDED/timeline; ninguna escritura rechazada persiste ni usa Discovery para autorizar. |

## Catálogo — SPEC-06

Fuente: [SPEC-06](spec-p1/spec_06_tax.md). Catálogo/Core posee IDs y labels; las mutaciones de
fixtures son exclusivamente de servidor, no una API de administración inventada para P1.

| Escenario / CA | RF | Hijo | Consumidor → proveedor y resultado exigido |
| --- | --- | --- | --- |
| I-06-01 / CA-01 | RF-066, RF-067 | 12 | Web/Streaming→Core: siete categorías/ocho tags semilla con IDs estables y tipos separados. |
| I-06-02 / CA-02 | RF-066, RF-067 | 11 | Web→Streaming→Core: categoría/tag ausente/inactivo/inexistente, cero tags válido y más de cinco rechazado. |
| I-06-03 / CA-03 | RF-066, RF-067 | 11 | Web→Streaming: exactamente una categoría; tags repetidos no duplican asociación. |
| I-06-04 / CA-04 | RF-068, RF-069 | 11 | Streaming→Core Discovery→Web: conjuntos exactos por IDs/categoría/tag AND, excluyendo OFFLINE/gracia/ENDED y coincidencias por subcadena de tag. |
| I-06-05 / CA-05 | RF-066, RF-067 | 11 | Owner Web→Streaming→Discovery: edición LIVE válida visible; inválida conserva valores. |
| I-06-06 / CA-06 | RF-066, RF-067 | 12 | Fixture servidor→Catálogo→Web sin rebuild: versión nueva publica opción nueva sin cambio de cliente. |
| I-06-07 / CA-07 | RF-066, RF-067, RF-068, RF-069 | 11 | Fixture servidor→Core/Streaming/Discovery: desactivado no seleccionable, tombstone conserva ID/label durante edición/rebuild. |

## Descubrimiento — SPEC-07

Fuente: [SPEC-07](spec-p1/spec_07_disc.md). Discovery/Core posee consultas y proyección pública;
Streaming es autoridad de emisión. La búsqueda nunca consulta SQL privado Streaming.

| Escenario / CA | RF | Hijo | Consumidor → proveedor y resultado exigido |
| --- | --- | --- | --- |
| I-07-01 / CA-01 | RF-070 | 11 | Players→Streaming→Discovery→Web: ranking con empates y cursor/snapshot estable mientras cambia conteo. |
| I-07-02 / CA-02 | RF-071 | 12 | Web GraphQL→Core: canales públicos en LIVE/OFFLINE/gracia sin secretos. |
| I-07-03 / CA-03 | RF-071, RF-072 | 11 | Web GraphQL→Core: búsqueda parcial normalizada/case-insensitive conservando acentos. |
| I-07-04 / CA-04 | RF-072, RF-073 | 11 | Web GraphQL→Core con medio real: título/filtros incluyen solo PLAYABLE, excluyen otros estados/VOD y usan IDs exactos. |
| I-07-05 / CA-05 | RF-073 | 11 | Web GraphQL→Core: categoría+tag AND y error de campo para desconocido/inactivo. |
| I-07-06 / CA-06 | RF-070, RF-071, RF-072, RF-073 | 13 | Web/API→Core: muestras de consultas paginadas y p95/error/timeout bajo perfil P1. |
| I-07-07 / CA-07 | RF-073 | 11 | Catálogo→Discovery→Web: tombstone mantiene label existente, no opción elegible de filtro. |
| I-07-08 / CA-08 | RF-070, RF-071, RF-072, RF-073 | 13 | Clientes→proxy→Core replicas: forma/costo/tamaño GraphQL y cuota/IP compartida, proxy confiable y Retry-After; SQL caído falla cerrado. |
| I-07-09 / CA-09 | RF-071 | 11 | Registro Core→Discovery→Web: visibilidad conjunta desde commit, sin PENDING/parcial ni evento Streaming requerido. |
| I-07-10 / CA-10 | RF-070, RF-071, RF-072, RF-073 | 11 | Streaming outbox→Core inbox/rebuild→Web: frescura ≤5 s, UNKNOWN, ENDED, snapshots/versiones/corte/watermark y concurrencia sin regresiones. |

## Accesibilidad — SPEC-08

Fuente: [SPEC-08](spec-p1/spec_08_a11y.md). Web es el proveedor de interacción accesible y
personas/tecnología asistiva son consumidores. Incluye rutas reales con Core/Streaming/Chat/Media;
una biblioteca de ejemplos o Axe aislado no sustituye este recorrido. RF de las vistas: los 34 RF
P1 de las tablas anteriores; requisitos primarios de accesibilidad: RNF-035/036.

| Escenario / CA | Hijo | Recorrido y resultado exigido |
| --- | --- | --- |
| I-08-01 / CA-01 | 12 | Registro/login/perfil/canal/browse/player por teclado con foco lógico en las rutas reales. |
| I-08-02 / CA-02 | 12 | Foco visible/no oculto bajo overlays, encabezados y autoscroll de chat. |
| I-08-03 / CA-03 | 12 | Nombre/rol/estado de controles; error anunciado y ligado al campo, incluidos fallos de API. |
| I-08-04 / CA-04 | 12 | LIVE/OFFLINE, éxito y error reconocibles sin depender solo del color. |
| I-08-05 / CA-05 | 12 | Player enfocable y controles por teclado; navegación/reproducción básica con Chat caído. |
| I-08-06 / CA-06 | 12 | Pausar/ocultar y reanudar autoscroll con mensajes reales sin saltos de lectura no solicitados. |
| I-08-07 / CA-07 | 13 | Persona con tecnología asistiva + herramienta: registrar navegador/versión/rutas/defectos y repetir tras correcciones. |
| I-08-08 / CA-08 | 12 | Comprobar criterios WCAG y mínimos numéricos de contraste/targets en cada vista P1; incumplimiento reprueba esa vista. |

## Ejecución y relación con RNF

La cobertura contiene 68 escenarios para 68 CA, y referencia los 34 RF P1 del catálogo. SPEC-02
se consolidó en SPEC-01. RF/RNF futuros conservan su prioridad; no son escenarios de aceptación P1.
La matriz RNF mantiene la SPEC primaria y evidencia exigida para los 49 RNF P1, incluido el de diseño;
RNF-020 permanece futuro. Para cada escenario registrar en Plane los RNF aplicables de esa matriz.

Los ejecutables disponibles son puntos de partida; no acreditan por su existencia todas las variantes
de las filas. [Contratos](../tests/contracts/README.md) valida schemas y consumidores neutrales;
[dominios/Web](../tests/integration/p1-domains/README.md) cruza proveedores reales;
[entrega TLS](../tests/integration/p1-delivery/README.md) ejercita carga, réplicas, caídas y restore.
Las pruebas de reloj controlable/SQL complementan Media/Streaming; su nivel debe declararse.
La revisión asistiva, versiones estables de Chrome/Firefox, runbook desde otro checkout y confirmación
docente de RNF-006 requieren sus evidencias específicas. Solo se cierra P1 cuando las filas, RNF y
CA de los hijos tienen evidencia suficiente y ningún fallo obligatorio pendiente.
