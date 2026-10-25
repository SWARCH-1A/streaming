# Interfaz de cuentas

Este módulo contiene registro, login, recuperación de contraseña y edición de perfil. Las acciones usan las APIs Core de
identidad y perfil, incluidos CSRF y uploads de avatar. La sesión común se gestiona en el shell.

## Organización

La entrada pública es `entry.tsx`. Los consumidores usan esa entrada y los contratos del módulo;
sus componentes y estado interno permanecen encapsulados. El [shell](../../shell/README.md)
organiza las rutas globales y el layout.

La [SPEC P2](../../../../../docs/spec-p2/spec_01_auth.md) define el comportamiento de recuperación;
la SPEC base conserva registro/perfil. `/forgot-password` solicita el correo con respuesta uniforme
y `/reset-password` confirma el token de un uso recibido en el fragmento del enlace. La vista retira
ese fragmento de la URL, conserva el token en memoria y limpia los secretos al terminar. Solicitud
y confirmación usan CSRF e Idempotency-Key; los tipos y la respuesta se validan desde contratos P2.
Core publica la solicitud en RabbitMQ; el consumidor y envío del correo están pendientes de
Notificaciones. Estas vistas y la aceptación de la solicitud no acreditan entrega SMTP.
[SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md) fija los criterios de accesibilidad
de sus recorridos. Los comandos están en el [README Web](../../../README.md),
y las pruebas locales se mantienen junto al código.
