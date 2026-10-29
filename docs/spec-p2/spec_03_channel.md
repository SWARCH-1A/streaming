# SPEC-03 — Canales y seguimiento

**Prioridad:** P2. **Responsable:** Core / Canales. **Estado:** implementado parcialmente: CA-07 depende de P2-17 y la copia de CA-08 requiere integración con Streaming.
**RF:** RF-008…016, 077, 080. **RNF:** RNF-014, 022…032, 038…042, 046…050.

## 1. Contexto y problema

Los usuarios siguen canales y el Gateway necesita componer seguimiento y biblioteca con el
canal público. VOD conserva la autoridad de grabaciones; Core conserva canal y relaciones.

## 2. Definición del componente

Módulo Core con repositorio SQL local. Cuenta/perfil/canal siguen compartiendo transacción de
registro. Seguimiento es relación entre userId y channelId; no se crea un servicio de grafo.

## 3. Historia de usuario

Como usuario, quiero seguir/dejar de seguir canales y consultar mi lista sin duplicados.

## 4. Alcance

Canal/portada/propiedad de [SPEC-03 base](../spec-p1/spec_03_channel.md), seguimiento idempotente,
consulta paginada y destinatarios privados para Notificaciones. La preferencia de autopublicación
se propone como configuración del canal y debe ratificarse con VOD antes de fijar su persistencia.

## 5. Requisitos funcionales

- Un usuario autenticado sigue o deja de seguir; repetir mantiene la misma relación final.
- El listado es privado del solicitante y solo expone canales conforme a disponibilidad.
- La biblioteca se obtiene de VOD; Core no consulta ni escribe su base.
- Cambios de seguimiento generan evento durable con versión; fallo de Notificaciones no revierte follow.

## 6. Criterios de aceptación

- [x] CA-01: registro sigue creando exactamente un canal; edición/portada conservan permisos y validación base.
- [x] CA-02: dos PUT follow concurrentes producen una sola relación; repetir PUT/DELETE no duplica el cambio ni versión.
- [x] CA-03: visitante no modifica follow; el userId se obtiene del principal y no del cuerpo.
- [x] CA-04: lista seguida y estado por canal concuerdan tras commit y se paginan sin repetir ni omitir dentro del snapshot.
- [x] CA-05: relación y outbox confirman juntos; reiniciar o perder ACK no pierde el aviso de cambio.
- [x] CA-06: destinatarios solo son accesibles por credencial privada limitada, sin devolver email ni tokens.
- [x] CA-07: canal suspendido aparece indisponible y no permite eludir acceso a su live/VOD. (Canales/Core: seguir, estado, destinatarios y vod-settings responden 404; lista seguida lo oculta conservando la relación; `FollowIT`. El corte de live/VOD lo ejecutan Streaming/VOD según SPEC-23/P2-17.)
- [ ] CA-08: autopublicación por defecto es true, update concurrente usa versión y una sesión iniciada conserva su copia.

La API de autopublicación conserva el valor y su versión; este checkout todavía no implementa
la copia al iniciar sesión en Streaming. CA-08 permanece abierta hasta verificarla.

## 7. Diseño técnico y datos

Unicidad `(followerUserId,channelId)` con FK locales y versión monotónica conservada también
tras unfollow; borrar la fila sin conservar versión no permite descartar eventos atrasados.
Las lecturas privadas usan paginación por cursor opaco ligado al solicitante/filtro. El corte de
destinatarios tiene snapshotId/capturedAtUtc; páginas siguientes conservan el mismo corte y
cursor expirado devuelve 410 para reiniciar, sin mezclar inventarios.

El bootstrap Core base puede conservar compatibilidad; el agregado de pantalla pertenece al
Gateway. Configuración VOD: `enabled` y `version`, default true, copiados al abrir sesión. El
lugar de almacenamiento debe ratificarse; Streaming la consulta al abrir sesión mediante
`p2.recording.preference-internal`, sin requerir la cookie de un navegador; no hay dos preferencias autoritativas Core/VOD.

Las rutas públicas de seguimiento, listado de seguidos y configuración VOD aceptan la sesión
opaca por cookie web o Bearer móvil según SPEC-01. Web conserva CSRF; los comandos nativos
sin Cookie ni Origin usan Bearer validado y mantienen Idempotency-Key y autorización de dominio.

## 8. Dependencias y contratos de integración

`p2.channels.*`, `p2.recording.preference-*` y evento `channel.follow.changed.v1`.
Notificaciones consume relación/destinatarios según [SPEC-20](spec_20_notifications.md);
Gateway compone VOD/seguimiento en [SPEC-16](spec_16_gateway.md).

## 9. Decisiones y preguntas abiertas

P2-16 (propuesta implementada): un corte de destinatarios por canal/sesión, reutilizado y válido
1 h; cursores HMAC de 1 h; 410 al vencer. Falta ratificar cuotas y el trato de canal indisponible. P2-09: ratificar Core como dueño de la preferencia de canal. El contrato CRUD y
sus formas están en P2; no requiere decidir un nuevo lenguaje o motor.

## 10. Verificación

Probar usuario propio/ajeno/anónimo, duplicación, concurrencia, lista paginada, outbox y snapshot
con cambios de seguidores entre páginas. Repetir caso de registro con broker detenido.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo M. El snapshot de destinatarios y las versiones de una relación eliminada requieren
cuidado para evitar destinatarios repetidos o una proyección que resucite un follow anterior.
