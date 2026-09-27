# SPEC-03 Canal público y propiedad P1

- **Módulo:** channels
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-008…RF-012, que separa canal de cuenta, perfil y sesión multimedia. Un canal representa la presencia pública del propietario y proyecta el estado real de Streaming.

## 2. Estado del sistema y brecha

Seguimiento (RF-014…RF-016) queda fuera de P1. Channels publica información del canal y proyecta los estados definidos por Identity y Streaming; los contratos entre esos dominios se encuentran en SPEC-10 y SPEC-11.

## 3. Historia de usuario

Como visitante o propietario, quiero consultar y mantener la página del canal, para encontrar al broadcaster y ver si está transmitiendo.

## 4. Alcance

### Dentro de P1

- Crear idempotentemente exactamente un canal por `ownerUserId` al completar registro. El canal no posee ni modifica handle; la ruta se resuelve con el handle canónico de Identity.

- Editar descripción e imagen de portada del canal. Portada opcional, JPEG/PNG/GIF, máximo 10 MB, tamaño recomendado 1200×480 px.

- Consultar la página pública, estado LIVE/OFFLINE y stream activo.

- Mostrar solo emisiones en vivo en la sección de streams de canal de P1; VOD se documenta fuera de P1.

### Fuera de P1

- Seguir/dejar de seguir/listar canales seguidos; catálogo VOD del canal; configuración avanzada.

- Cambiar handle o nombre visible; esos datos pertenecen a Identity/Profile en P1.

### Supuestos acordados

- Streaming es fuente autoritativa de estado de sesión; Channels puede mantener una proyección de lectura.

- La portada pertenece al canal; avatar/nombre visible pertenecen a Profile.

## 5. Requisitos funcionales

- RF-008 crear el canal al completar registro; una cuenta posee uno.

- RF-009 propietario edita descripción y portada.

- RF-010 página pública de canal consultable sin login.

- RF-011 reflejar estado de emisión según sesión de Streaming.

- RF-012 mostrar streams activos asociados; no mostrar catálogo VOD en P1.

## 6. Criterios de aceptación

- CA-01 — al completarse registro ACTIVE existe exactamente un canal por `ownerUserId`; repetir solicitud de provisión devuelve el mismo `channelId`. Handle se resuelve en Identity, no como dato autoritativo de Channels.

- CA-02 — propietario cambia descripción/portada; usuario distinto recibe 403; inválidos conservan valor anterior.

- CA-03 — visitante abre `/channels/{handle}`; el shell resuelve handle canónico en Identity y muestra nombre visible/avatar desde Profile, descripción/portada desde Channels y estado/disponibilidad actual.

- CA-04 — el estado se actualiza dentro de 5 segundos tras evento/confirmación de Streaming.

- CA-05 — LIVE muestra el stream activo; OFFLINE muestra estado offline sin inventar VOD.

- CA-06 — lectura de canal solo resuelve cuenta ACTIVE; PENDING/EXPIRED y userId inexistente reciben el mismo 404. `channelVersion` inicia en 0, sube exactamente uno por cambio persistido efectivo de descripción/portada y no cambia en PATCH sin diferencias; `ChannelProvisioned` y lecturas incluyen su valor.
- CA-07 — un PATCH parcial actualiza atómicamente el estado más reciente; cambios concurrentes aceptados en campos distintos se conservan y para el mismo campo prevalece el commit serializado más reciente. Una validación o persistencia fallida no incrementa versión ni reemplaza campos.
- CA-08 — Channels persiste registrationId único junto al canal y acepta creación solo si la cerca atómica confirma `serverNow < pendingUntilUtc`; vencido devuelve `410 REGISTRATION_EXPIRED`. Si Identity pierde la respuesta, lookup por registrationId tras el deadline devuelve PROVISIONED o ABSENT terminal y asegura que ninguna creación en vuelo se confirmará después. Identity puede borrar solo por registrationId; retry devuelve 204 si ya no existe. Nunca reactiva ni publica EXPIRED.
- CA-09 — lecturas públicas requieren identidad ACTIVE. Al resolver un handle a ACTIVE, si `GET /api/channels/by-owner/{userId}` aún responde 404 porque la proyección no procesó `IdentityPublicChanged`, shell reintenta tras 100/250/500/1000 ms dentro de 2 s; después muestra estado temporal “canal activándose” con acción manual de reintento, no 404 definitivo. PENDING/EXPIRED siguen dando el mismo 404 indistinguible.

## 7. Diseño técnico y datos

- Propiedad: `channelId`, `ownerUserId`, `description`, banner URI, createdAt/updatedAt. Identity es fuente autoritativa de handle; Channels consume `ownerUserId` y solo puede mantener un alias de búsqueda derivado/versionado si su implementación lo necesita. El alias nunca acepta escrituras como dueño ni resuelve conflictos contra Identity. Los datos de Profile y Stream no se duplican como fuentes de verdad.
- `IdentityPublicChanged` activa la proyección consultable; antes de ese evento un canal provisionado no es visible y GET devuelve el mismo 404 que PENDING/EXPIRED. Channels guarda registrationId con la provisión y publica `ChannelProvisioned` durable por outbox/equivalente. Identity que pierde el compare-and-set por vencimiento consulta estado por registrationId y ejecuta DELETE idempotente por esa misma clave.

- `POST /internal/channels/provision` es invocable solo por Identity mediante HTTPS/TLS con autenticación de servicio en red privada; es idempotente por `registrationId` y devuelve el mismo `channelId` al repetir. `GET/DELETE /internal/channels/provisions/{registrationId}` dan estado/reparación aun si Identity no recibió el channelId. No se enrutan por el proxy público. La consulta del shell resuelve `/channels/{handle}` vía Identity y luego obtiene Channels por `ownerUserId`, componiendo perfil y sesión.

- Registro cross-module debe ser idempotente y no exponer canal huérfano; elegir transacción local/evento/reconciliación en ADR.

- ADR: estrategia de proyección de estado, persistencia relacional/cache y ruta estable del canal.

## 8. Dependencias y contratos de integración

- Identity inicia provisión de canal al registrar cuenta; requiere `ownerUserId`. Identity conserva el handle canónico y comparte handle solo para resolver URL; Channels no decide unicidad.

- Profile aporta displayName/avatar.

- Streaming publica streamId/channelId/status/title/category/viewerCount; Channels no controla ingestión.

- Frontend y reverse proxy deben compartir ruta de canal basada en handle estable.

## 9. Decisiones y preguntas abiertas

**Decisiones:** un canal por cuenta, creado automáticamente; handle URL estable; Channels posee descripción/portada y proyecta el estado cuyo dueño es Streaming; RF-012 cubre streams LIVE y RF-013 el catálogo VOD futuro.

**Abierto:** no hay preguntas de producto bloqueantes. La persona responsable debe registrar el ADR y cerrar los detalles de implementación listados en Diseño.

## 10. Verificación

- registro repetido y provisión de canal sin duplicados.

- lectura pública y edición de propietario frente a denegación a otro usuario.

- proyección de LIVE/OFFLINE y SLA de frescura 5 s.

- validación de imágenes y página sin datos no disponibles.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. **Riesgos:** provisión inconsistente distribuida, doble fuente de verdad de estado, URL/handle no estable y acoplamiento a la base de Streaming. **Consecuencia:** no implementar capacidades explícitamente fuera de P1.
