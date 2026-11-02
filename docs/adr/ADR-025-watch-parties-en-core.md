# ADR-025: Watch parties como módulo de Core con salas por código y fuentes de Streaming

- Estado: propuesta
- Fecha: 2026-11-01
- Responsable: Core / Watch parties; revisión con Gateway, Web, Streaming e Integración
- SPEC/contratos afectados: SPEC-21; RF-046…RF-051; P2-01, P2-02 y P2-03; contratos `p2.watch-parties.*`; ruta pública `/api/watch-parties`.

## Contexto

RF-046…RF-051 piden crear una sala de visualización conjunta, añadir y retirar emisiones activas, mostrarlas a la vez,
permitir el acceso de otros usuarios y mostrar el canal de cada fuente. [SPEC-21](../spec-p2/spec_21_watch_party.md) dejó
sin cerrar el acceso y los roles (P2-01), la cantidad, la disposición y el fin de una fuente (P2-02), y la sincronía, el
chat propio, las invitaciones, la mezcla y el ciclo de la sala (P2-03). También exige que cada fuente conserve su autoridad
de estado y acceso en su servicio, que una sala no sirva para eludir la suspensión de un canal y que los comandos sean
idempotentes y con control de concurrencia.

El equipo había acordado el 2026-10-05 un conjunto de reglas para un primer módulo (PR #11, ADR-007 y SPEC-14 de aquella
rama). Esa rama quedó basada en un `develop` anterior a P2: su ADR-007 y su SPEC-14 ya pertenecen a Web y a VOD, su migración
V5 la ocupa Discovery y no cubría suspensión, idempotencia ni cliente. Este ADR sustituye esa propuesta, conserva sus
reglas de producto y las completa con lo que exige P2.

## Decisión

1. **Ubicación y datos.** Módulo `watchparty` dentro de Core (`streaming.core.watchparty`; capas `api`, `application`,
   `domain` e `infrastructure`), con esquema SQL `watchparty` en la migración V12 (`parties`, `party_members`,
   `party_streams`, `operations`). No hay runtime, base ni broker nuevos. `streamId` y `channelId` son referencias
   opacas a datos de Streaming y Canales: no hay clave foránea hacia ellos.
2. **Políticas cerradas (P2-01…03).**

   | Pregunta | Decisión |
   | --- | --- |
   | Acceso | La sala es privada y se entra con un código de acceso: exige cuenta y código. No hay salas públicas ni anónimas. |
   | Roles | Cualquier usuario con sesión crea una sala y queda como propietario. El propietario añade y retira fuentes, rota el código y cierra. Los miembros solo ven. Un tercero recibe `404` igual que ante una sala inexistente. |
   | Cantidad y disposición | Hasta 4 fuentes, sin duplicados. La disposición es del cliente (rejilla de 1 a 4 reproductores); el servidor no la fija. |
   | Fin de una fuente | La fuente se queda en la sala con su estado real (`ENDED`, `OFFLINE`, `RECONNECTING`…) hasta que el propietario la retire. No hay retirada automática. |
   | Salida del propietario | La sala no depende de que esté conectado y no caduca sola. |
   | Sincronía, chat, invitaciones y mezcla | No hay sincronía de reproducción entre participantes, chat propio ni mezcla audiovisual. La invitación es el propio código o el enlace que lo contiene, compartido fuera de la plataforma. |
   | Ciclo | `OPEN` → `CLOSED`, terminal y solo del propietario. Una sala cerrada no admite entradas ni cambios, no lista fuentes ni entrega sesiones. |

3. **Código de acceso.** Secreto opaco de 256 bits en base64url sin relleno. Solo se guarda su SHA-256 y se muestra una vez,
   al crear o rotar. Rotarlo invalida el anterior sin expulsar a los miembros actuales. El enlace de invitación lleva el
   código en el fragmento (`#`), que el navegador no envía al servidor ni a los registros del proxy.
4. **Autoridad de cada fuente.** Core no guarda estado de transmisión. Lee `GET /api/streams/{streamId}` (lectura pública de
   Streaming, sin credenciales), fuera de toda transacción, en paralelo y con 1 s de conexión y 2 s de lectura en total. Solo el 404 `STREAM_NOT_FOUND` de Streaming es definitivo y una URL
   vacía deshabilita la lectura. Añadir
   falla cerrado (`503 STREAMING_UNAVAILABLE`); leer degrada a `UNKNOWN`. Solo se añade una fuente `LIVE`, `PLAYABLE` y fresca
   cuyo canal Core publica. La composición con los datos del canal usa `ChannelQueries`, sin leer tablas de Canales,
   Cuentas ni Perfil.
5. **Estado de cada fuente y suspensión.** El contrato `P2WatchPartySource.state` es `PLAYABLE`, `RECONNECTING`, `OFFLINE`,
   `ENDED`, `SUSPENDED` o `UNKNOWN`. Solo `PLAYABLE` entrega `sessionId`; un estado no fresco, una respuesta ausente o inválida
   y una sesión inexistente nunca se presentan como en vivo. Si el canal ya no está publicado porque su cuenta está
   suspendida, la fuente pasa a `SUSPENDED`, sin canal, metadatos ni sesión, y el resto de la sala conserva su estado. Esto
   evita usar la sala para consumir desde ella una fuente suspendida; el corte del HLS directo es de Streaming y Media
   (SPEC-23, P2-17).
6. **Comandos idempotentes y concurrencia.** Todo comando exige `Idempotency-Key` (UUID). La clave se guarda con hash por
   cuenta, junto con la huella de la solicitud y la sala resultante, en la misma transacción que el cambio, y se conserva 24
   horas (purga cada 5 minutos). Repetir el comando con la misma clave devuelve la sala con su estado actual sin repetir el
   efecto ni volver a consultar a Streaming; la misma clave con otro contenido responde `409 IDEMPOTENCY_CONFLICT`. El código
   nunca se registra: una repetición de crear o rotar devuelve la sala sin él y el propietario rota el código para obtener
   otro. Las mutaciones se serializan con un bloqueo de fila sobre la sala y `partyVersion` crece con cada cambio efectivo
   (fuentes, código, cierre).
7. **Seguridad.** La sesión la resuelve el filtro común de Core (cookie o Bearer nativo) y las mutaciones del navegador
   usan el CSRF común (`/api/identity/csrf`). Las respuestas llevan `Cache-Control: no-store, private` y no incluyen a otros
   miembros, datos privados de cuentas ni el hash del código. Una cuenta suspendida pierde su sesión y con ella el acceso.
8. **Cliente y ruta.** Caddy enruta `/api/watch-parties` a Core, igual que seguimientos, y el Gateway no añade un agregado de
   sala. Web compone la rejilla con un reproductor por fuente (HLS, calidad y lease propios) usando el contrato público de
   cada fuente; al dejar de ser `PLAYABLE`, quitarla, cerrar la sala o salir, el reproductor se desmonta y su lease se libera.
   La aplicación móvil queda fuera de esta entrega.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Módulo Core con REST y PostgreSQL | Elegida: la membresía y el límite son invariantes locales, comparte seguridad, transacciones, CSRF y build, y no añade runtime. |
| Servicio independiente (Go, WebSocket y Redis) | Descartada: ningún RF exige tiempo real ni sincronía; añade runtime, credenciales y operación sin necesidad medida (criterios de [fases futuras](../fases_futuras.md)). |
| Alojarlo en Streaming | Descartada: Streaming posee emisiones, no grupos de usuarios; mezclaría propiedad de datos. |
| Agregado de sala en el Gateway | Descartada: la sala es autoridad propia con escritura; el Gateway agrega lecturas y no decide permisos ni estado. |
| Retirar automáticamente una fuente al terminar (evento Streaming → Core) | Aplazada: exige otro consumidor y contrato. Se muestra su estado real y decide el propietario. |
| Sala pública o por invitación nominal | Descartada para P2: la primera obliga a moderar acceso anónimo y la segunda añade invitaciones, que RF-046…051 no piden. |
| Guardar el código en claro o en el registro de idempotencia | Descartada: es un secreto portador; se sigue el patrón de hashes ya usado en sesiones y cargas. |
| Guardar la respuesta completa en la idempotencia | Descartada: congelaría un estado de Streaming caducado y retendría datos que no deben persistir. Se registra solo qué sala produjo el comando. |

## Consecuencias

Watch parties comparte disponibilidad y release con Core. Cada lectura de una sala consulta hasta cuatro veces a
Streaming; con sondeo de cliente cada pocos segundos y varios miembros esto multiplica la carga sobre su lectura pública y
debe medirse. La caída de Streaming no impide ver la sala ni sus canales, pero sí añadir fuentes.

El selector del propietario ofrece las 50 emisiones más populares de Discovery (sin búsqueda ni paginación), de modo que con más de 50
emisiones en vivo no podría elegir las demás desde la interfaz. Un miembro que vuelve a entrar con el código a una sala ya cerrada recibe
`409 WATCH_PARTY_CLOSED`: una sala cerrada no admite entradas y la consulta a quien ya es miembro sigue disponible por su enlace.

No hay listado de salas del usuario: se accede por su enlace o con el código, y Web recuerda en el navegador solo el
identificador y el título de salas recientes como comodidad. No hay tope de salas por usuario ni límite de tasa específico:
el código de 256 bits hace inviable adivinarlo y la creación queda para una política posterior. Las salas cerradas y sus
membresías no se borran. El estado `UNKNOWN` por frescura depende del reloj de Streaming.

La suspensión oculta la fuente en la sala, pero no corta por sí sola el HLS que un cliente ya conozca; ese corte pertenece a
Streaming/Media y queda pendiente en SPEC-23. Sin sincronía, cada participante puede ver puntos distintos de cada emisión.
Reconstruir o mover el módulo a otro proceso exigirá un ADR que cubra los datos de `watchparty`.

## Verificación

Ver la evidencia ejecutada registrada en [SPEC-21](../spec-p2/spec_21_watch_party.md#10-verificación): suite de Core con PostgreSQL
real, contratos, Web y un stack local aislado con cuatro emisiones RTMPS reales y navegador. Esta decisión sigue en propuesta: falta
la revisión de integración y el despliegue completo con Chat, VOD y Notificaciones.

## Revisión

Revisar si el número de miembros y fuentes hace costosa la lectura repetida a Streaming (una caché breve en Core o un
agregado), si el producto pide sincronía, chat o invitaciones nominales, si se necesita listar salas, un tope por usuario o
retirada automática, o si el módulo debe separarse de Core. Revisa: responsable de Watch parties con Core, Gateway, Web,
Streaming e Integración.
