# Notificaciones

Servicio TypeScript (Node.js 24) de [SPEC-20](../../docs/spec-p2/spec_20_notifications.md). Avisa a los
seguidores cuando un canal sale en vivo y entrega el correo de recuperación de contraseña de Cuentas.
Tiene su propia base PostgreSQL; consume y publica en el RabbitMQ compartido ([ADR-016](../../docs/adr/ADR-016-rabbitmq-eventos-p2.md)).

Decisiones P2-12 registradas en [ADR-020](../../docs/adr/ADR-020-notificaciones-typescript-postgresql-smtp.md): Node.js 24 ejecuta TypeScript directamente (sin paso de build),
PostgreSQL para bandeja/intentos, SMTP con STARTTLS obligatorio y Mailpit como proveedor local.
Push (P2-13): la interfaz `PushProvider` existe, pero no hay proveedor aprovisionado; una entrega push
termina en `PERMANENT_FAILURE` con causa `PUSH_PROVIDER_NOT_CONFIGURED`, visible en `deliveries`.

## Flujo

1. `notifications.live` recibe `stream.session.changed.v1` solo con `initialLive=true` (primera vez LIVE).
   Serializa por sesión y registra `live_sessions` **antes** de elegir destinatarios. Pide a Core el corte de
   seguidores por páginas y, por cada página, en una transacción: crea el aviso (único por tipo,
   usuario y sesión), sus entregas por medio y los trabajos en `outbox`. Reconectar no vuelve a elegir; reanudar conserva los medios fijados al crear cada aviso.
2. Medios: bandeja siempre; correo/push solo si el interruptor general **y** el del canal están activos
   (ambos apagados por defecto). Push crea una entrega por dispositivo registrado.
3. `outbox.ts` publica `notification.delivery.requested.v1.<medio>` con confirmación del broker.
4. `deliveries.ts`: un trabajador por cola (`inbox`, `email`, `push`). La fila `deliveries` es la autoridad:
   lo entregado, permanente o desconocido no se reenvía. Fallo temporal: reintentos 1 s/5 s/30 s y luego DLQ.
   Un lock PostgreSQL por deliveryId impide envíos concurrentes. Tras un reinicio, `IN_PROGRESS`
   externo pasa a `UNKNOWN`; la bandeja local se completa como `DELIVERED`. SMTP no permite saber si salió.
5. `notifications.follow` mantiene una copia local de seguimientos por versión; dejar de seguir apaga
   correo/push de ese canal. Core sigue siendo la autoridad de quién sigue a quién.
6. `notifications.recovery.email`, con la identidad aparte `recovery-email`: claim a Core → SMTP → result.
   Guarda solo ids y fase (`recovery_attempts`), nunca dirección, token ni enlace.

## API pública (`/api/notifications/*`, detrás del proxy)

| Operación | Ruta |
| --- | --- |
| Bandeja paginada | GET `/api/notifications?limit=&cursor=` |
| Marcar leído | PUT `/api/notifications/{notificationId}/read` |
| Interruptores generales | GET/PUT `/api/notifications/preferences` |
| Interruptores por canal | GET/PUT `/api/notifications/channels/{channelId}/preference` |
| Dispositivo push | PUT/DELETE `/api/notifications/devices/{installationId}` |

Sesión: cookie `stream_session` (mutaciones con CSRF de doble envío `XSRF-TOKEN`) o `Authorization: Bearer`.
La identidad se resuelve en Core (`/internal/core/notifications/session`); el correo se lee solo al
enviar (`/internal/core/notifications/email-target`). Mutaciones con `Idempotency-Key` UUID (24 h).
Contratos: `p2.notifications.*` en [contracts/p2.json](../../contracts/p2.json).

## Desarrollo

```sh
npm ci
npm run typecheck
# Pruebas contra un PostgreSQL desechable:
docker run -d --name ntf-test-db -e POSTGRES_PASSWORD=test -e POSTGRES_DB=notifications -p 127.0.0.1:55432:5432 postgres:18-alpine
NOTIFICATIONS_TEST_DB_URL=postgres://postgres:test@127.0.0.1:55432/notifications npm test
```

La prueba opcional de SMTP real requiere un Mailpit aislado con STARTTLS y certificado SAN
`localhost`; define `NOTIFICATIONS_TEST_SMTP_PORT`, `NOTIFICATIONS_TEST_SMTP_CA_FILE` y
`NOTIFICATIONS_TEST_MAILPIT_URL` al ejecutar `npm test`. Verifica confianza TLS y una única entrega.

En la instalación local (`python3 infra/local/dev.py init|build|up`) los correos se ven en Mailpit:
http://127.0.0.1:8025.
