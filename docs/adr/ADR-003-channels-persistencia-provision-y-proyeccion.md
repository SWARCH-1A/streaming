# ADR-003: Persistencia, provisión y proyección de estado del módulo Channels

- Estado: propuesta
- Fecha: 2026-09-30
- Responsable: Channels
- SDD/contratos afectados: SPEC-03 (RF-008…RF-012; CA-01–CA-09); SPEC-10 (propiedad de datos, API y errores); SPEC-11 (provisión Identity→Channels y eventos `StreamSession*`); SPEC-13 (volumen persistente y reinicio aislado); contratos `GET /api/channels/by-owner/{userId}`, `PATCH /api/channels/{channelId}`, `POST /api/channels/{channelId}/banner-uploads`, `/internal/channels/provision*`, `ChannelProvisioned` y `ChannelChanged`.

## Contexto

Channels es dueño de `channelId`, `ownerUserId` (referencia externa), descripción, portada y `channelVersion`. Identity es autoridad del handle y del estado de la cuenta; Profile, de nombre visible y avatar; Streaming, del estado de la sesión. SPEC-03 exige registrar en ADR la estrategia de proyección de estado, la persistencia relacional/caché y la ruta estable del canal, además del patrón que evita canales huérfanos en el registro distribuido.

Identity ya invoca `POST /internal/channels/provision`, `GET` y `DELETE /internal/channels/provisions/{registrationId}` en el puerto `8083`, con `X-Service-Name: identity` y `X-Service-Token`. Identity y Profile usan Java 25, Spring Boot 4.1.1, PostgreSQL 18 y Flyway (ADR-001, ADR-002).

## Decisión

1. **Plataforma:** Java 25, Spring Boot 4.1.1 y Maven Wrapper, igual que Identity y Profile. Proceso independiente en el puerto `8083`, con health en `/actuator/health`.
2. **Datos:** PostgreSQL 18 con esquema `channels`, propiedad exclusiva del módulo y creado por Flyway. Contiene las tablas del canal, la cerca de provisión, los permisos de upload, la proyección de stream, los eventos procesados y el outbox. `owner_user_id` es único y no tiene FK hacia otros dominios. No se añade caché: la lectura es una consulta por clave única y RNF-011 no exige más en P1.
3. **Provisión idempotente con cerca:** todas las operaciones sobre un mismo `registrationId` se serializan con `pg_advisory_xact_lock`. La creación se acepta solo si `serverNow < pendingUntilUtc` (el primer plazo recibido queda registrado y no se extiende) y persiste en la misma transacción el canal, `registration_id` único y `ChannelProvisioned` en el outbox. La repetición devuelve el mismo `channelId`. El lookup espera cualquier creación en vuelo y responde `PROVISIONED` o `ABSENT`; `ABSENT` queda registrado como cerca terminal, así que una provisión posterior con esa clave devuelve `410 REGISTRATION_EXPIRED`. `DELETE` es idempotente por `registrationId` y deja la cerca `DELETED`.
4. **Visibilidad pública (`IdentityPublicChanged`):** mientras Integración no defina el transporte de eventos, la lectura pública consulta `GET /api/identity/public/users/{userId}` y solo expone el canal si Identity responde ACTIVE. Es el mismo patrón que usa Profile en ADR-002. Una cuenta desconocida, PENDING o EXPIRED devuelve el mismo `404 CHANNEL_NOT_FOUND`. Si Identity no está disponible, la respuesta es `503 IDENTITY_UNAVAILABLE`, no `404`.
5. **Proyección de estado de Streaming:** Channels guarda una proyección de lectura (`stream_projections`), nunca la fuente de verdad. La alimentan `StreamSessionStarted`, `StreamSessionAvailabilityChanged`, `StreamSessionEnded`, `StreamMetadataUpdated` y `ViewerCountChanged`, con el sobre común del contrato transversal. Se deduplica por `eventId` y el orden se aplica así:
   - el ciclo de vida, por mayor `streamGeneration` y luego `sessionVersion`;
   - la metadata, por `metadataVersion`;
   - el conteo, por `countVersion` dentro de la sesión vigente.

   El canal está `LIVE` si la disponibilidad es `PLAYABLE` o `RECONNECTING` ("en vivo · reconectando"); en cualquier otro caso está `OFFLINE` y no se muestra un stream activo ni VOD.
6. **Entrega de eventos:** el módulo expone el adaptador `POST /internal/channels/stream-events`, que recibe esos eventos por HTTPS en red privada y solo acepta el servicio `streaming`. Responde `200` con `APPLIED`, `DUPLICATE`, `STALE` o `IGNORED`, de modo que los reintentos son seguros. La lógica de proyección no depende del transporte: si Integración elige un broker, se agrega otro adaptador sin cambiar la aplicación.
7. **Portada:** se usa el mismo ciclo que el avatar de ADR-002, en el directorio `CHANNELS_BANNER_STORAGE` (volumen persistente, con `pending/` y `public/`). Se valida en servidor que sea JPEG/PNG/GIF real de hasta 10 MB. `1200×480` es solo una recomendación, así que no se rechazan otras dimensiones; solo hay un límite defensivo de 40 MP. El `uploadId` es de un solo uso, vence a los 15 minutos y queda ligado al owner y al `channelId`. Los archivos se sirven por `GET /api/channels/banners/{key}`, con clave inmutable y caché pública.
8. **Edición:** `PATCH` bloquea la fila (`SELECT … FOR UPDATE`) y aplica solo los campos presentes sobre el estado más reciente. `channelVersion` sube exactamente uno si cambia la descripción o la portada, y en ese caso se escribe `ChannelChanged` en el outbox. Un PATCH sin diferencias no cambia la versión. Un error de validación o de persistencia no modifica nada.
9. **Ruta estable:** el canal se lee por `ownerUserId`. El shell resuelve `/channels/{handle}` con Identity y luego llama a Channels, que no guarda ni decide el handle.

## Opciones consideradas

| Decisión | Opción elegida | Alternativas y motivo para no elegirlas en P1 |
| --- | --- | --- |
| Lenguaje/framework | Java + Spring Boot, igual que Identity y Profile | Kotlin o Go cumplirían la API y sumarían lenguaje al requisito global, pero Discovery ya aporta Kotlin y Streaming/Chat son candidatos a Go. Repetir el stack reutiliza seguridad, introspección y despliegue ya probados. |
| Datos | PostgreSQL con esquema propio | NoSQL no aporta nada a un registro 1:1 con unicidad por dueño, cerca transaccional y outbox. Redis como caché sería otro servicio sin una necesidad medida. |
| Anti-huérfanos | Cerca por `registrationId` con lock transaccional y lookup terminal | Un 2PC entre Identity y Channels no está disponible. Un TTL de limpieza sin cerca podría confirmar una creación después del lookup (viola CA-08). |
| Visibilidad pública | Lookup síncrono en Identity en cada lectura | Consumir `IdentityPublicChanged` requiere un transporte todavía no decidido. El lookup cuesta una llamada extra por lectura pública; cuando exista transporte se puede sustituir por una proyección local. |
| Estado del stream | Proyección por eventos con versión y deduplicación | Consultar Streaming en cada lectura acopla la disponibilidad de la página a Streaming y no hay endpoint público por `channelId`. Leer su base viola la propiedad de datos. |

## Consecuencias

**Beneficios:**
- Identity ya compatible sin cambios.
- Un solo canal por cuenta, garantizado en la base.
- Reintentos y eventos repetidos seguros.
- La página funciona con Streaming caído: muestra el último estado proyectado.
- Portada con las mismas garantías que el avatar.

**Costos y fallos:**
- La lectura pública depende de Identity (fail-closed `503`).
- La frescura de 5 s de RNF-014 depende de que el transporte entregue los eventos a tiempo.
- La portada requiere un volumen persistente y compartido si hay varias réplicas.
- `ChannelProvisioned`/`ChannelChanged` quedan en el outbox hasta que Integración implemente el despachador.

**Compatibilidad:**
- Las rutas y payloads siguen `contratos_modelo_datos.md`.
- La respuesta pública agrega `status` (`LIVE`/`OFFLINE`) y `activeStream` como forma concreta de la "proyección pública de stream".
- El reverse proxy no debe publicar `/internal/*`.
- `/api/channels/{channelId}/streams` pertenece a Streaming y el proxy lo enruta allí.

**Requisitos globales:** este ADR no declara cumplidos el requisito NoSQL, los tres lenguajes ni los dos conectores HTTP. Esas decisiones son de Integración.

## Verificación

- CA-01/CA-08: provisión repetida con el mismo `registrationId` devuelve el mismo `channelId`. Vencido el plazo da `410`. Lookup tras el deadline devuelve `PROVISIONED` o `ABSENT` terminal, y `DELETE` repetido da `204`.
- CA-02/CA-07: owner edita, otro usuario recibe `403`, entradas inválidas conservan el estado y dos PATCH concurrentes en campos distintos conservan ambos cambios.
- CA-04/CA-05: un `StreamSessionStarted` con `PLAYABLE` pone el canal en `LIVE` en ≤5 s tras la entrega; `StreamSessionEnded` lo pone en `OFFLINE`; eventos repetidos o viejos no retroceden el estado.
- CA-06: una cuenta PENDING/EXPIRED o inexistente da el mismo `404`; `channelVersion` empieza en 0 y sube solo con cambios efectivos.
- Evidencia registrada al preparar este ADR (2026-09-30): verificación manual con `curl` contra el JAR (`./mvnw -B -q -Djava.version=21 -DskipTests package`, JDK local 21; la imagen usa 25) y PostgreSQL 18 en contenedor. Como Identity no estaba operativo, se usó un simulador local de introspección y lookup. Resultados:
  - Flyway crea el esquema.
  - CA-01: 201 y luego 200 con el mismo `channelId`.
  - CA-08: 410 si venció; lookup `PROVISIONED`/`ABSENT` terminal con 410 posterior; `DELETE` repetido da 204.
  - CA-03/CA-06: lectura pública y 404 idéntico.
  - CA-02: 401 sin sesión, 403 sin CSRF u otro usuario, 400 con 501 caracteres o campo ajeno.
  - `channelVersion` no sube sin cambios.
  - Portada: falsa da 400, válida da 201, el `uploadId` es de un solo uso y el archivo se sirve.
  - Eventos: `APPLIED`/`DUPLICATE`/`STALE`, `RECONNECTING` sigue LIVE, `Ended` pone OFFLINE, servicio incorrecto da 403.
  - Los datos persisten tras reiniciar el proceso.
  - Con Identity caído la lectura da 503.
  
  - `docker build` genera la imagen (Java 25). El contenedor arranca con PostgreSQL en red Docker, `/actuator/health` responde UP y la provisión devuelve 201.
  
  No hay pruebas automatizadas ni verificación integrada con Identity real.

## Revisión

Channels revisará esta decisión cuando Integración elija el transporte de eventos (para consumir `IdentityPublicChanged` y sustituir o complementar el adaptador HTTP), antes de ejecutar más de una réplica, o si RNF-011 exige caché. Integración aprueba los cambios de proxy, volumen y despliegue.
