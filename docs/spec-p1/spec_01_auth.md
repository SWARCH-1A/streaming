# SPEC-01 Cuentas, autenticación y perfil P1

- **Módulo:** accounts — Core / Cuentas
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Cuentas reúne identidad privada y presentación pública del mismo usuario. Define RF-001…RF-003,
RF-005…RF-007 y RNF-025…RNF-032. El registro crea cuenta, perfil y canal en una transacción Core.
La separación entre DTO públicos y credenciales se aplica dentro del módulo.

## 2. Definición del componente

Cuentas pertenece a Core, con Java/Spring, PostgreSQL, una cadena de seguridad y un gestor de
transacciones. Autenticación y perfil usan interfaces locales; no hay introspección HTTP entre ambos.
Canales aporta la creación inicial dentro de la transacción de registro.

## 3. Historia de usuario

Como visitante o usuario, quiero registrarme, iniciar/cerrar sesión y mantener mi presentación pública,
para participar y administrar únicamente mis recursos sin exponer mis credenciales.

## 4. Alcance

### Dentro de P1

Registro idempotente con email/handle únicos; login/logout con sesión opaca y CSRF; autorización por
propietario; consulta y edición de displayName, bio y avatar. El registro confirma cuenta ACTIVE,
perfil inicial y canal, sin iniciar sesión. Handle inmutable. Avatar opcional con recurso por defecto.

### Fuera de P1

Verificación de email, recuperación/cambio de contraseña, cambio de handle, preferencias avanzadas,
roles administrativos y portada del canal. Recuperación de contraseña se define en RF-004 futuro.

## 5. Requisitos funcionales

- RF-001: registrar cuenta con email único, handle único y contraseña.
- RF-002: autenticar con email o handle y contraseña válidos.
- RF-003: cerrar sesión e invalidarla.
- RF-005: verificar identidad y propiedad antes de operaciones protegidas.
- RF-006: consultar el perfil público sin exponer campos privados.
- RF-007: el propietario edita nombre visible, biografía y avatar.

## 6. Criterios de aceptación

- CA-01: handle 4–25 ASCII alfanumérico/underscore, único sin distinguir mayúsculas, canónico minúsculo e inmutable. Contraseña 12–128 puntos de código exactos, sin trim, normalización, truncado ni reglas de composición/caducidad. Email recorta extremos y compara en minúsculas, conservando puntos y alias +.
- CA-02: una transacción confirma cuenta ACTIVE, perfil default, exactamente un canal y resultado idempotente. No crea sesión. Fallo antes del commit revierte todo; respuesta perdida y misma clave conservan los IDs.
- CA-03: email/handle ocupado da 409 REGISTRATION_UNAVAILABLE uniforme, sin indicar cuál existe; misma clave con payload distinto da 409 IDEMPOTENCY_KEY_REUSED. Resultados exitosos se retienen treinta días.
- CA-04: login válido crea cookie opaca host-only, Path=/, HttpOnly, SameSite=Lax y Secure en HTTPS, sin token JSON; duración fija 24 h, sin extensión por actividad. Logout revoca: toda autorización posterior rechaza. Una operación ya autorizada puede confirmar dentro del presupuesto de su contrato.
- CA-05: propietario edita su recurso; otro usuario no puede. Perfil self obtiene el userId del principal, sin aceptar propietario enviado por el cliente.
- CA-06: password no reversible ni en logs/respuestas; solo hash de sesión en servidor. Perfil/lookup/canal públicos excluyen email, hash y credenciales; inexistente/no activo da 404 uniforme. Fallo SQL devuelve 503.
- CA-07: cinco fallos de login/identificador/15 min y cincuenta/IP/15 min; diez claves nuevas de registro/IP/hora. 429 RATE_LIMITED con Retry-After; reintento de la misma clave no consume otra operación; sin bloqueo permanente.
- CA-08: módulos Core validan sesión localmente; Chat obtiene un contexto por mensaje sin caché de permisos. Core caído devuelve CORE_UNAVAILABLE sin persistir mensajes nuevos; Streaming valida comandos de owner mediante contexto privado Core y falla cerrado si no responde.
- CA-09: perfil inicial visible en el commit: displayName=handle, bio vacía, avatar nulo y profileVersion=0. No requiere proyecciones ni eventos de activación.
- CA-10: PATCH solo permite displayName de 1–50 caracteres, bio hasta 300 y avatarUploadId; campos omitidos se conservan, bio:null limpia y avatarUploadId:null retira avatar. PATCH vacío se rechaza; no-op conserva versión; cambio real incrementa profileVersion. El handle no cambia.
- CA-11: avatar JPEG/PNG/GIF decodificado real, <=10 MB y ancho/alto >=200 px. Inválido conserva el avatar anterior. Upload ligado al propietario, un uso y vencimiento de 15 min.
- CA-12: archivo nuevo se publica antes de confirmar la referencia SQL; rollback conserva el anterior y elimina el nuevo. Tras commit se retiran temporal/anterior; limpieza periódica reconcilia objetos huérfanos. Reinicio conserva referencias y objetos: S3 comparte el bucket entre réplicas y filesystem requiere un volumen persistente/compartido cuando se selecciona explícitamente.
- CA-13: lectura pública posterior a edición refleja el commit; snapshots de autor en mensajes Chat anteriores permanecen inmutables.

## 7. Diseño técnico y datos

ADR-001 define Java 25, Spring Boot 4.1.1, PostgreSQL 18, Flyway, Argon2id, sesión opaca y CSRF.
Cuentas encapsula account, profile, sesiones, resultados idempotentes, uploads y cuotas. Perfil y canal
usan FK locales hacia la cuenta. Cada repositorio conserva autoridad de escritura. Registro persiste
hash de Idempotency-Key UUID y fingerprint HMAC del payload; POST 201 ACTIVE, consulta 200 ACTIVE
con la misma clave; desconocido/clave incorrecta 404. Login es una operación separada.

ADR-002 y ADR-009 definen claves UUID/URI inmutables, almacenamiento de objetos S3 privado en despliegue,
permisos de upload, publicación y limpieza. Las rutas públicas de imagen siguen pasando por Core.
Edición parcial se serializa por usuario. S3 es el proveedor de objetos predeterminado; si se selecciona
filesystem, requiere volumen compartido cuando Core ejecuta más de una réplica.

## 8. Dependencias y contratos

Canales crea el canal inicial mediante interfaz local y comparte la transacción. Streaming Rust solicita contexto Core nuevo por comando protegido; Cuentas/Canales/Catálogo lo validan localmente en Core. Canal y Consultas leen DTO/vistas públicas sin credenciales. Chat obtiene
principal y snapshot público local, más estado/timeline Streaming en un contexto Core; no consulta un servicio Profile.
Las rutas /api/identity/* y /api/profile/* están definidas en contratos_modelo_datos.md.

## 9. Decisiones y preguntas abiertas

Autenticación y perfil pertenecen a Cuentas, según ADR-005. La verificación de email y recuperación de
contraseña quedan fuera de P1. No se introduce JWT ni una dependencia de red para validar módulos Core.

## 10. Verificación

Conflictos y normalización; registro concurrente/rollback/retry tras respuesta perdida; cuenta/perfil/canal
1:1; login/logout/expiry/cuotas y CSRF; acceso propio/ajeno/anónimo; edición parcial/no-op/versiones;
imágenes/tamaño/dimensiones; permiso de un uso/15 min; fallo archivo/DB y recuperación; privacidad;
contexto Chat después de revocación y snapshots históricos.

## 11. Esfuerzo, riesgos y consecuencias

La transacción local simplifica registro y publicación. Cuentas comparte disponibilidad con Core.
Imágenes requieren persistencia y reconciliación fuera de la transacción SQL; la sesión opaca y las
cuotas requieren consulta autoritativa y limpieza según retención. No duplicar identidad pública en
Canales ni aceptar estado de autorización enviado por el navegador.
