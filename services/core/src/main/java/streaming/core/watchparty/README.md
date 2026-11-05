# Core / Watch parties

Módulo `watchparty` de Core ([SPEC-21](../../../../../../../../docs/spec-p2/spec_21_watch_party.md), RF-046…051,
[ADR-025](../../../../../../../../docs/adr/ADR-025-watch-parties-en-core.md)). Una sala privada reúne hasta 4 emisiones de Streaming
que sus miembros ven a la vez. Vive en el build, la base y el runtime de Core; no añade proceso.

Políticas: crea cualquier usuario con sesión; entra quien tiene cuenta y el código de acceso (256 bits, solo se guarda su SHA-256 y se
muestra una vez); solo el propietario añade y retira fuentes, rota el código y cierra la sala (`OPEN` → `CLOSED`, terminal). Un tercero
recibe `404` igual que ante una sala inexistente. No hay sincronía, chat propio, invitaciones nominales ni mezcla audiovisual.

Capas: `api` (`WatchPartyController`), `application` (casos de uso y puertos), `domain` (reglas puras) e `infrastructure`
(`JdbcWatchPartyStore`, `HttpStreamDirectory`, `LocalAccountSessions`, `LocalChannelDirectory`). Los datos están en el esquema `watchparty`
(`V12__watchparty.sql`): `parties`, `party_members`, `party_streams` y `operations`.

- **Autoridad de cada fuente.** Core guarda solo `streamId` y `channelId` opacos y lee `GET /api/streams/{streamId}` de Streaming (público,
  sin credenciales) fuera de toda transacción, en paralelo y con plazos de 1 s de conexión y 2 s en total, cuerpo incluido (`WATCHPARTY_STREAMING_BASE_URL`; vacía deshabilita la lectura). Solo el 404
  `STREAM_NOT_FOUND` de Streaming se toma como definitivo. Añadir falla cerrado (`503 STREAMING_UNAVAILABLE`); leer degrada a `UNKNOWN`.
- **Estado de una fuente.** `PLAYABLE`, `RECONNECTING`, `OFFLINE`, `ENDED`, `SUSPENDED` o `UNKNOWN`. Solo `PLAYABLE` (fresca, `LIVE` y con
  sesión) entrega `sessionId`. Si el canal ya no se publica porque su cuenta está suspendida, la fuente es `SUSPENDED` y no revela canal ni
  metadatos. Una sala cerrada no lista fuentes.
- **Comandos.** Todos exigen `Idempotency-Key` (UUID). El resultado se registra con la sala en la misma transacción y se conserva 24 horas
  (purga cada 5 minutos); repetirlo devuelve la sala sin repetir el efecto ni mostrar otra vez el código, y otra carga con la misma clave
  responde `409 IDEMPOTENCY_CONFLICT`. Las mutaciones se serializan con un bloqueo de fila y `partyVersion` crece con cada cambio efectivo.
- **Seguridad.** La sesión la resuelve el filtro común (cookie o Bearer nativo) y las mutaciones del navegador usan el CSRF común de Core
  (`/api/identity/csrf`). Las respuestas llevan `Cache-Control: no-store, private`.

Rutas (`p2.watch-parties.*`): `POST /api/watch-parties`, `GET /api/watch-parties/{partyId}`, `POST /api/watch-parties/join`,
`POST /api/watch-parties/{partyId}/streams`, `DELETE /api/watch-parties/{partyId}/streams/{streamId}`,
`POST /api/watch-parties/{partyId}/access-code/rotate` y `POST /api/watch-parties/{partyId}/close`. Caddy las enruta a Core.

Pruebas: `WatchPartyApplicationServiceTest`, `WatchPartyRulesTest` y `HttpStreamDirectoryTest` (unitarias); `WatchPartyIT` (HTTP, sesión, CSRF
y SQL reales con un Streaming simulado) y `WatchPartyMigrationIT` (migración V11 → V12 e invariantes del esquema) con PostgreSQL desechable.
Comandos en el [README de Core](../../../../../../README.md).

Pendiente: listado de salas del usuario, tope por cuenta, integración móvil y la aceptación con varias reproducciones reales (SPEC-21).
