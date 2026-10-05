# ADR-007: Watch Party como módulo de Core

- Estado: propuesta
- Fecha: 2026-10-05
- Responsable: Core / Watch Party
- SPEC/contratos afectados: SPEC-14; RF-046…RF-051; contratos_modelo_datos, mapa de responsabilidades, fases futuras y tabla de rutas del proxy.

## Contexto

RF-046…RF-051 piden crear una sesión de visualización conjunta, agregar y retirar transmisiones activas,
mostrarlas simultáneamente, permitir que otros usuarios accedan y mostrar información de cada canal.
El catálogo los conserva como Futuro y [fases futuras](../fases_futuras.md) asigna Watch Party a un módulo
de Core: membresía y política de acceso en una transacción local, referencias a `streamId`/`sessionId` y
sincronización según una SPEC posterior. RF-049 se cumple con varios reproductores; no exige mezclar video.

El equipo decidió implementar el backend de esta capacidad antes de conectar Suscripciones/Premium. Los
documentos solo dicen «varias» transmisiones y «usuario autorizado», por lo que las reglas concretas
(tope, quién crea, quién entra, quién edita, qué pasa al terminar un stream) se acordaron el 2026-10-05 y
quedan registradas en SPEC-14.

## Decisión

1. **Ubicación.** Módulo `watchparty` dentro de Core (`services/core/.../streaming/core/watchparty`), con capas
   `api`, `application`, `domain` e `infrastructure` como Canales y Catálogo. Java 25, Spring Boot, JDBC,
   Flyway y PostgreSQL de Core. No se crea otro runtime, base ni servicio.
2. **Datos.** Esquema `watchparty` (migración V5) con `parties`, `party_streams` y `party_members`. Las FK a
   `identity.accounts` son locales y válidas dentro de Core. `streamId` y `channelId` de una transmisión son
   referencias opacas validadas por contrato al escribir; no hay FK hacia datos de Streaming.
3. **Reglas de producto (acordadas 2026-10-05).** Crea cualquier usuario con sesión; máximo 4 transmisiones
   por sesión; entrar requiere cuenta y el código de acceso; solo el creador agrega/retira transmisiones y
   cierra; una transmisión finalizada permanece marcada como finalizada hasta que el creador la retire;
   solo se agregan transmisiones en vivo y reproducibles; la sesión no caduca sola.
4. **Código de acceso.** Secreto opaco de 256 bits. Solo se guarda su SHA-256; se muestra una vez al crear o
   rotar. Rotarlo invalida el anterior sin expulsar a los miembros actuales.
5. **Estado de las transmisiones.** Streaming es la autoridad. Core lee `GET /api/streams/{streamId}`
   (lectura pública, sin credenciales) con timeout acotado y en paralelo. Agregar falla cerrado
   (`STREAMING_UNAVAILABLE`); la lectura de la sesión degrada a `availability=UNKNOWN`/`statusFresh=false`
   y conserva el resto, igual que el bootstrap de canal.
6. **Datos de canal.** Se componen con la interfaz local publicada `ChannelQueries` (se añade la lectura
   aditiva `byChannelId`). No se leen tablas de Canales, Cuentas ni Perfil.
7. **Sin eventos ni tiempo real.** No hay outbox, WebSocket ni sincronización de reproducción en esta
   decisión. El cliente consulta la sesión por REST. Una futura sincronización requiere otra SPEC/ADR.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Módulo Core con REST y PostgreSQL | Elegida: membresía y límites son invariantes locales; mismo build, seguridad y transacciones; cumple fases futuras y AGENTS (sin runtime nuevo). |
| Servicio independiente Go + WebSocket + Redis (propuesta del documento de trabajo) | Descartada: ningún RF exige tiempo real; añade runtime, credenciales y operación sin necesidad medida (criterios de extracción de fases futuras). |
| Kotlin/Spring aparte | Descartada: Core ya es Java; otro lenguaje dentro del mismo build no aporta. |
| Alojarlo en Streaming Rust | Descartada: Streaming posee emisiones, no grupos de usuarios; mezclaría propiedad de datos. |
| Retirada automática al terminar un stream (evento Streaming→Core) | Aplazada: Streaming solo publica a Chat y Discovery; un tercer consumidor exige contrato y outbox nuevos. Se resuelve marcando la transmisión como finalizada. |
| Guardar el código de acceso en claro | Descartada: es un secreto portador; se sigue el patrón de hashes ya usado (sesiones, uploads). |

## Consecuencias

Watch Party comparte disponibilidad y release con Core. Cada lectura de una sesión realiza hasta cuatro
llamadas HTTP a Streaming; su caída no impide ver la sesión ni los canales, pero sí agregar transmisiones.
El estado de cada transmisión es el observado al leer, sin garantía de frescura de Discovery. Sin sesión
activa de Streaming (PR #7 aún no integrado) la integración solo puede verificarse contra el contrato con
un servidor simulado. El código de acceso, si se pierde, solo se recupera rotándolo. No hay límite de
sesiones por usuario; se revisará con uso real.

## Verificación

Pruebas unitarias de reglas y casos de uso; pruebas de integración con PostgreSQL desechable (Testcontainers)
y un servidor HTTP simulado de Streaming: creación, tope de 4 con concurrencia, duplicados, propietario/ajeno,
acceso por código y rotación, cierre, degradación con Streaming caído, privacidad de DTO y CSRF. La migración V5
se prueba sobre una base V4 con datos. Esta decisión no acredita RF-046…RF-051 hasta ejecutar esa evidencia
y la integración real con Streaming.

## Revisión

Revisar si se pide sincronización de reproducción o presencia (posible servicio de tiempo real), si Streaming
publica eventos de fin de sesión útiles, si Premium restringe quién crea sesiones, o si el volumen de lecturas
a Streaming exige usar su batch privado. Revisa: responsable de Watch Party con Core y Streaming.
