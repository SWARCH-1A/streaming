# Identity backend

Identity es un proceso independiente. Es autoridad de `userId`, email normalizado, `handle`, hash de contraseña, estado de cuenta y sesión. No comparte repositorios ni tablas con Profile; los consumidores validan una sesión mediante introspección privada antes de cada operación protegida.

## Arquitectura del módulo

- `api/` traduce HTTP y cookies al contrato público; `application/` coordina registro, login, sesión y provisión; `infrastructure/` adapta PostgreSQL y Channels; `security/` protege cookie, CSRF y rutas internas.
- PostgreSQL 18 guarda reservas, cuentas, operaciones idempotentes, sesiones, límites y outbox. Flyway materializa el esquema `identity`. Las credenciales de sesión se generan con 256 bits aleatorios y solo se guarda SHA-256.
- Argon2id guarda contraseñas. El registro crea una identidad pendiente y llama a `POST /internal/channels/provision`; solo la transición confirmada antes de 24 h crea una cuenta activa. Un worker conserva los reintentos y compensa una provisión tardía por `registrationId`.
- La cookie `stream_session` es host-only, `Path=/`, `HttpOnly`, `SameSite=Lax`, dura 24 h sin renovación deslizante y se revoca al hacer logout. No se entregan JWT ni refresh tokens en JSON.

## Contrato P1 implementado

- `POST /api/identity/registrations` y `GET /api/identity/registrations/{registrationId}`: requieren el mismo `Idempotency-Key` UUID. PENDING devuelve 202; ACTIVE, 201/200 según operación; expirado devuelve 410. Email y handle duplicados usan el mismo 409 genérico.
- `POST /api/identity/sessions` recibe `login` (email o handle) y `password`; escribe la sesión solo en cookie. `DELETE /api/identity/sessions/current` revoca inmediatamente.
- `POST /internal/identity/sessions/introspect` devuelve el principal vigente o `active:false`; exige autenticación de servicio y `X-Session-Credential` y nunca debe pasar por el proxy público.
- `GET /api/identity/public/users/{userId}` y `GET /api/identity/public/handles/{handle}` solo resuelven identidades activas.
- Login: máximo 5 fallos por identificador y 50 por IP en 15 min; registro: 10 claves nuevas por IP/hora. El identificador de los contadores se guarda como HMAC.

## Ejecución local

Puerto `8081`; health en `/actuator/health`; requiere Java 25 y PostgreSQL 18. `./mvnw spring-boot:run` aplica las migraciones al arrancar; `./mvnw -B package` genera el JAR. Configura `IDENTITY_DB_URL`, `IDENTITY_DB_USER`, `IDENTITY_DB_PASSWORD`, `IDENTITY_RATE_LIMIT_HMAC_SECRET`, `IDENTITY_INTERNAL_SERVICE_TOKENS`, `CHANNELS_INTERNAL_URL` y `IDENTITY_CHANNELS_TOKEN`. Los valores locales del YAML son solo para desarrollo. En red, usa HTTPS/TLS, secretos independientes por consumidor, proxy confiable y `IDENTITY_SECURE_COOKIE=true`.

El navegador obtiene CSRF con `GET /api/identity/csrf` antes de enviar registro, login o logout; reenvía la cookie CSRF y el header que indica la respuesta. `Idempotency-Key` es un UUID nuevo por operación lógica y se conserva en los reintentos de registro.

La selección de Java/Spring/PostgreSQL, hash, sesión, CSRF y autenticación servicio-a-servicio está registrada en [ADR-001](../../docs/adr/ADR-001-identity-plataforma-y-seguridad.md). El transporte y dispatcher de eventos permanece como decisión de Integración.

## Pendiente fuera del límite del módulo

El outbox persiste `IdentityPublicChanged` en la misma transacción que activa la cuenta. El transporte y el dispatcher de eventos deben cerrarse en el ADR de integración; no se debe marcar la publicación como completada mientras no haya ACK durable del consumidor.
