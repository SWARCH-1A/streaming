# SPEC-20 — Bandeja y entrega de notificaciones

**Prioridad:** P2. **Responsable:** Notificaciones. **Estado:** implementado en [services/notifications](../../services/notifications/README.md) (TypeScript/Node.js 24, PostgreSQL, SMTP); push real pendiente de P2-13.
**RF:** RF-004, 014…016, 052…055, 079. **RNF:** RNF-020, 022…032, 038, 041…047, 050…051.

## 1. Contexto y problema

Los seguidores reciben avisos de nuevas emisiones según preferencias. La bandeja, correo y
push necesitan entrega independiente, deduplicación y recuperación sin bloquear la emisión.

## 2. Definición del componente

Servicio y workers con persistencia propia de bandeja, leído, preferencias, instalaciones,
avisos lógicos e intentos. Consume el broker compartido y usa adaptadores SMTP/push. Core
conserva la relación autoritativa de seguimiento y los secretos de recuperación de cuenta.

## 3. Historia de usuario

Como seguidor, quiero elegir qué canales me avisan y por qué medio, consultar mi bandeja y
marcar avisos leídos sin recibir duplicados por reconexiones.

## 4. Alcance

Avisos por nueva sesión LIVE, bandeja paginada, leído, preferencias por canal y medio, dispositivos,
colas de entrega y recuperación SMTP de RF-004. No se crea otro broker. Las políticas de avisos
muy atrasados quedan abiertas; la selección y concurrencia de preferencias se fijan en ADR-020.

## 5. Requisitos funcionales

- Una notificación lógica se identifica por tipo, destinatario y sessionId; reconectar no crea otra.
- Bandeja es el medio inicial; email/push requieren activación general y aviso habilitado del canal.
- Cada medio y destino tiene deliveryId y resultado independientes; no se reenvía otro medio ya completado.
- Las APIs operan sobre el usuario autenticado; un cliente no elige destinatarios ajenos.
- Un proveedor externo caído no revierte LIVE, follow ni bandeja ya confirmada.

## 6. Criterios de aceptación

- [x] CA-01: seguir un canal y confirmar una sesión LIVE crea un aviso lógico; reentregar el evento o reconectar no duplica bandeja.
- [x] CA-02: listado/leído son privados e idempotentes; cuenta ajena no lee ni modifica otro aviso.
- [x] CA-03: email/push desactivados por defecto no entregan; activar medio y canal habilita solo la entrega autorizada.
- [x] CA-04: fallo push conserva correo entregado y bandeja; su reintento no repite ambos.
- [x] CA-05: SMTP temporal falla/reintenta; permanente o destino inválido deja estado observable sin bucle infinito.
- [ ] CA-06: token push rotado actualiza la instalación; logout/cambio de cuenta elimina asociación anterior y un token inválido se deshabilita.
- [x] CA-07: reiniciar tras crear aviso y antes de encolar conserva trabajos; ACK perdido no duplica lógicamente el aviso.
- [x] CA-08: recuperación entrega un enlace de Core sin introducir token de restablecimiento en eventos/logs.
- [x] CA-09: un aviso antiguo puede permanecer como registro, pero abrirlo no permite acceder a contenido suspendido/retirado. (`notifications.account` por versión; `available=false` y sin enlace en la web; correo/push pendientes cancelados para destinatario o dueño suspendido.)

## 7. Diseño técnico y datos

El consumidor procesa únicamente el hecho `initialLive=true`, con un registro durable por
sessionId antes de seleccionar destinatarios. Reconectar no vuelve a seleccionar seguidores
aunque alguno haya comenzado a seguir después del inicio original.

Claves únicas: aviso `(kind,userId,sessionId)` y entrega `(notificationId,medium,targetId)`.
Cada deliveryId tiene propiedad exclusiva entre workers. La bandeja interrumpida se completa
localmente; solo los medios externos pueden quedar con resultado incierto tras un reinicio.
Estados de intento: pendiente, en curso, entregado, error reintentable, error permanente o
resultado desconocido. SMTP/push no garantizan exactly-once cuando se pierde una respuesta;
la deduplicación del sistema no debe ocultar esa incertidumbre externa.

Core entrega destinatarios paginados con snapshot consistente. La proyección de seguimiento
puede ayudar a consultar, pero no crea una relación nueva ni sustituye sus permisos. Las
preferencias tienen versión y updates condicionales. Se fija una instantánea de selección por
aviso para poder recuperar intentos; reanudar una página conserva los medios elegidos originalmente
según ADR-020. Un cambio posterior de preferencias se aplica a los nuevos avisos.

Tokens de proveedor y emails permanecen privados. El trabajo contiene targetId, no credenciales.
Las instalaciones se vinculan al principal; cambios de token son idempotentes y revocables.
Los jobs de recuperación usan recoveryId y un contrato privado limitado para material de email.

## 8. Dependencias y contratos de integración

APIs `p2.notifications.*`, jobs `notification.delivery.requested.v1` filtrados por medio y
`credential.recovery.delivery.requested.v1`, destinatarios Core y claim/result de recuperación.
[Contratos HTTP](../contratos_p2_http.md), [eventos](../contratos_p2_eventos.md) y schemas P2
fijan firmas y productores. Push requiere configuración externa y prueba en dispositivo.

## 9. Decisiones y preguntas abiertas

P2-12 (selección registrada en [ADR-020](../adr/ADR-020-notificaciones-typescript-postgresql-smtp.md)): Node.js 24 con TypeScript, PostgreSQL propio para bandeja,
preferencias, dispositivos e intentos, SMTP con STARTTLS y Mailpit como proveedor local. Preferencias
con versión y update condicional (409 si cambió); la selección lee las preferencias vigentes en la
misma transacción que crea el aviso. Token push inválido deshabilita la instalación. CA-06 queda
parcial (registro/rotación/baja sí; invalidez real depende del proveedor) y CA-09 depende de P2-17.
P2-13: proveedor y provisión push. La implementación de Cuentas no selecciona ni despliega
tecnologías de este módulo. P2-15 está resuelta por
[ADR-017](../adr/ADR-017-recuperacion-de-credenciales.md): Core protege y autoriza el material
temporal mediante claim/result y publica en `notifications.recovery.email` del RabbitMQ
compartido. El consumidor obtiene el material por TLS con la identidad lógica `recovery-email`
y reporta el resultado; no accede a tablas Core ni guarda dirección/token/enlace en eventos,
logs o en su registro durable de intentos. El consumidor y adaptador SMTP están en `services/notifications`; su aceptación integrada se acredita por separado.

## 10. Verificación

Ejecutar entrega real SMTP y push con destinatarios de prueba y bandeja persistente. Inyectar
fallo por medio, respuesta externa perdida, dispositivo inválido y reinicio entre commit/ACK.
Medir backlog/edad y revisar ausencia de direcciones, credenciales o enlaces privados en logs.
Cuentas dispone de pruebas de solicitud, confirmación, publicación y contratos privados.
`services/notifications/test/notifications.test.ts` verifica con PostgreSQL el consumidor, duplicados
concurrentes, rollback de idempotencia, versiones y recuperación de fases; Core y proveedores
son dobles en esa suite. `test/smtp.test.ts` verifica el adaptador contra Mailpit STARTTLS con CA
confiable y rechazo de certificado sin confianza. Estas pruebas no cierran por sí solas la
aceptación integrada completa de RF-004 ni la entrega push en dispositivo (P2-13).

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. Las credenciales/provisión push son dependencia externa. La entrega puede quedar
pendiente sin afectar emisión; la operación debe exponer qué medio falló y cómo recuperarlo.
