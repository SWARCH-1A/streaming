# ADR-008: Descubrimiento en Core con GraphQL propio y proyección SQL de Streaming

- Estado: aceptada
- Fecha: 2026-10-08
- Responsable: Core / Consultas y descubrimiento; revisión con Core, Streaming e Integración
- SPEC/contratos afectados: SPEC-07; RF-070…RF-073; RNF-011, RNF-014, RNF-038, RNF-041; contratos de consulta, proyección pública y contextos privados Core–Streaming.

## Contexto

[ADR-005](ADR-005-streaming-rust-y-proyeccion-discovery.md) fija que Discovery vive en Core, lee Cuentas/Canales/Catálogo
por SQL local y recibe de Streaming una proyección pública versionada. El servicio Streaming ya publica cada
`StreamDiscoverySnapshot` a `POST /internal/core/discovery/stream-events` y expone un corte paginado de reconstrucción;
Core todavía no recibe esos eventos. SPEC-07 exige además un GraphQL público con límites estrictos de forma, costo y
tasa, paginación estable bajo ranking dinámico y frescura de 5 s sin inventar estado. El contrato pide un ADR para la
librería GraphQL y para los detalles que dejó abiertos.

## Decisión

1. **Ubicación y datos.** Módulo `discovery` de Core (`streaming.core.discovery`; capas `api`, `application`, `domain`,
   `infrastructure`), esquema SQL `discovery` en la migración V5. Sin runtime, base ni broker nuevos. La migración también
   publica la vista `channels.public_channels` (`channel_id`, `owner_user_id`, `channel_version`) para leer canales sin
   acceder a su tabla, igual que `identity.public_accounts` y `profile.public_profiles`.
2. **GraphQL.** `graphql-java` 25.0 (versión gestionada por el BOM de Spring Boot 4.1.1) detrás de un controlador propio
   en `POST /api/discovery/graphql`. Cuerpo máximo 16 KiB leído antes de parsear; solo `query`; una única operación; solo
   raíces `streams` y `channels` una vez cada una; sin alias, fragments ni campos `__*`; `limit` ≤50 por conexión y ≤100
   por solicitud. El esquema es el del contrato, sin mutaciones ni suscripciones.
3. **Códigos HTTP.** Cuerpo/JSON/sintaxis/variables inválidos: `400 BAD_REQUEST`. Forma o costo excedidos (incluidos
   cuerpo >16 KiB y `limit`>50): `422 QUERY_LIMIT_EXCEEDED` antes de tocar SQL. Content-Type distinto de
   `application/json`: `415`. Cuota: `429 RATE_LIMITED` con `Retry-After`. Errores de campo (`INVALID_FILTER`,
   `INVALID_LIMIT` para `limit<1`, `INVALID_CURSOR`): HTTP 200 con `errors[].extensions.httpStatus=422` y el resto de los
   campos independientes intactos. Fallo del servicio: `503`/`504` con los datos parciales disponibles. Todo error lleva
   `extensions.code` y `requestId`.
4. **Recepción de snapshots.** Ruta privada `POST /internal/core/discovery/stream-events`, con el mismo `X-Service-Name`/
   `X-Service-Token` que Streaming ya usa para `owner-context` y un permiso propio (`DISCOVERY_EVENTS`) que **no** concede
   la credencial limitada a catálogo. Valida envelope y payload (texto sin NUL y fechas entre 1970 y 9999, para que un evento mal formado sea 422 y no un error de servidor que Streaming reintentaría); inbox y aplicación en una sola transacción:
   `202` nuevo, `200` mismo `eventId` y mismo contenido, `409` mismo `eventId` con otro contenido o misma
   `projectionVersion` con otro contenido, `422` inválido, `400` JSON malformado. Solo una `projectionVersion` mayor
   reemplaza la fila; versiones iguales idénticas u antiguas se ignoran sin regresión. Un conflicto se conserva con su
   payload para el operador. El inbox guarda solo identidad y hash del evento y se purga a la hora (más que la ventana de
   15 min de reintentos de Streaming): Streaming publica una observación por configuración cada ≤2 s y guardar cada payload
   sería inviable. Un canal aún no resuelto localmente no bloquea nada: la fila se aplica y solo se hace visible al
   existir cuenta, perfil y canal.
5. **Reconstrucción y ausencia.** Una tarea programada pide a Streaming el corte paginado
   (`POST /internal/streaming/discovery/snapshots`, `Authorization: Bearer`, límite 50; reinicia ante `410
   SNAPSHOT_EXPIRED`), valida todo en memoria y lo publica en una transacción: reemplaza filas con versión mayor y retira
   solo las ausentes del corte cuya `discoveryPosition` sea ≤ al `watermark`. Si falla, conserva la proyección previa y su
   frescura real. Como una configuración inexistente solo se acredita con un corte vigente (≤5 s), la cadencia por defecto
   es de 4 s, contada de inicio a inicio de cada corte (un retardo fijo sumaría la duración del corte y un ritmo fijo dispararía cortes seguidos para ponerse al día), y es configurable. La prueba de ausencia se mantiene continua mientras un corte tarde menos de un segundo. El corte cabe en memoria al tamaño del prototipo; no se crea tabla de staging.
6. **Frescura.** `statusFresh` y `viewerCountFresh` usan la edad respecto a `stateObservedAtUtc` y
   `viewerCountObservedAtUtc` con un reloj inyectable (≤5 s). Una fila sin observación vigente se excluye de `streams` y
   en `channels` aparece como `UNKNOWN`; sin fila, un corte vigente acredita OFFLINE y de lo contrario `UNKNOWN`.
7. **`streams`.** Solo `status=LIVE` y `availability=PLAYABLE` frescas. Filtros contra el catálogo activo (misma lectura
   SQL del contexto de catálogo): un ID desconocido, inactivo o del tipo equivocado da `INVALID_FILTER`; categoría y tag se
   combinan con AND. La paginación usa un snapshot de ranking materializado (ID ordenados, TTL 5 min, purga programada) y
   un cursor opaco `snapshotId.offset` atado al filtro; solo se persiste cuando hay más de una página.
8. **`channels`.** `INNER JOIN` de cuenta, perfil y canal (nunca aparece algo parcial) con `LEFT JOIN` a la proyección;
   coincidencia por subcadena NFKC en minúsculas con acentos conservados, calculada en SQL con `normalize`/`lower`;
   orden exacta → prefijo → subcadena, handle antes que nombre visible, luego handle y `userId`; cursor de clave
   (sin estado) atado al filtro.
9. **Tasa e IP.** Según ADR-014, limitador compartido en SQL Core por HMAC de IP: cubeta de 20 que repone 10/s
   más un tope móvil de 600 por 60 s, con tiempo SQL y transacción por bucket. SQL inaccesible devuelve 503 sin
   fallback local. Sustituye la decisión original en memoria al incorporar réplicas Core. La IP es la
   del socket salvo que `core.trusted-proxies` (CIDR) incluya al par directo: entonces se toma la última entrada no
   confiable de `X-Forwarded-For`. Por defecto no hay proxies confiables.
10. **CSRF.** La ruta pública de solo lectura queda fuera de la protección CSRF, exige `Content-Type: application/json` y
    no usa la cookie de sesión. El resto de Core conserva CSRF sin cambios.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| `graphql-java` + controlador propio | Elegida: control total de códigos HTTP, tamaño de cuerpo y validación previa; dependencia ya gestionada por el BOM. |
| Spring for GraphQL 2.0.5 | Viable, pero fija su propio transporte (HTTP 200 con errores) y obligaría a un filtro que relea el cuerpo para devolver 400/422/429. |
| Keyset puro para `streams` | Descartada: el ranking cambia con el conteo y las páginas saltarían o repetirían filas. |
| Consultar a Streaming por cada resultado | Descartada por ADR-005: HTTP por fila y acoplamiento de latencia. |
| Motor de búsqueda externo | Fuera de P1; exige reconstrucción con watermark y ADR propio. |
| Limitador SQL o Redis | ADR-014 selecciona SQL Core compartido al habilitar réplicas; no añade un runtime ni dependencia sobre Redis Chat. |

## Consecuencias

Descubrimiento comparte disponibilidad y release con Core; una caída de Core afecta todas sus APIs y una caída de
Streaming solo degrada la frescura (las filas vencen a `UNKNOWN`, nunca a OFFLINE). La frescura compara el reloj de
Streaming con el de Core, de modo que un desfase mayor a unos segundos reduce el tiempo en que los datos se ven frescos.
Un corte cada 4 s genera unos 21 600 cortes diarios en Streaming, que los conserva un día: debe revisarse con Streaming si
crece el número de configuraciones. Si un corte completo tarda un segundo o más, los canales sin proyección pasan a `UNKNOWN`
durante parte de cada ciclo (nunca a OFFLINE): habría que acortar la cadencia o reducir el costo del corte. La cuota SQL compartida añade escrituras y bloqueo por IP, conservando el límite
al replicar Core; su indisponibilidad rechaza la consulta. El cursor de
`streams` caduca a los 5 min y entonces responde `INVALID_CURSOR`. No se acredita el perfil de carga de SPEC-13.

## Verificación

Pruebas unitarias de normalización, reglas de versión, cursor, validador de consulta, limitador e IP confiable; pruebas de
integración con PostgreSQL desechable que cubren CA-01…CA-10, la recepción (duplicados, desorden, conflictos, 404 por el
puerto público), la reconstrucción con eventos concurrentes y corte vencido contra un Streaming simulado con los formatos
reales, la migración V4→V5 y la privacidad del payload. La aceptación de RF-070…RF-073 requiere esa evidencia ejecutada y,
después, la integración real con Streaming y la medición de SPEC-13.

## Revisión

Revisar si el número de configuraciones hace costoso el corte periódico, si aparece más de una réplica de Core, si se
introduce búsqueda sobre VOD o si Streaming cambia el formato del snapshot. Revisa: responsable de Descubrimiento con
Core y Streaming.

La revisión de Core, Streaming e Integración acepta esta selección para P1: Discovery permanece dentro de Core, consume
la proyección pública versionada de Streaming y no introduce otro runtime, base de datos o broker. La verificación de la
implementación y la medición de SPEC-13 siguen siendo evidencia de entrega, no condiciones para volver a tratar la
tecnología como candidata.

La selección original del limitador en memoria fue reemplazada por cuota compartida SQL al habilitar
réplicas Core en [ADR-014](ADR-014-perfil-integrado-tls-p1.md). Los demás aspectos de esta decisión se conservan.
