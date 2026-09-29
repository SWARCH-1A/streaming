# ADR-001: Plataforma y modelo de seguridad del módulo Identity

- Estado: aceptada
- Fecha: 2026-09-29
- Responsable: Identity
- SDD/contratos afectados: SPEC-01 (RF-001–RF-003 y RF-005; CA-01–CA-10); SPEC-10 (contratos API, propiedad de datos, autenticación y errores); SPEC-13 (contribución al despliegue); contratos de introspección de sesión, lookup público e `IdentityPublicChanged`.

## Contexto

Identity es la autoridad de `userId`, email y handle canónicos, hash de contraseña, estado de la cuenta y sesiones. Sus consumidores —Profile, Channels, Streaming y Chat— no deben leer sus tablas ni validar credenciales por cuenta propia. Las lecturas protegidas deben comprobar el estado de sesión vigente en cada operación para que el logout tenga efecto inmediato.

SPEC-01 fija las reglas de producto P1: email y handle únicos; handle inmutable; contraseña de 12–128 puntos de código Unicode sin recorte ni normalización; registro PENDING/ACTIVE/EXPIRED con idempotencia y plazo máximo de 24 horas; el registro no inicia sesión; cookie de sesión opaca de 24 horas sin extensión; revocación inmediata; introspección privada sin caché; CSRF y límites de intentos concretos. Recuperación de contraseña, verificación de correo y roles avanzados están fuera de P1. Este ADR selecciona medios técnicos para implementar esas reglas; no las modifica.

La implementación en `services/identity` ya usa Java 25, Spring Boot 4.1.1, Spring Security, JDBC, Flyway, PostgreSQL y Argon2id. Perfil ya consume la introspección privada con autenticación de servicio. El ADR registra esas decisiones y deja explícitos sus límites de integración.

## Decisión

1. **Lenguaje y framework:** Java 25 y Spring Boot 4.1.1; Spring Security para filtros, autorización y CSRF. Se conserva Maven Wrapper para builds reproducibles sin exigir una instalación global de Maven. La API sigue siendo REST/JSON y los contratos no exponen clases Java.
2. **Persistencia:** PostgreSQL 18 como almacenamiento autoritativo. Identity posee el esquema `identity`; Flyway aplica cambios versionados. Las cuentas, reservas idempotentes, sesiones, contadores de abuso y outbox se guardan en tablas del módulo. Ningún consumidor consulta estas tablas.
3. **Contraseñas:** Argon2id mediante `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` de Spring Security y proveedor criptográfico Bouncy Castle. Solo se persiste el hash con parámetros; nunca la contraseña reversible ni el valor recibido en logs.
4. **Sesiones:** credencial opaca criptográficamente aleatoria de al menos 256 bits, enviada exclusivamente en cookie host-only `stream_session` (`Path=/`, `HttpOnly`, `SameSite=Lax`, `Secure` en despliegues HTTPS). PostgreSQL conserva únicamente SHA-256 de la credencial. La sesión expira a las 24 horas desde el login, no se extiende por actividad y se revoca al cerrar sesión. Cada servicio protegido llama a introspección en cada operación, sin caché.
5. **CSRF:** `CookieCsrfTokenRepository` de Spring Security, cookie CSRF legible por el cliente y header `X-XSRF-TOKEN`; `SameSite=Lax` y `Secure` según el entorno. La cookie de sesión permanece `HttpOnly`. Los orígenes CORS se configuran en lista permitida.
6. **Autenticación servicio-a-servicio:** token opaco distinto por consumidor, configurado como secreto fuera del código, sobre red privada HTTPS/TLS. Identity compara el secreto en tiempo constante y conserva el nombre del consumidor para aplicar la credencial correcta. La implementación actual transmite el nombre y el secreto en `X-Service-Name` y `X-Service-Token`; la credencial de sesión recibida para introspección va en `X-Session-Credential`. No se reenvía la cookie al consumidor.
7. **Límites deliberados:** la autenticación de usuario no será JWT en P1; no se añade Redis; no se decide aquí el transporte ni el dispatcher de eventos del outbox. La recuperación de contraseña y los roles avanzados siguen fuera del alcance especificado para P1. La autorización P1 valida identidad y propiedad.

## Opciones consideradas

| Decisión | Opción elegida | Alternativas y motivo para no elegirlas en P1 |
| --- | --- | --- |
| Lenguaje/framework | Java + Spring Boot/Security | Kotlin comparte JVM y Spring, pero no aporta una necesidad funcional y añade otra convención al equipo. Node.js/NestJS también serviría para REST, pero duplicaría runtime y modelo de seguridad sin beneficio para este módulo. |
| Datos/sesión | PostgreSQL y sesión opaca persistida | JWT reduciría la consulta de sesión, pero una revocación inmediata exigiría consultar estado o mantener una denylist, recuperando el costo de estado adicional. Redis sería otro servicio operativo y una fuente de estado que no se necesita: PostgreSQL ya es requerido y conserva sesiones, idempotencia y límites de forma durable. |
| Hash de contraseña | Argon2id | BCrypt y PBKDF2 son alternativas conocidas y disponibles; se elige Argon2id por su resistencia configurable en memoria y porque existe soporte en Spring Security. No se almacena texto plano ni se cifra la contraseña de forma reversible. |
| CSRF | Repositorio cookie-token de Spring Security | Un mecanismo artesanal o un token ligado a sesión requeriría más código propio. El repositorio elegido encaja con cliente web y cookie de sesión; el cliente debe reenviar el token en el header acordado. CORS por sí solo no reemplaza CSRF. |
| Auth interna | Secreto estático por servicio sobre HTTPS privado | mTLS u OAuth2 Client Credentials pueden aportar identidad/rotación más administrada, pero incorporan PKI o un servidor de autorización y operación adicional para P1. Una clave global compartida sería más simple pero ampliaría el impacto de filtración; por eso se usa una distinta por consumidor. |

## Consecuencias

**Beneficios:** el módulo y sus consumidores mantienen una frontera HTTP explícita; Identity conserva propiedad exclusiva de credenciales/sesiones; PostgreSQL permite transacciones e idempotencia durables; el logout se refleja en la siguiente introspección; el formato de sesión no acopla clientes o servicios a JWT ni a tablas compartidas.

**Costos y fallos:** cada operación protegida depende de Identity y añade latencia/red; si Identity no está disponible, los consumidores deben fallar cerrados con `503 IDENTITY_UNAVAILABLE`. PostgreSQL debe protegerse, respaldarse y limpiarse según retención. Las credenciales internas estáticas requieren secretos únicos, almacenamiento seguro y rotación coordinada; HTTPS/TLS y aislamiento de red son obligatorios fuera del entorno local.

**Compatibilidad:** se mantienen la semántica pública, los estados, expiraciones y errores definidos por SPEC-01. Profile ya usa introspección y los headers internos documentados; Channels debe usar la credencial de servicio al aprovisionar y reconciliar cuentas. Streaming y Chat deben usar introspección antes de operaciones protegidas. Ningún consumidor debe importar modelos Java o tablas de Identity.

**Despliegue y requisitos globales:** este ADR selecciona SQL para Identity y no pretende resolver el requisito global de un uso justificado de NoSQL, tres lenguajes, dos tipos de conectores HTTP u otros procesos independientes de SPEC-13. Esas decisiones pertenecen al sistema/integración. El outbox conserva eventos durablemente, pero el broker/dispatcher/ACK no queda decidido aquí.

**Migración/lock-in:** Flyway registra las migraciones; sustituir PostgreSQL exige migrar cuentas, sesiones y contadores sin exponer secretos. Cambiar el formato de sesión o el hash de contraseña requeriría un periodo de compatibilidad o forzar nuevos logins. Argon2id permite actualizar parámetros al validar credenciales.

## Verificación

- Verificar los casos y límites normativos de SPEC-01: registro y reintentos, conflictos genéricos, PENDING/EXPIRED, cero sesiones al registrar, login, cookie de 24 h, logout con introspección inmediatamente inactiva, acceso propio/ajeno, rate limits y ausencia de secretos en respuestas/logs.
- Verificar que PostgreSQL parte de migración limpia, conserva estado tras reinicio y rechaza duplicados mediante restricciones; comprobar que consumidores no requieren lectura de `identity.*`.
- Verificar llamadas privadas con credencial de cada consumidor, rechazo de token incorrecto y comportamiento fail-closed/503 cuando Identity no responde; verificar HTTPS en despliegue.
- Evidencia registrada al preparar este ADR: suites unitarias de Identity (8 pruebas) y Profile (10 pruebas) pasaron en la verificación previa del repositorio. No se verificó el flujo integrado con PostgreSQL/Compose en ese momento porque Docker Engine no estaba disponible; los criterios de despliegue e integración anteriores permanecen pendientes de evidencia E2E.

## Revisión

Identity revisará esta decisión si la introspección no cumple el objetivo de latencia acordado, el volumen requiere escalar sesiones o rate limits fuera de PostgreSQL, aparece una necesidad real de SSO/federación o la rotación estática de secretos resulta insuficiente. El cambio debe conservar la expiración de 24 h y la revocación inmediata, salvo cambio explícito de SPEC. El equipo de integración revisará compatibilidad de los headers/secreto con cada consumidor antes de habilitarlo en su entorno; una discrepancia de contrato requiere actualizar la especificación y registrar la decisión sustitutiva.
