# SPEC-01 — Cuentas, recuperación y sesión web/móvil

**Prioridad:** P2. **Responsable:** Core / Cuentas. **Estado:** diseño para desarrollo.
**RF:** RF-001…007, 076, 079. **RNF:** RNF-022…032, 038, 041…050.

## 1. Contexto y problema

Cuentas debe conservar el registro transaccional y la sesión existente, permitir recuperación
de credenciales y autenticar al cliente móvil sin mezclar tokens de servicio con usuarios.

## 2. Definición del componente

Módulo Java de Core con PostgreSQL y seguridad comunes. Posee credenciales, sesiones, roles,
versiones de cuenta, tokens de recuperación y material privado de entrega. Perfil sigue local.

## 3. Historia de usuario

Como usuario, quiero recuperar mi cuenta y usar web y móvil con los mismos permisos y revocación.

## 4. Alcance

Conserva registro/perfil/CSRF y límites de [SPEC-01 base](../spec-p1/spec_01_auth.md). Añade
login/logout móvil, recuperación de un uso, revocación por suspensión y contexto de acceso.
No se añade JWT, refresh token, verificación de email o cambio de handle por esta SPEC.

## 5. Requisitos funcionales

- Registro confirma cuenta/perfil/canal localmente y no depende del broker.
- Web usa cookie HttpOnly/CSRF; móvil recibe sesión opaca mediante Bearer y vida fija de 24 h.
- Pedir recuperación devuelve respuesta uniforme exista o no la cuenta; la entrega es asíncrona.
- Confirmar token válido cambia contraseña una vez y revoca sesiones anteriores.
- Solo un rol administrativo provisionado puede suspender/reactivar; permisos se validan en Core.

## 6. Criterios de aceptación

- [ ] CA-01: las pruebas base de unicidad, normalización, registro/rollback, perfil, cookie y cuotas siguen pasando.
- [ ] CA-02: login móvil válido devuelve `P2NativeLogin` por HTTPS/no-store, sin cookie obligatoria ni secreto de servicio.
- [ ] CA-03: logout, expiración y suspensión invalidan web/móvil y el siguiente contexto de Chat/Streaming.
- [ ] CA-04: solicitudes de recuperación para cuenta existente/inexistente devuelven el mismo status/cuerpo y no filtran existencia mediante errores.
- [ ] CA-05: token vencido, usado o manipulado no cambia contraseña; dos confirmaciones concurrentes solo permiten un uso.
- [ ] CA-06: después de confirmar, la contraseña anterior y sesiones anteriores dejan de servir; se conserva perfil/canal.
- [ ] CA-07: reinicio antes/después de encolar correo no pierde la responsabilidad durable ni revela el token en broker/logs.
- [ ] CA-08: la credencial privada de entrega solo accede al recoveryId autorizado y nunca a sesiones/perfiles arbitrarios.

## 7. Diseño técnico y datos

Nuevos datos: recuperación con ID, hash del token, vencimiento, uso y versión; outbox de entrega;
material temporal de email protegido y asociación deliveryId. La política de duración, cuotas,
invalidación de solicitudes previas y protección del material reversible se cierra en P2-15.
La contraseña mantiene el contrato de 12–128 puntos de código sin normalización de P1.

La sesión nativa es del mismo tipo y autoridad que la web. Sus tokens no entran en URLs.
`X-Session-Credential` privado lleva la credencial opaca al contexto Core únicamente dentro de
una llamada autenticada de servicio. Chat valida el Bearer nativo al Upgrade y cada mensaje
nuevo usa el contexto actual; el primer handshake no concede permisos indefinidos.

La confirmación de recuperación verifica/consume el token y cambia hash/revoca sesiones en
una transacción. El job de email referencia recoveryId; un worker obtiene material solo por
`p2.recovery.delivery-claim`, verifica expiración y registra outcome por deliveryId.

## 8. Dependencias y contratos de integración

`p2.identity.*`, `p2.recovery.*` y `p2.access.context` en [contratos HTTP](../contratos_p2_http.md).
`credential.recovery.delivery.requested.v1` va al worker SMTP; [SPEC-20](spec_20_notifications.md)
no recibe autoridad para cambiar contraseña. Los contratos base de registro/perfil permanecen.

## 9. Decisiones y preguntas abiertas

P2-14: adaptación de clientes y WS a Bearer; su transporte HTTP ya está especificado.
P2-15: duración/cuotas y protección de material de recuperación antes de habilitar RF-004.
El endpoint privado de claim es diseño de interfaz a ratificar entre Cuentas/Notificaciones.

## 10. Verificación

Cubrir tokens usados/expirados, concurrencia, reintento y fallos entre commit/encolado. Probar
revocación cruzada web/móvil y sesión suspendida; inspeccionar respuestas y logs sin secretos.
La forma de schemas se valida con `contracts/check_p2.py`; ejecución Core requiere sus pruebas.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo M/L. El principal riesgo es tratar la autenticación nativa como credencial entre
servicios o perder privacidad en recuperación. Registro y perfil conservan su transacción local.
