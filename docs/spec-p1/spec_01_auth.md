# SPEC-01 Cuentas, autenticación y perfil P1

- **Módulo:** accounts — Core / Cuentas
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Cuentas se encarga de que un usuario pueda registrarse, iniciar sesión y editar su perfil público.
Reúne la identidad privada y la presentación pública, pero mantiene separados los datos que puede
consultar un visitante de las credenciales. Cubre RF-001…RF-003, RF-005…RF-007 y RNF-025…RNF-032.

Al registrar al usuario creamos su cuenta, perfil y canal en una misma transacción Core.
Así, un fallo no deja una cuenta sin los recursos que necesita para usar la plataforma.

## 2. Definición del componente

El módulo pertenece a Core y usa Java/Spring, PostgreSQL, la misma cadena de seguridad y el
mismo gestor de transacciones que los demás módulos locales. Autenticación y perfil se comunican
mediante interfaces de aplicación. Canales participa en la creación inicial dentro de la
transacción de registro.

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

- CA-01: el handle admite 4–25 caracteres ASCII alfanuméricos o underscore. Es único sin distinguir mayúsculas, se guarda en minúsculas y no cambia en P1. La contraseña admite 12–128 puntos de código y se conserva exactamente: sin trim, normalización, truncado ni reglas de composición o caducidad. Al comparar emails se recortan los espacios de los extremos y se usan minúsculas; se conservan los puntos y los alias con +.

- CA-02: una transacción crea la cuenta ACTIVE, el perfil por defecto, exactamente un canal y el resultado idempotente, sin iniciar sesión. Un fallo anterior al commit revierte todo. Si se pierde la respuesta, repetir la solicitud con la misma clave conserva los IDs.

- CA-03: un email o handle ocupado devuelve 409 REGISTRATION_UNAVAILABLE, sin revelar cuál de los dos existe. Reutilizar una clave con otro payload devuelve 409 IDEMPOTENCY_KEY_REUSED. Los resultados exitosos se retienen treinta días.

- CA-04: un login válido crea una cookie opaca host-only con Path=/, HttpOnly, SameSite=Lax y Secure en HTTPS. No devuelve un token en JSON. La sesión dura 24 h desde el login, sin extenderse por actividad. Antes de navegar al estudio, Web confirma el perfil self y la identidad pública; cada consulta solo aplica su resultado al intento vigente. Un 401 de una consulta anónima anterior o una petición cancelada no invalida un login posterior. Logout revoca la sesión y toda autorización posterior se rechaza. Una operación ya autorizada puede terminar dentro del presupuesto de su contrato.

- CA-05: solo el propietario puede editar su recurso. El perfil self obtiene el userId del principal autenticado, sin aceptar un propietario enviado por el cliente.

- CA-06: la contraseña no se guarda de forma reversible ni aparece en logs o respuestas. El servidor conserva solo el hash de la sesión. Perfil, lookup y canal públicos excluyen email, hash y credenciales. Un recurso inexistente o no activo devuelve un 404 uniforme; un fallo SQL, 503.

- CA-07: se permiten cinco fallos de login por identificador en 15 min y cincuenta por IP en 15 min. El registro admite diez claves nuevas por IP y hora. Al superar el límite se devuelve 429 RATE_LIMITED con Retry-After. Reintentar la misma clave no consume otra operación y no hay bloqueo permanente.

- CA-08: los módulos Core validan la sesión localmente. Chat solicita un contexto por mensaje, sin reutilizar permisos. Si Core cae, devuelve CORE_UNAVAILABLE y no persiste mensajes nuevos. Streaming valida los comandos del propietario mediante el contexto privado Core y los rechaza si este no responde.

- CA-09: el perfil inicial es visible desde el commit, con displayName=handle, bio vacía, avatar nulo y profileVersion=0. No requiere proyecciones ni eventos de activación.

- CA-10: PATCH permite únicamente displayName de 1–50 caracteres, bio de hasta 300 y avatarUploadId. Los campos omitidos se conservan; bio:null limpia la biografía y avatarUploadId:null retira el avatar. Un PATCH vacío se rechaza. Si no hay cambios, la versión se conserva; un cambio real incrementa profileVersion. El handle permanece igual.

- CA-11: el avatar debe ser un JPEG/PNG/GIF realmente decodificable, de hasta 10 MB y con ancho y alto de al menos 200 px. Una imagen inválida conserva el avatar anterior. El upload pertenece al propietario, se usa una sola vez y vence a los 15 min.

- CA-12: el archivo nuevo se publica antes de confirmar su referencia SQL. Si hay rollback, se conserva el anterior y se elimina el nuevo. Tras el commit se retiran el temporal y el anterior; una limpieza periódica reconcilia los objetos huérfanos. Un reinicio conserva referencias y objetos. S3 comparte el bucket entre réplicas; filesystem necesita un volumen persistente y compartido al replicar Core.

- CA-13: una lectura pública posterior a la edición refleja el commit. Los snapshots del autor en mensajes Chat anteriores se conservan como estaban cuando se enviaron.

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

Las rutas públicas de imágenes sirven bytes con Content-Type image/*. Un Accept incompatible devuelve 406 NOT_ACCEPTABLE; no es un fallo 500 del almacenamiento.

## 8. Dependencias y contratos

Canales crea el canal inicial mediante interfaz local y comparte la transacción. Streaming Rust solicita contexto Core nuevo por comando protegido; Cuentas/Canales/Catálogo lo validan localmente en Core. Canal y Consultas leen DTO/vistas públicas sin credenciales. Chat obtiene
principal y snapshot público local, más estado/timeline Streaming en un contexto Core; no consulta un servicio Profile.
Las rutas /api/identity/* y /api/profile/* están definidas en contratos_modelo_datos.md.

## 9. Decisiones y preguntas abiertas

Autenticación y perfil pertenecen a Cuentas, según ADR-005. La verificación de email y recuperación de
contraseña quedan fuera de P1. No se introduce JWT ni una dependencia de red para validar módulos Core.

## 10. Verificación

Las pruebas deben cubrir:

- Conflictos y normalización de entradas, registro concurrente y relación 1:1 entre cuenta, perfil y canal.
- Rollback y reintento cuando se pierde la respuesta de registro.
- Login, logout, expiración, cuotas y CSRF; acceso propio, ajeno y anónimo.
- Edición parcial, cambios de versión y solicitudes que no modifican datos.
- Formatos, tamaño y dimensiones de imágenes; uploads de un uso con vencimiento de 15 min.
- Fallos de archivo o base de datos, recuperación y ausencia de datos privados en respuestas.
- Contexto Chat después de revocar la sesión y conservación de los snapshots históricos.

## 11. Esfuerzo, riesgos y consecuencias

La transacción local simplifica el registro y permite publicar juntos la cuenta, el perfil y el canal.
Cuentas comparte la disponibilidad de Core. Las imágenes necesitan persistencia y reconciliación
fuera de la transacción SQL; las sesiones y cuotas requieren consultas a la autoridad y limpieza
según su retención.

Canales obtiene la identidad pública de Cuentas. Los permisos se comprueban en el servidor,
sin aceptar el estado de autorización indicado por el navegador.
