# Core / Watch Party

Watch Party vive dentro del build y runtime de Core, bajo `streaming.core.watchparty`. Es una capacidad
futura (RF-046…RF-051) implementada de forma anticipada: sesiones de visualización conjunta con hasta 4
transmisiones en vivo que varios usuarios ven a la vez.

Implementado: crear, leer y cerrar una sesión; agregar y retirar transmisiones (solo el propietario); entrar con un
código de acceso opaco y rotarlo; lectura compuesta con el estado actual de cada transmisión y los datos públicos
de su canal. Reglas acordadas el 2026-10-05: crea cualquier usuario con sesión, máximo 4 transmisiones, entrar exige
cuenta y código, una transmisión finalizada queda marcada hasta que el propietario la retire, solo se agregan
transmisiones reproducibles y la sesión no caduca sola.

Capas: `api` (REST), `application` (casos de uso y puertos), `domain` (reglas puras) e `infrastructure`
(`JdbcWatchPartyStore`, `HttpStreamDirectory`, `LocalAccountSessions`, `LocalChannelDirectory`). Streaming es la
autoridad del estado de cada transmisión: Core lee `GET /api/streams/{streamId}` (público, sin credenciales) fuera de
toda transacción y en paralelo; sin respuesta, agregar falla (`503`) y leer degrada a `UNKNOWN`. Los datos de canal se
leen por `ChannelQueries`; la sesión, por la interfaz local de Cuentas, y el CSRF es el común de Core.

Rutas: `GET /api/watch-parties/csrf`, `POST /api/watch-parties`, `GET /api/watch-parties/{partyId}`,
`POST /api/watch-parties/join`, `POST /api/watch-parties/{partyId}/streams`,
`DELETE /api/watch-parties/{partyId}/streams/{streamId}`, `POST /api/watch-parties/{partyId}/access-code/rotate` y
`POST /api/watch-parties/{partyId}/close`. El código de acceso se muestra una vez y solo se guarda su SHA-256.

Pendiente: integración con el Streaming real (PR #7; hoy se verifica contra su contrato con un servidor simulado),
sincronización de reproducción, restricción por Premium, retiro automático al finalizar una transmisión y la UI.

Definición: [SPEC-14](../../../../../../../../docs/spec-futuro/spec_14_watch_party.md),
[contratos](../../../../../../../../docs/contratos_modelo_datos.md),
[ADR-007](../../../../../../../../docs/adr/ADR-007-watch-party-en-core.md).
Configuración, comandos y pruebas: [Core](../../../../../../README.md).
