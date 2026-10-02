# Core

Un ejecutable Java 25 / Spring Boot 4.1.1, Maven Wrapper, PostgreSQL 18, JDBC y Flyway.
Seguridad, CSRF, configuración y gestor de transacciones compartidos. Decisiones:
[ADR-001](../../docs/adr/ADR-001-identity-plataforma-y-seguridad.md),
[ADR-002](../../docs/adr/ADR-002-profile-persistencia-y-avatar.md) y
[ADR-003](../../docs/adr/ADR-003-servicios-cohesivos.md).

## Módulos e implementación

Código bajo `src/main/java/streaming/core`:

- `accounts/identity`: registro, credenciales, sesiones, idempotencia y cuotas SQL.
- `accounts/profile`: perfil, edición propia y archivos/permisos de avatar. Valida sesión localmente.
- `channels`: creación inicial y bootstrap público por handle/owner, con vistas SQL públicas de Cuentas.
- `streaming`, `taxonomy`, `discovery`: responsabilidades definidas, implementación pendiente.
- `security`, `api`: infraestructura común, sin orquestación de casos de uso de dominio.

El registro pertenece a Cuentas: abre una transacción y llama interfaces locales de inicialización
de perfil/canal; cada repositorio escribe sus tablas. Confirma cuenta ACTIVE, perfil versión 0,
canal versión 0 y resultado idempotente juntos. No inicia sesión. Las FK y UNIQUE protegen relaciones
y unicidad. Un reintento recupera los mismos IDs; un fallo revierte todas las escrituras de negocio.
Las cuotas se guardan aparte para limitar intentos fallidos.

No hay HTTP entre estos módulos, worker de provisión, reservas PENDING ni outbox de identidad/perfil.
La consulta pública compone canal/handle/perfil en una sentencia SQL y devuelve `stream:null`
para el canal inicial sin configuración. Edición/portada de canal, Emisiones y contexto Chat aún no
se implementan; no se declara cumplimiento completo de SPEC-03 ni del sistema P1.

## Configuración

| Variable | Uso |
| --- | --- |
| PORT | Puerto Core; default 8081 |
| CORE_DB_URL | JDBC; default jdbc:postgresql://localhost:5432/core |
| CORE_DB_USER | Usuario SQL; default core |
| CORE_DB_PASSWORD | Obligatoria; contraseña SQL |
| CORE_RATE_LIMIT_HMAC_SECRET | Obligatoria, al menos 32 bytes; fingerprint y cuotas |
| CORE_SECURE_COOKIE | true bajo HTTPS; false solo para desarrollo HTTP |
| WEB_ORIGIN | Origen CORS permitido; default http://localhost:3000 |
| PROFILE_AVATAR_STORAGE | Obligatoria; directorio persistente escribible por el proceso |
| PROFILE_AVATAR_PUBLIC_BASE | URI de avatar; default /api/profile/avatars |

Una réplica Core. Varias requieren almacenamiento de avatares compartido consistente o nuevo adaptador
por ADR. El contenedor usa UID 10001 y volumen `/data/avatars`. Persistir PostgreSQL y objetos juntos;
el HMAC se conserva entre reinicios para reintentos/cuotas. Rotarlo requiere una estrategia compatible
con la retención de treinta días, no cambiarlo accidentalmente en cada arranque.

El listener directo ignora `Forwarded`/`X-Forwarded-*`; cuotas usan la IP del socket. La integración
de reverse proxy debe configurar exclusivamente sus IP/redes confiables y verificar las cuotas
antes de habilitar tráfico tras proxy. Health: `GET /actuator/health`, readiness y liveness bajo
`/actuator/health/{readiness,liveness}`. Los endpoints privados futuros permanecen denegados.

## Ejecución local

[Compose](../../infra/local/README.md) inicia Core y PostgreSQL con volúmenes. Para ejecutar con JDK 25
y PostgreSQL dedicado en el host, exporta las variables anteriores y usa:

```sh
./mvnw spring-boot:run
```

Desde la raíz del repositorio: `services/core/mvnw -f services/core/pom.xml spring-boot:run`.
El paquete ejecutable es `target/core.jar`; el Dockerfile usa JDK/JRE 25.

## Pruebas

```sh
./mvnw test
./mvnw verify -P integration
```

`test` ejecuta reglas de dominio, perfil y validación real de imágenes. `integration` requiere Docker
y usa PostgreSQL 18 desechable con Testcontainers; inicia Core en un puerto aleatorio. No utiliza
CORE_DB_URL ni tablas existentes del desarrollador. Incluye rollback en cada escritura dependiente,
concurrencia/idempotencia/unicidad, sesión/expiry/logout, privacidad, perfil parcial/versiones,
CSRF/CORS, permisos de avatar y conservación del archivo anterior ante rollback SQL.

## Base nueva e historiales anteriores

`db/migration/V1__core.sql` es el baseline de una **base nueva**. El historial Flyway se ubica en
schema `core`; las tablas tienen schemas `identity`, `profile`, `channels`. Las migraciones V1 de
los ejecutables anteriores no se concatenan ni se cambian sobre una base aplicada. Flyway debe
rechazar schemas no vacíos sin historial Core; no activar baseline-on-migrate ni ejecutar clean.

Si existen datos de los prototipos anteriores, el traslado se hace como una migración de datos
explícita antes del cambio de despliegue:

1. Detener escrituras y respaldar ambas bases, sus historiales y el volumen de avatares.
2. Resolver las operaciones PENDING/EXPIRED con el prototipo anterior y sus canales externos;
   no convertir automáticamente pendientes en ACTIVE ni descartar reservas/objetos.
3. Crear Core en una base nueva y preparar un import por columnas explícitas, preservando userId,
   channelId, hashes de contraseña/sesión, fechas, perfiles/objetos y resultados ACTIVE retenidos.
   Crear el perfil default solo donde una cuenta activa anterior no lo tenía. Usar el mismo secreto
   HMAC para preservar fingerprints; validar el canal/propietario contra las fuentes anteriores.
4. Validar cuenta/perfil/canal 1:1, unicidad, FK, referencias/checksums de avatares y reintentos con
   los IDs anteriores; comprobar login/logout en una copia. No importar outboxes de proyección local
   como autorización ni crear nuevos canales con IDs distintos para registros retenidos.
5. Cambiar rutas a Core tras comprobar la copia, conservando las fuentes para rollback. No iniciar
   simultáneamente prototipos antiguos escribiendo en las bases trasladadas.

Este cambio no ejecuta un import ni modifica bases existentes. Los scripts e historiales originales
permanecen recuperables en Git; el import depende de las fuentes reales y se valida antes de aplicarlo.
