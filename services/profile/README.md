# Profile backend

Profile es un proceso independiente y dueño de `displayName`, `bio`, `avatarUri`, `profileVersion` y archivos de avatar. `userId` es una referencia externa; este módulo no crea claves foráneas ni consulta tablas de Identity.

## Arquitectura del módulo

- `api/` adapta REST y multipart; `application/` aplica las reglas de lectura y edición; `infrastructure/` implementa PostgreSQL, el cliente HTTP de Identity y almacenamiento de imágenes.
- Cada acceso propio valida la cookie de sesión llamando a Identity por introspección privada; no se valida JWT local ni se conserva en caché la respuesta. Si Identity no está disponible, la operación falla con 503.
- Cuando un perfil activo aún no tiene fila propia, la lectura pública devuelve `displayName=handle`, `bio=""`, `avatarUri=null` y `profileVersion=0`. La lectura consulta Identity para distinguir identidad activa, pendiente, expirada o inexistente.
- Los uploads guardan el identificador opaco como hash, son de un solo uso y vencen en 15 minutos. El servidor decodifica JPEG/PNG/GIF, limita a 10 MB, exige al menos 200×200 px y evita nombres de archivo controlados por el usuario. La imagen anterior se conserva si la actualización falla.
- PostgreSQL 18 conserva perfiles, permisos de upload y outbox; Flyway crea el esquema `profile`. No hay FK entre dominios.

## Contrato P1 implementado

- `GET /api/profile/users/{userId}` es público y devuelve el mismo 404 para usuario desconocido o no activo.
- `GET /api/profile/me` consulta el perfil del principal autenticado.
- `POST /api/profile/me/avatar-uploads` recibe `file` multipart; entrega `uploadId` con vigencia de 15 minutos.
- `PATCH /api/profile/me` acepta solo `displayName`, `bio` y `avatarUploadId`; no acepta un `userId` objetivo. Omitir conserva, `bio:null` limpia y `avatarUploadId:null` retira la imagen.
- `GET /api/profile/avatars/{key}` sirve objetos inmutables con tipo validado y caché pública.

## Ejecución local

Puerto `8082`; health en `/actuator/health`; requiere Java 25, PostgreSQL 18 y un directorio de almacenamiento persistente montado en `PROFILE_AVATAR_STORAGE`. Ejecuta `./mvnw spring-boot:run`; `./mvnw -B package` genera el JAR. Configura `PROFILE_DB_URL`, `PROFILE_DB_USER`, `PROFILE_DB_PASSWORD`, `IDENTITY_INTERNAL_URL`, `PROFILE_IDENTITY_TOKEN`, `PROFILE_AVATAR_STORAGE`, `PROFILE_AVATAR_PUBLIC_BASE` y `PROFILE_SECURE_COOKIE`. El token debe coincidir con la credencial `profile` que Identity configura para introspección. El navegador obtiene CSRF con `GET /api/profile/csrf` antes de `PATCH` y de subir un avatar. En red, usa HTTPS/TLS y cookies Secure.

El almacenamiento actual usa un directorio externo configurable y un volumen persistente. La elección del volumen, la URI pública y el ciclo de reemplazo/eliminación están registrados en [ADR-002](../../docs/adr/ADR-002-profile-persistencia-y-avatar.md); un despliegue con varias réplicas requiere almacenamiento compartido o una decisión nueva de S3/MinIO. Las notificaciones `ProfilePublicChanged` quedan en outbox durable; el transporte de eventos pertenece a Integración.
