# SPEC-01 Identidad y autorización P1

- **Módulo:** identity
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

El prototipo necesita registrar y autenticar usuarios, establecer identidad y proteger recursos propios. Se basa en RF-001…RF-003, RF-005 y RNF-025…RNF-031. El P1 requiere email único, handle único, contraseña, login/logout y autorización mínima por propiedad.

## 2. Estado del sistema y brecha

El contrato de autenticación debe permitir que Perfil, Canales, Streaming y Chat integren identidad sin validar credenciales por su cuenta. El registro de cuenta y la creación automática de canal deben evitar cuentas huérfanas.

## 3. Historia de usuario

Como visitante o usuario, quiero registrarme, iniciar y cerrar sesión de forma segura, para participar y administrar únicamente mis propios recursos.

## 4. Alcance

### Dentro de P1

- Registro con email, handle y contraseña; email y handle únicos.

- Login y logout con sesión opaca transportada en cookie `HttpOnly; Secure; SameSite=Lax`; identidad autenticada y autorización por propietario.

- Endpoint privado de introspección de sesión para que cada servicio de dominio valide expiración, revocación y principal sin leer tablas de Identity.

- Provisión atómica de la cuenta y su canal inicial.

- Validación de credenciales y credenciales expiradas; HTTPS/TLS, hash adecuado y secretos fuera de logs.

### Fuera de P1

- Verificación de correo, recuperación/cambio de contraseña, administración global, suspensión y roles avanzados.

## 5. Requisitos funcionales

- **RF-001:** visitante registra cuenta con email único, handle único y contraseña.

- **RF-002:** usuario se autentica con sus credenciales válidas.

- **RF-003:** usuario cierra su sesión e invalida la sesión actual.

- **RF-005:** el sistema verifica identidad y propiedad antes de cada operación protegida.

## 6. Criterios de aceptación

- **CA-01:** un registro válido tiene email único, handle de 4–25 caracteres ASCII alfanuméricos o `_` sin distinguir mayúsculas, y contraseña de 12–128 puntos de código Unicode (sin recortar ni normalizar). Queda una cuenta ACTIVE y exactamente un canal asociado. El endpoint de registro nunca crea ni entrega sesión; luego el usuario inicia sesión normalmente. Si Channels no está disponible, Identity devuelve `202 PENDING`, no inicia sesión ni expone cuenta/canal públicamente; el mismo `Idempotency-Key` consulta/reintenta durante un máximo de 24 h desde el `pendingSinceUtc` persistido al crear PENDING. Si no completa, GET y POST con esa clave devuelven `410 REGISTRATION_EXPIRED`, Identity libera reservas de email/handle y un nuevo intento requiere otro UUID de idempotencia; no se duplica cuenta/canal.

- **CA-02:** dado un email o handle ya usado, cuando se registra, entonces se rechaza con `409 REGISTRATION_UNAVAILABLE` sin indicar qué campo existe ni incluir fieldErrors; no se crea otra cuenta/canal.

- **CA-03:** dado credenciales correctas, cuando se inicia sesión, entonces el cliente obtiene una sesión autenticada que puede usar en endpoints protegidos.

- **CA-04:** dado un logout, cuando se invalida la sesión, entonces sus credenciales no autorizan nuevas operaciones protegidas.

- **CA-05:** dado un usuario A, cuando modifica un recurso de B, entonces el sistema deniega la operación; B sí puede modificarlo.

- **CA-06:** contraseñas nunca aparecen en respuestas o logs ni se almacenan reversiblemente; valores por debajo de 12 o por encima de 128 puntos de código se rechazan sin truncar. Se aceptan espacios; el contenido se conserva exactamente. P1 no solicita verificación de email ni recuperación.

- **CA-07:** cada servicio protegido valida cookie/sesión con introspección privada; logout invalida de inmediato solicitudes posteriores; caída de Identity deniega acceso con `503 IDENTITY_UNAVAILABLE`.

- **CA-08:** login aplica máximo 5 fallos por identificador/15 min y 50/IP/15 min; registro aplica 10 operaciones con claves nuevas/IP/hora. Identity responde HTTP `429 RATE_LIMITED` con `Retry-After`; Chat comunica el límite a través de un frame de error WebSocket `RATE_LIMITED` con `retryAfterMs`. Ninguno bloquea permanentemente la cuenta.

- **CA-09:** handle PENDING/EXPIRED y userId asociado no se resuelven en lookups públicos; GET por handle, GET por userId y lectura Profile devuelven el mismo `404` que para un usuario inexistente. No se revela un handle reservado.

- **CA-10:** si Channels termina la provisión después de `pendingUntilUtc`, Identity mantiene EXPIRED terminal, libera las reservas y responde `410 REGISTRATION_EXPIRED` a la clave retenida; nunca emite `IdentityPublicChanged`. Si la respuesta original de Channels se perdió, Identity consulta por registrationId; Channels cerca la creación contra el deadline y devuelve estado terminal PROVISIONED/ABSENT sin dejar una creación en vuelo. Identity borra por registrationId una provisión encontrada y reintenta hasta 204; ABSENT cierra compensación. Repetir/reordenar no crea una cuenta/canal público ni activa una sesión.

## 7. Diseño técnico y datos

- **Propiedad:** email canónico, handle, hash de contraseña, estado de cuenta y referencias de sesión pertenecen a Identidad. Perfil mantiene los campos públicos; Canales mantiene el canal asociado.

- **Contrato:** `POST /api/identity/registrations` requiere `Idempotency-Key`; `GET /api/identity/registrations/{registrationId}` requiere la misma clave y devuelve PENDING, ACTIVE o EXPIRED; incluye userId/channelId solo al completar. El estado PENDING vence a las 24 h, libera email/handle y una nueva operación debe usar una clave nueva. Ninguno emite sesión. Después el usuario hace login normal, cuya credencial opaca se guarda en cookie, nunca en JSON. Identity es la única autoridad de `handle`; publica `GET /api/identity/public/handles/{handle}` para resolver handle canónico a userId y `GET /api/identity/public/users/{userId}` para exponer el handle público mínimo a consumidores que reconstruyen proyecciones. Schemas y errores están especificados en el contrato transversal.

- **Seguridad:** hash de credencial de sesión aleatoria ≥256 bits; sesión de 24 h sin sliding extension; cookie host-only Path=/; solo hash de session credential; revocación inmediata. Email/handle se normalizan como el contrato común. La contraseña tiene 12–128 puntos de código Unicode y no se recorta ni normaliza. Login cuenta fallos solo; no se bloquea permanentemente cuenta. Servicios introspectan en cada operación protegida y no almacenan la cookie.

- **ADR requerido:** comparar framework, persistencia, librerías de hash/CSRF y mecanismo de autenticación servicio-a-servicio. No puede cambiar vida de sesión, límites de rate, respuesta genérica ni validación inmediata de revocación definidos por el contrato.

## 8. Dependencias y contratos

Consumidores: Perfil, Canales, Streaming y Chat. Identity es autoridad del userId/handle/sesión. Cada servicio protegido llama por HTTPS/TLS a `POST /internal/identity/sessions/introspect` sobre red privada con la credencial recibida por cookie; recibe active/userId/handle/expiry. La ruta nunca está en proxy público. No compartir tablas ni librerías internas con los consumidores. Identity además expone lookup público mínimo userId→handle para Discovery.

## 9. Decisiones y preguntas abiertas

**Acordado:** sin verificación de email ni recuperación en P1; email/handle únicos; handle 4–25 ASCII alfanuméricos o `_`, canónico minúsculo e inmutable; contraseña 12–128 puntos de código Unicode sin recorte/normalización ni reglas de composición; Identity única fuente autoritativa del handle; registro PENDING recuperable sin sesión durante 24 h y luego EXPIRED/liberación de reservas; el registro ACTIVE no inicia sesión y el usuario hace login después; autorización mínima por propiedad; sesión opaca de servidor en cookie `HttpOnly; Secure; SameSite=Lax`, 24 h desde login sin extensión; protección CSRF; introspección privada sin caché; límites login/registro exactos. **ADR técnico:** almacenamiento concreto de sesión, CSRF y mecanismo interno de autenticación de servicios, respetando semántica acordada. P1 no incluye verificación de email, así que el conflicto de registro no identifica email frente a handle pero no elimina por completo riesgo de enumeración.

## 10. Verificación

Probar registro ACTIVE y conflictos genéricos, reintentos con mismo/diferente payload, PENDING justo antes de 24 h, EXPIRED al alcanzar 24 h, liberación de reservas, `410` al repetir la clave expirada y recuperación con un UUID nuevo, caída de Channels, respuesta de provisión tardía + compensación exacta sin activación/publicación, canal exactamente una vez, login bloqueado mientras PENDING, expiración de sesión a 24 h, logout e introspección inmediata, acceso propio/ajeno, límites HTTP/Retry-After y frame de error Chat, no exposición de hashes/tokens y 503 fail-closed si Identity cae.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. **Riesgos:** sesiones que no puedan revocarse, divergencia de normalización del handle, creación no atómica de canal y contratos incompatibles. **Consecuencia:** recuperación y verificación se planifican para otra fase.
