# ADR-017: Recuperación de credenciales bajo la autoridad de Cuentas

- Estado: aceptada; Core/Web y publicación RabbitMQ implementados; entrega pendiente de Notificaciones
- Fecha: 2026-10-24
- Responsable: Core / Cuentas; integración de entrega a cargo de Notificaciones
- SDD/contratos afectados: SPEC-01 P2, SPEC-10, SPEC-20; RF-004; P2-15

## Contexto

RF-004 requiere recuperación por SMTP, respuesta uniforme, tokens de un uso y revocación
de sesiones. Las políticas de P2-15 se confirmaron con el responsable antes de implementar.
El contrato de P2 exige outbox, broker y worker de correo con credencial privada limitada.
El encargo de Cuentas termina en la publicación y los contratos privados de entrega. El
responsable de Notificaciones implementará el consumidor, persistencia de intentos y SMTP.

## Decisión

Cuentas conserva Java/Spring y PostgreSQL. Un token aleatorio de 256 bits, codificado en
base64url, vence a los 30 minutos. Una nueva solicitud aceptada invalida los tokens anteriores.
Se admiten tres solicitudes nuevas por dirección normalizada en una hora y diez por IP en
quince minutos. Las direcciones inexistentes consumen las mismas cuotas y reciben la misma
respuesta. Los reintentos con la misma clave y contenido recuperan el resultado original.

El token de confirmación se guarda únicamente como SHA-256. El material temporal que necesita
el correo se cifra con AES-256-GCM, nonce aleatorio de 96 bits y recoveryId/deliveryId como datos
autenticados. La clave de cifrado es independiente del HMAC y de las credenciales de servicio.
El material se elimina al usar, invalidar o vencer el token; los estados durables de entrega
y de outbox se conservan para resolver trabajos pendientes.

Confirmar consume el token, cambia el hash Argon2 y revoca todas las sesiones en una sola
transacción. Login y recuperación serializan sobre la cuenta para impedir crear una sesión
con una contraseña que acaba de ser sustituida. Perfil y canal conservan sus IDs y datos.

Las operaciones mutadoras requieren Idempotency-Key UUID y conservan su resultado 24 horas.
Una confirmación repetida con otra clave no reutiliza un token consumido. El correo usa un enlace
del origen configurado con el token en el fragmento; Web lo retira de la URL y lo conserva
únicamente en memoria hasta confirmar o abandonar la vista. No se inicia sesión automáticamente.

Cuentas publica `credential.recovery.delivery.requested.v1` en el RabbitMQ compartido de
ADR-016. Su payload contiene solo recoveryId/deliveryId y se enruta a
`notifications.recovery.email`. El publicador conserva eventId/cuerpo al reintentar y marca
el outbox después del publisher confirm durable y sin retornos por ausencia de ruta.

Notificaciones consumirá esa suscripción y obtendrá el material mediante claim/result HTTPS.
El nombre lógico de la credencial privada es `recovery-email`; no selecciona lenguaje, motor
de datos ni un contenedor concreto. Core autoriza la pareja recoveryId/deliveryId y un intento
monotónico 1..2147483647; los cuatro intentos de transporte de ADR-016 son independientes de
ese contador y un redrive no lo reinicia. Un trabajo vencido o invalidado no permite obtener material.

El consumidor debe deduplicar y conservar intentos/resultados durables antes de confirmar el
trabajo. Una entrega ya confirmada no se repite por reentrega o fallo al reportar el resultado.
Una respuesta SMTP perdida queda UNKNOWN y no autoriza reenvío automático. La dirección y
el enlace solo se obtienen por el contrato privado, sin copiarlos a broker, logs o al registro
durable de intentos. La selección de runtime, almacén, proveedor y credenciales SMTP queda a
cargo de Notificaciones en P2-12/13. El arranque de Cuentas no provisiona esos componentes.

## Justificación de las fronteras y tecnologías

La frontera entre Cuentas y entrega SMTP está definida en ADR-015 y SPEC-01/20: Core conserva
la autoridad sobre credenciales, perfil y canal, y Notificaciones ejecuta la entrega externa.
Outbox y broker permiten confirmar la solicitud sin mantener una transacción ni la petición Web
esperando a SMTP. Claim/result evita exponer el token en la cola o compartir las tablas Core.

Cuentas reutiliza Java/Spring y su PostgreSQL para consumir el token, cambiar la contraseña y
revocar sesiones en una transacción local. El contrato neutral permite al responsable de
Notificaciones seleccionar sus tecnologías sin depender de clases de implementación de Core.
La responsabilidad durable del consumidor es necesaria para distinguir envío de reporte tras
un reinicio; su implementación y modelo físico se deciden dentro de Notificaciones.

## Opciones consideradas

- Token reversible sin cifrar: expone la recuperación desde copias de la base; descartado.
- JWT de recuperación: complica uso único y revocación sin eliminar la necesidad de estado SQL.
- Envío SMTP dentro de la petición: acopla disponibilidad/latencia y no cumple el recorrido P2.
- Token opaco, hash y material temporal cifrado: permite uso único transaccional y entrega durable.
- Intentos solo en memoria del worker: un reinicio pierde la distinción entre enviar y reportar; descartado.
- Consumidor de Notificaciones con persistencia propia: conserva esa distinción respetando la propiedad de datos; tecnología pendiente de su responsable.

## Consecuencias

Hacen falta una clave de cifrado respaldada y una credencial privada de entrega para Notificaciones.
Perder la clave impide enviar correos pendientes; se deben emitir nuevas solicitudes.
Una respuesta SMTP perdida se registra UNKNOWN y no se convierte en entrega confirmada.
El transporte usa el RabbitMQ compartido de ADR-016 y la suscripción notifications.recovery.email.
El arranque local conserva diez servicios y no incluye worker SMTP, almacén de Notificaciones
ni buzón de pruebas. Mientras su responsable no integre el consumidor, los trabajos permanecen
pendientes y RF-004 no acredita la entrega completa por correo. La retención del consumidor
deberá coordinarse con redrive y deduplicación del broker.

## Verificación

Pruebas de uniformidad, cuotas compartidas, idempotencia, vencimiento, manipulación, consumo
concurrente, revocación de sesiones, rollback y aislamiento del contrato privado. El recorrido
por broker/SMTP debe verificarse con sus adaptadores y destinatarios ficticios.
Las pruebas de [Cuentas](../../services/core/src/test/java/streaming/core/accounts/identity/RecoveryIT.java)
y de [publicación](../../services/core/src/test/java/streaming/core/accounts/identity/infrastructure/RabbitRecoveryPublisherTest.java)
definen la cobertura de Core. La entrega SMTP, deduplicación del consumidor, fallos externos y
reinicios de ese proceso quedan pendientes de la implementación de Notificaciones.

## Revisión

Cuentas e Integración revisan esta decisión al cambiar cuotas, transporte, rotación de claves,
retención de reintentos o el contrato de entrega. Las demás políticas de P2 conservan su responsable.
