# SPEC-21 — Watch parties

**Prioridad:** P2. **Responsable:** Core / Watch parties. **Estado:** implementado en Core y Web con políticas cerradas en [ADR-025](../adr/ADR-025-watch-parties-en-core.md) (propuesta, pendiente de la revisión de integración); verificado en un stack local aislado y completo (19 servicios, con Chat, VOD y Notificaciones) con emisiones reales. Queda la integración móvil.
**RF:** RF-046…051. **RNF:** RNF-022…030, 038, 041…047.

## 1. Contexto y problema

La plataforma incluye visualización conjunta de varias emisiones. Una sala reúne hasta cuatro fuentes de Streaming que sus
miembros ven a la vez. Acceso, permisos y ciclo de sala los decide su responsable en ADR-025; cada fuente conserva su autoridad
de estado y acceso en su servicio.

## 2. Definición del componente

Módulo `watchparty` de Core (`streaming.core.watchparty`), con autoridad propia sobre la sala, su membresía y sus fuentes. No
crea un proceso, una base ni un broker, y no cuenta como unidad adicional para el mínimo académico. Web compone la rejilla de
reproductores con el contrato público de cada fuente. El módulo no almacena el estado de las emisiones: lo lee de Streaming
en cada consulta y combina los datos públicos de su canal desde Canales.

## 3. Historia de usuario

Como usuario autorizado, quiero crear una sala con varias emisiones, añadir/retirar fuentes
y consultar los canales incluidos.

## 4. Alcance

Crear y consultar una sala, entrar con su código, añadir y retirar fuentes, rotar el código, cerrarla y mostrar las fuentes
simultáneamente con su canal. RF-049 no exige una mezcla audiovisual, chat nuevo, invitaciones o sincronía entre
dispositivos; esas extensiones quedan fuera de esta entrega (P2-03 resuelta). La aplicación móvil queda fuera: se integrará
con las mismas operaciones.

## 5. Requisitos funcionales

RF-046 crea sala por usuario autorizado; RF-047/048 modifican sus emisiones; RF-049 reproduce
varias fuentes; RF-050 permite acceso; RF-051 muestra canal e información de cada fuente.
Cada fuente conserva autoridad de estado y acceso en su servicio. No se copian secretos de
ingesta ni se usa una sala para eludir suspensión de un canal.

## 6. Criterios de aceptación

- [x] CA-01: el responsable cierra acceso/roles, cantidad de fuentes, ciclo y comportamiento al acabar una emisión en P2-01…03. (ADR-025: sala privada por código, hasta 4 fuentes, la fuente terminada queda con su estado hasta que el propietario la retire, cierre terminal. La revisión de integración del ADR sigue pendiente.)
- [x] CA-02: contrato de crear/consultar/agregar/retirar/cerrar tiene schemas, errores y permisos consistentes con esas decisiones. (`p2.watch-parties.*` en `contracts/p2.json`, validadas por `check_p2.py` y `tests/contracts`.)
- [x] CA-03: usuario autorizado crea una sala y añade/retira fuentes sin duplicación; usuario sin permiso es rechazado. (`WatchPartyIT`: propietario, miembro y tercero; límite de cuatro y duplicados con solicitudes concurrentes.)
- [x] CA-04: varias fuentes reales se reproducen con su canal visible; caída/fin de una se presenta sin inventar una fuente activa. (Navegador real: 4 emisiones RTMPS reales se reproducen a la vez para el propietario y para un miembro, cada una con su canal; al caer una pasa a `RECONNECTING` a los 0,2 s y a `ENDED` a los 30,5 s, siempre sin sesión y con su reproductor desmontado.)
- [x] CA-05: duplicar comandos o reiniciar conserva sala y membresía según ciclo acordado. (`WatchPartyIT`: repetición con la misma `Idempotency-Key`, claves en conflicto y solicitudes concurrentes. Stack real: tras reiniciar el contenedor de Core la sala, su versión y la membresía se conservan y repetir el comando sigue devolviendo la misma sala sin código.)
- [x] CA-06: fuente suspendida deja de poder consumirse desde la sala; el resto conserva su estado. (`WatchPartyIT`: la fuente pasa a `SUSPENDED` sin sesión, canal ni metadatos y se restaura al reactivar la cuenta. El corte del HLS directo es de Streaming y Media: SPEC-23/P2-17.)
- [x] CA-07: cliente maneja el límite elegido y errores por fuente sin conexiones o leases huérfanos. (Navegador real contra Streaming: con dos participantes cada fuente tiene exactamente 2 leases; al quitar una fuente, caerse o cerrar la sala sus leases bajan a 0 y los de las demás se conservan. Con 4 fuentes la UI bloquea añadir más y los errores por fuente se muestran sin afectar al resto.)

## 7. Diseño técnico y datos

Esquema SQL `watchparty` (migración V12): `parties` (propietario, título, hash SHA-256 del código, estado `OPEN`/`CLOSED`,
`party_version`), `party_members`, `party_streams` (`streamId` y `channelId` opacos, sin clave foránea hacia Streaming) y
`operations` (resultado de cada `Idempotency-Key` durante 24 horas). Las mutaciones se serializan con un bloqueo de fila sobre la
sala; el límite de cuatro y los duplicados se vuelven a comprobar bajo ese bloqueo y `partyVersion` crece con cada cambio efectivo.

Streaming se consulta fuera de transacciones, en paralelo y con plazos de 1 s y 2 s; añadir falla cerrado y leer degrada a
`UNKNOWN`. Solo una fuente fresca, `LIVE` y `PLAYABLE` de un canal publicado entrega su `sessionId`; una sala cerrada no
lista fuentes. Los datos del canal se leen por `ChannelQueries`, de modo que un canal cuya cuenta está suspendida deja de
publicarse y su fuente pasa a `SUSPENDED`.

Los contratos de cada fuente continúan disponibles por Gateway. La composición simultánea en cliente no genera una nueva
emisión ni evade leases: cada reproductor abre y libera el suyo. La sala no introduce mezcla audiovisual.

## 8. Dependencias y contratos de integración

Siete operaciones `p2.watch-parties.*` (ver [contratos HTTP](../contratos_p2_http.md#watch-parties) y
[ADR-025](../adr/ADR-025-watch-parties-en-core.md)). Streaming provee estado/player/leases; Core, canal/permisos; Caddy enruta
`/api/watch-parties` a Core; Web presenta la rejilla. No hay agregado de sala en el Gateway.

## 9. Decisiones y preguntas abiertas

P2-01, P2-02 y P2-03 están resueltas por el responsable en ADR-025 y pendientes de su revisión de integración. Quedan abiertas,
sin bloquear la entrega: el listado de salas de un usuario, un tope de salas por cuenta, la carga que la lectura repetida de
cuatro fuentes impone a Streaming y la integración móvil.

## 10. Verificación

Evidencia ejecutada el 2026-11-01 sobre `develop`:

- Core, suite completa (`./mvnw verify -P integration`, PostgreSQL 18 con Testcontainers): 286 pruebas unitarias y 245 de integración, 0 fallos. De ellas, Watch parties: 42 del servicio, 13 de reglas, 14 del cliente de Streaming, `WatchPartyIT` (26) y `WatchPartyMigrationIT` (2). Cubren la matriz propietario, miembro, visitante y tercero, la concurrencia del límite y de los duplicados (también con reintentos solapados de la misma `Idempotency-Key`), el estado de cada fuente, la suspensión, el cierre, la idempotencia y la ausencia de datos privados.
- Contratos: `check_p2.py`, `generate.py --check`, `generate_mobile.py` y las 48 pruebas de `tests/contracts` pasan; los payloads reales capturados del stack (sala creada, con 4 fuentes, leída por un miembro y cerrada) validan contra `P2WatchParty`.
- Web: 213 pruebas (64 de Watch parties), lint, typecheck, build de producción y verificación SSR sin fallos; la verificación SSR comprueba que la sala llega renderizada en el primer HTML, con la sesión de quien la pide, y que una sala ajena responde 404.
- Stack local aislado y completo (los 19 servicios del despliegue local, con Chat, VOD, Notificaciones, MinIO, Mailpit y RabbitMQ), migración V1 → V12 sobre base nueva:
  - API real con cuatro publicadores RTMPS reales: 38 comprobaciones (crear, repetir con la misma clave, añadir 4 fuentes y rechazar la 5.ª, miembro, tercero, visitante, caída de una fuente, suspensión y reactivación, reinicio de Core, cierre y `explore` con VOD arriba y detenido). Un `streamId` inexistente responde `404 STREAM_NOT_FOUND` con el 404 real de Streaming.
  - Carga directa de la sala en el navegador (HTML inicial con la sala, hidratación sin avisos y reproductores que arrancan para propietario y miembro): 17 comprobaciones.
  - Navegador (Chromium con H.264): 22 comprobaciones (4 reproductores HLS a la vez para dos participantes, 2 leases por fuente, liberación de leases al quitar, caerse o cerrar, enlace de invitación, accesibilidad con axe sin violaciones serias o críticas y sin desbordamiento a 390 px).
  - Accesibilidad con el listón del repositorio (axe WCAG 2.2 AA, controles de al menos 24 px y sin desbordamiento) en `/watch-parties`, `/watch-parties/join` con y sin sesión y la sala, en escritorio y móvil: 30 comprobaciones.
  - Suites de integración del repositorio contra ese stack: Gateway (SPEC-16), SSR con navegador (SPEC-18), seguimiento y notificaciones (SPEC-03/20), replay de chat con VOD (SPEC-05/14) y borde de Caddy; y la de administración con Core, PostgreSQL, Caddy y Chromium reales. Todas pasan.
- Otros servicios (Gateway, Chat, VOD con ffmpeg y Notificaciones): `go vet`/`go test` y `npm test` sin fallos (las pruebas de Notificaciones con base de datos y SMTP se omiten sin esos servicios, y se ejercitan en la suite de seguimiento).

No se ha ejecutado: la carga sostenida sobre la lectura pública de Streaming, la integración móvil ni las suites de P1 de otros dueños que usan sus propias fixtures (`p1-delivery`, `p1-domains`). La cuota global de cinco sesiones live simultáneas de Streaming limita cuántas salas con fuentes distintas pueden ver emisiones a la vez.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo M. Riesgos: la lectura de hasta cuatro fuentes por consulta y por miembro carga la lectura pública de Streaming; la
suspensión oculta la fuente en la sala pero el corte directo del HLS pertenece a Streaming/Media; sin sincronía cada miembro ve
puntos distintos de cada emisión.
