# ADR-001: Plataforma Core y seguridad de Cuentas

- Estado: aceptada
- Fecha: 2026-10-01
- Responsable: Core / Cuentas
- SPEC/contratos afectados: SPEC-01, SPEC-03, SPEC-04, SPEC-10, SPEC-11, SPEC-13; cuenta, registro, sesión, autorización y contexto Chat.

## Contexto

Cuentas controla userId, email/handle canónicos, contraseña, sesiones y perfil público. Canales,
Catálogo y Consultas comparten Core; Streaming es servicio Rust según ADR-005. Registro debe confirmar cuenta, perfil y canal juntos;
logout debe impedir toda autorización posterior. Los DTO públicos excluyen datos privados.

La entrega requiere SQL, uso justificado de NoSQL, dos procesos propios de lógica y tres lenguajes.
Estas restricciones son de sistema y no obligan a introducir otro stack por módulo Core.

## Decisión

1. Java 25, Spring Boot 4.1.1, Maven Wrapper, Spring Security, JDBC, Flyway y PostgreSQL 18 para Core. Un build, cadena de seguridad y gestor de transacciones. Los módulos internos usan interfaces locales y repositorios encapsulados.
2. Registro transaccional de cuenta ACTIVE, perfil default, canal inicial y resultado idempotente. FK locales y unicidad en base; rollback total ante fallo. UUID de idempotencia, hash SHA-256 de la clave y fingerprint HMAC del payload; resultado exitoso retenido treinta días. Registro no inicia sesión.
3. Argon2id mediante Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), con Bouncy Castle. Guardar hash y parámetros; no contraseña reversible, body de credenciales ni secretos en logs. Contraseña exacta de 12–128 puntos de código, sin recorte/normalización.
4. Sesión opaca aleatoria de al menos 256 bits; PostgreSQL guarda solo SHA-256. Cookie host-only stream_session, Path=/, HttpOnly, SameSite=Lax y Secure en HTTPS. Vida fija 24 h, sin extensión. Logout revoca en persistencia; cada operación protegida consulta estado vigente localmente.
5. CSRF de Spring Security mediante CookieCsrfTokenRepository, CsrfTokenRequestAttributeHandler y header X-XSRF-TOKEN; el endpoint CSRF publica nombre de header y token utilizable por la Web. La cookie de sesión permanece HttpOnly. CORS con orígenes permitidos; CORS y SameSite no sustituyen CSRF. Una cadena cubre identity, profile y los otros módulos Core, incluyendo PATCH/multipart.
6. Límites SQL autoritativos: cinco fallos login/identificador y cincuenta/IP en 15 min; diez operaciones de registro nuevas/IP/hora. Respuesta 429 con Retry-After; reintento idempotente no duplica consumo. Confiar solo en IP observada por proxy configurado.
7. Entre procesos, TLS privado y secreto distinto por consumidor/ruta, con comparación en tiempo constante y autorización de la operación. Headers X-Service-Name y X-Service-Token; Chat transmite la credencial de sesión en X-Session-Credential al contexto Core. No se registra ni persiste esa credencial en Chat. /internal/* queda bloqueado en entrada pública.
8. Chat solicita un contexto por nuevo mensaje; Core resuelve sesión/autor localmente y obtiene estado/timeline vigente de Streaming. Streaming solicita contexto Core para cada comando protegido. No caché de permisos ni HTTP entre módulos Core. No JWT, Redis de sesiones, servidor OAuth o broker como requisito P1.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Java/Spring/PostgreSQL compartidos por Core | Elegida: reutiliza plataforma y resuelve invariantes mediante transacciones/FK. |
| Stacks o bases distintos para autenticación y perfil | Añaden contratos, fallos parciales y operación a entidades que se crean juntas. |
| JWT sin estado | No permite revocación inmediata; denylist o introspección recupera dependencia de estado. |
| Redis de sesiones/cuotas | Posible ante carga medida; añade persistencia y operación sin necesidad demostrada en P1. |
| BCrypt/PBKDF2 | Alternativas disponibles; Argon2id aporta costo de memoria configurable y soporte en Spring Security. |
| mTLS/OAuth de servicios | Posible evolución de identidad/rotación; PKI o servidor adicional no se justifican en P1. |

## Consecuencias

Registro y publicación tienen integridad local; seguridad común evita cookies y validaciones
incompatibles. Core tiene disponibilidad y release comunes. Chat depende de Core para nuevos envíos:
indisponibilidad produce CORE_UNAVAILABLE y falla cerrado. Una operación ya autorizada puede confirmar
dentro del presupuesto acotado del contrato; logout no revierte commits en vuelo.

PostgreSQL requiere backups y limpieza de sesiones, resultados y cuotas. Los secretos internos
requieren rotación por consumidor. La réplica Core usa la misma autoridad SQL de sesión/cuota;
no se admiten contadores independientes por réplica. Cambiar hash/sesión exige compatibilidad o nuevos
logins. Flyway versiona cambios; no modificar checksums aplicados para ocultar discrepancias.

## Verificación

Comprobar registro concurrente y rollback entre escrituras, recuperación tras respuesta perdida,
unicidad, login/logout/expiry/cuotas, cookie, CSRF para todas las mutaciones y rechazo de acceso ajeno.
Inspeccionar DTO/logs y rechazo de tokens internos incorrectos o fuera de su ruta. Contexto Chat tras
logout debe rechazar; la caída Core no admite mensajes. Evidencia SQL/reinicio en SPEC-13.

## Condiciones para cambiar la decisión

Reevaluar ante SSO/federación, carga de sesiones/cuotas medida, cambio de revocación o necesidad de
rotación administrada. Core/Cuentas coordina contratos con Streaming, Chat, Media y Web antes de adoptar cambios.
