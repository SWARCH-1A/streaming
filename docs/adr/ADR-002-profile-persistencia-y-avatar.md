# ADR-002: Perfil público y almacenamiento de avatares en Cuentas

- Estado: aceptada
- Fecha: 2026-10-01
- Responsable: Core / Cuentas
- SPEC/contratos afectados: SPEC-01 (RF-006, RF-007, RNF-032), SPEC-03, SPEC-10 y SPEC-13; perfil público, edición self y objetos avatar.

## Contexto

El perfil pertenece a Cuentas y contiene displayName, bio, avatarUri y profileVersion.
La cuenta conserva email, handle y credenciales; Canales conserva descripción y portada.
Al compartir Core y PostgreSQL podemos crear el perfil junto con la cuenta y validar la sesión
localmente, manteniendo separados los DTO públicos y los datos privados.

En P1 el avatar es opcional. Admitimos JPEG, PNG y GIF de hasta 10 MB y al menos 200×200 px.
El usuario puede editar el nombre visible y la biografía, mientras el handle permanece igual.
Si falla el reemplazo de un avatar, debe conservarse el anterior.

## Decisión

1. Usar Java/Spring y PostgreSQL Core de ADR-001. Tabla de perfil con FK a cuenta, permisos de upload ligados al propietario y repositorio encapsulado; registro crea displayName=handle, bio vacía, avatar nulo y versión 0. Validación de sesión por interfaz local de Cuentas.
2. PATCH parcial serializado por usuario; omisión conserva, bio:null limpia y avatarUploadId:null retira. Solo cambios efectivos incrementan versión. Canal/Consultas leen DTO/vista pública local; Chat recibe snapshot del autor en su contexto y conserva el de mensajes anteriores.
3. Usar el proveedor de objetos definido por [ADR-009](ADR-009-s3-image-storage.md). S3 es el proveedor predeterminado; guardar en el prefijo de avatares bajo `pending/` y `public/`. Filesystem queda disponible solo cuando se selecciona explícitamente para desarrollo o pruebas. Clave UUID inmutable y extensión del formato real; no usar nombres de archivo del cliente ni guardar imagen como blob SQL.
4. Inspeccionar/decodificar bytes; JPEG/PNG/GIF real <=10 MB y ancho/alto >=200 px. Límite defensivo de píxeles/dimensiones. Content-Type y extensión del cliente no prueban formato.
5. Upload crea temporal y permiso de un uso: hash de uploadId, dueño y vencimiento 15 min. PATCH valida/consume permiso y publica objeto nuevo antes del commit de referencia SQL. Rollback elimina el objeto nuevo; tras commit limpia temporal y retira objeto anterior cuando corresponde. Reconciliación periódica elimina temporales vencidos/huérfanos sin borrar objetos referenciados.
6. URI pública GET /api/profile/avatars/{key}, prefijo PROFILE_AVATAR_PUBLIC_BASE (default /api/profile/avatars). Core entrega la imagen desde el bucket privado; no se publica una ACL S3 ni se incluye una URL directa de bucket. Clave opaca y caché de larga duración con URL nueva en cada reemplazo; no revelar ruta física/nombre original. La política de caché debe contemplar privacidad y retiro antes de introducir borrado definitivo de cuentas.
7. Una réplica Core en P1. Al usar S3, todas las réplicas acceden al mismo almacén de objetos; filesystem requiere un volumen compartido consistente. Backup/restauración de PostgreSQL y objetos se verifican como conjunto recuperable.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Perfil SQL dentro de Cuentas | Elegida: relación 1:1, defaults de registro y lectura/edición local. |
| Perfil en servicio/NoSQL separado | Obliga a activación y lookup remoto; el uso NoSQL de sistema se asigna al historial Chat. |
| Volumen persistente | Se conserva para desarrollo local y pruebas; con varias réplicas requiere volumen compartido consistente. |
| Blob PostgreSQL | Aumenta tamaño y costo de lectura/backups transaccionales; separa peor los archivos. |
| S3/MinIO | Elegido para despliegue: almacén compartido y escalable, a cambio de dependencia de red, credenciales y operación; detalle en ADR-009. |
| Sobrescribir URL o usar nombre original | Complica caché, colisiones y seguridad; se eligen claves UUID inmutables. |

## Consecuencias

El perfil consulta la identidad local de Cuentas. Los objetos y SQL no comparten una transacción:
el orden de publicación, la compensación local y la reconciliación conservan el avatar anterior
y reparan los huérfanos después de una caída.

Si S3 falla, se bloquean las cargas y los cambios, y puede fallar la lectura de avatares hasta
que vuelva el bucket. El respaldo y la restauración deben comprobar checksums y referencias.
Aunque cambie el proveedor, las rutas públicas permanecen en Core.

Los lectores Core consultan el perfil localmente. El snapshot Chat es histórico;
editar perfil no cambia los autores de mensajes ya persistidos. Fallo SQL es 503, no usuario inexistente.

## Verificación

Consulta público/self y privacidad, acceso propio/ajeno, campos permitidos, PATCH parcial/no-op,
validación de formato/tamaño/píxeles/dimensiones, permiso de un uso/expiry y concurrencia de reemplazos.
Inyectar fallos de almacenamiento/SQL/commit y reinicio: conservar referencia anterior ante rollback y
recuperar huérfanos. Probar el adaptador S3 contra el proveedor configurado y restaurar objetos junto con
SQL antes de habilitar varias réplicas.

## Condiciones para cambiar la decisión

Core/Cuentas revisa proveedor ante límites de costo/latencia, política de acceso, CDN o retención.
Coordinar URL/proxy y restauración con Integración y consumidores. La migración de los volúmenes existentes
a S3 se describe en ADR-009.
