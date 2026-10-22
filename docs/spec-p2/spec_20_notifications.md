# SPEC-20 — Bandeja y entrega de notificaciones

**Prioridad:** P2. **Responsable:** Notificaciones. **Estado:** diseño para desarrollo.
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
muy atrasados y cambios concurrentes de preferencia se cierran por el responsable.

## 5. Requisitos funcionales

- Una notificación lógica se identifica por tipo, destinatario y sessionId; reconectar no crea otra.
- Bandeja es el medio inicial; email/push requieren activación general y aviso habilitado del canal.
- Cada medio y destino tiene deliveryId y resultado independientes; no se reenvía otro medio ya completado.
- Las APIs operan sobre el usuario autenticado; un cliente no elige destinatarios ajenos.
- Un proveedor externo caído no revierte LIVE, follow ni bandeja ya confirmada.

## 6. Criterios de aceptación

- [ ] CA-01: seguir un canal y confirmar una sesión LIVE crea un aviso lógico; reentregar el evento o reconectar no duplica bandeja.
- [ ] CA-02: listado/leído son privados e idempotentes; cuenta ajena no lee ni modifica otro aviso.
- [ ] CA-03: email/push desactivados por defecto no entregan; activar medio y canal habilita solo la entrega autorizada.
- [ ] CA-04: fallo push conserva correo entregado y bandeja; su reintento no repite ambos.
- [ ] CA-05: SMTP temporal falla/reintenta; permanente o destino inválido deja estado observable sin bucle infinito.
- [ ] CA-06: token push rotado actualiza la instalación; logout/cambio de cuenta elimina asociación anterior y un token inválido se deshabilita.
- [ ] CA-07: reiniciar tras crear aviso y antes de encolar conserva trabajos; ACK perdido no duplica lógicamente el aviso.
- [ ] CA-08: recuperación entrega un enlace de Core sin introducir token de restablecimiento en eventos/logs.
- [ ] CA-09: un aviso antiguo puede permanecer como registro, pero abrirlo no permite acceder a contenido suspendido/retirado.

## 7. Diseño técnico y datos

El consumidor procesa únicamente el hecho `initialLive=true`, con un registro durable por
sessionId antes de seleccionar destinatarios. Reconectar no vuelve a seleccionar seguidores
aunque alguno haya comenzado a seguir después del inicio original.

Claves únicas: aviso `(kind,userId,sessionId)` y entrega `(notificationId,medium,targetId)`.
Estados de intento: pendiente, en curso, entregado, error reintentable, error permanente o
resultado desconocido. SMTP/push no garantizan exactly-once cuando se pierde una respuesta;
la deduplicación del sistema no debe ocultar esa incertidumbre externa.

Core entrega destinatarios paginados con snapshot consistente. La proyección de seguimiento
puede ayudar a consultar, pero no crea una relación nueva ni sustituye sus permisos. Las
preferencias tienen versión y updates condicionales. Se fija una instantánea de selección por
aviso para poder recuperar intentos; la política de cambios mientras está pendiente se ratifica
en P2-12 antes de cerrar entrega.

Tokens de proveedor y emails permanecen privados. El trabajo contiene targetId, no credenciales.
Las instalaciones se vinculan al principal; cambios de token son idempotentes y revocables.
Los jobs de recuperación usan recoveryId y un contrato privado limitado para material de email.

## 8. Dependencias y contratos de integración

APIs `p2.notifications.*`, jobs `notification.delivery.requested.v1` filtrados por medio y
`credential.recovery.delivery.requested.v1`, destinatarios Core y claim/result de recuperación.
[Contratos HTTP](../contratos_p2_http.md), [eventos](../contratos_p2_eventos.md) y schemas P2
fijan firmas y productores. Push requiere configuración externa y prueba en dispositivo.

## 9. Decisiones y preguntas abiertas

P2-12: runtime/almacén, caducidad de avisos atrasados, preferencias concurrentes y dispositivos.
P2-13: proveedor y provisión push. SMTP está disponible. P2-15: política del token de recuperación
Core y protección del material de entrega. No se selecciona proveedor push por esta SPEC.

## 10. Verificación

Ejecutar entrega real SMTP y push con destinatarios de prueba y bandeja persistente. Inyectar
fallo por medio, respuesta externa perdida, dispositivo inválido y reinicio entre commit/ACK.
Medir backlog/edad y revisar ausencia de direcciones, credenciales o enlaces privados en logs.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L. Las credenciales/provisión push son dependencia externa. La entrega puede quedar
pendiente sin afectar emisión; la operación debe exponer qué medio falló y cómo recuperarlo.
