# ADR-002: Persistencia y almacenamiento de avatares del módulo Profile

- Estado: aceptada
- Fecha: 2026-09-29
- Responsable: Profile
- SDD/contratos afectados: SPEC-02 (RF-006, RF-007 y RNF-032; CA-01–CA-07); SPEC-10 (propiedad de datos, API y errores); SPEC-13 (persistencia del volumen en despliegue); contratos de lectura/edición Profile e `IdentityPublicChanged`/`ProfilePublicChanged`.

## Contexto

Profile es dueño de `displayName`, `bio`, avatar y versión del perfil. `userId` y `handle` son autoridad de Identity; el perfil solo guarda `userId` como referencia externa, sin FK ni acceso a la base privada de Identity. Canales es dueño de la descripción y portada del canal. Los datos públicos no pueden incluir email, contraseña, credenciales ni sesión.

SPEC-02 P1 permite consultar y editar nombre visible, biografía y avatar, no permite cambiar handle y fija JPEG/PNG/GIF hasta 10 MB. El ADR obligatorio de SPEC-02 debe resolver almacenamiento local compatible con despliegue reproducible frente a S3/MinIO, formato de URL y eliminación/reemplazo. El módulo existente usa Java 25, Spring Boot 4.1.1, Spring Security, PostgreSQL 18, Flyway y un directorio configurable `PROFILE_AVATAR_STORAGE`.

## Decisión

1. **Plataforma:** mantener el baseline de Java 25, Spring Boot 4.1.1 y Maven Wrapper que usa Identity. Spring Security valida la identidad llamando a la introspección privada de Identity en cada operación protegida; no se duplica autenticación ni se conserva una copia de credenciales.
2. **Datos estructurados:** PostgreSQL 18 y Flyway; Profile es dueño del esquema `profile` y de las tablas de perfil, permisos de upload y outbox. `userId` es texto/ID externo, sin FK entre dominios. Si una proyección aún no existe, se aplica el fallback público previsto en SPEC-02 mediante lookup de Identity; Identity inaccesible produce `503`, no `404`.
3. **Archivos:** para P1, guardar avatares en un directorio externo al contenedor, configurado por `PROFILE_AVATAR_STORAGE` y montado como volumen persistente. Separar `pending/` y `public/`. La imagen no se guarda como blob en PostgreSQL. Los archivos reciben claves UUID aleatorias e inmutables con extensión derivada del formato real; no se usan nombres de archivo proporcionados por el usuario.
4. **Validación:** inspeccionar y decodificar los bytes en el servidor; aceptar solo JPEG/PNG/GIF, hasta 10 MB y con límite defensivo de dimensiones/píxeles. La extensión o `Content-Type` aportados por el cliente no prueban el formato.
5. **URL pública:** guardar la referencia lógica/URI con clave opaca y servir el archivo por `GET /api/profile/avatars/{key}`. El prefijo público se configura (`PROFILE_AVATAR_PUBLIC_BASE`); por defecto es `/api/profile/avatars`. La URI nunca revela ruta absoluta, raíz del volumen o nombre original. Las claves son inmutables para que un cambio genere una URL nueva y cada objeto publicado pueda servirse con caché pública de larga duración (un año en la implementación actual) sin que una sustitución deje una imagen vieja bajo la misma URL.
6. **Carga, reemplazo y retiro:** upload crea archivo pendiente y permiso de un solo uso (hash de `uploadId`, asociado al usuario, vence a los 15 minutos). PATCH consume el permiso, valida que corresponda al dueño, publica el nuevo objeto y confirma la nueva referencia en la transacción de PostgreSQL. Si falla la actualización, se elimina el objeto nuevo publicado y se conserva el avatar anterior. Tras commit se elimina el temporal y, cuando se reemplaza o retira avatar, se elimina el objeto anterior; un proceso periódico limpia uploads vencidos.
7. **Límite de escalamiento:** la elección del directorio local requiere un volumen persistente. Para más de una réplica de Profile, todas deben usar un almacenamiento compartido con garantías de lectura/escritura compatibles o el módulo debe migrar a S3/MinIO mediante un nuevo adaptador/ADR. No se asume que el disco efímero de un contenedor sea persistente ni compartido.
8. **Eventos:** guardar `ProfilePublicChanged` en outbox dentro de la transacción de perfil. La selección de transporte, dispatcher, ACK y reintentos de entrega es decisión de Integración y permanece fuera de este ADR.

## Opciones consideradas

| Decisión | Opción elegida | Alternativas y motivo para no elegirlas en P1 |
| --- | --- | --- |
| Lenguaje/framework | Java + Spring Boot, igual que Identity | Un stack diferente podría cumplir la API, pero añadiría otro runtime, seguridad y operación sin una necesidad de Profile; compartir lenguaje no comparte modelos ni bases de datos. |
| Datos de perfil | PostgreSQL 18 con esquema propiedad de Profile | Documentos NoSQL simplificarían algunos objetos, pero este modelo pequeño tiene campos acotados, unicidad/relación lógica con su propietario y actualización coordinada con outbox. La obligación global de incluir NoSQL debe resolverse en el sistema donde exista un caso de uso real, no duplicando aquí los datos de identidad. |
| Archivos | Volumen de archivos externo y persistente | Guardar bytes en PostgreSQL mezcla contenido grande con datos transaccionales y aumenta copias/lecturas de la base. S3/MinIO separa mejor almacenamiento de objetos y facilita varias réplicas, pero añade servicio, credenciales, configuración y operación; queda como siguiente opción si se exige Profile multi-réplica. |
| URL y ciclo de vida | URI configurable con clave UUID inmutable; temporal → publicado → referencia DB → limpieza posterior | Nombre original o ruta física filtra datos y permite colisiones/traversal. Sobrescribir una URL estable complica cachés y puede mostrar contenido antiguo. Eliminar primero la imagen actual puede dejar el perfil sin avatar si falla el cambio de DB; por eso primero se publica el nuevo objeto y se elimina el viejo tras commit. |

## Consecuencias

**Beneficios:** ownership claro entre Identity, Profile y Channels; metadatos y archivos se despliegan reproduciblemente sin imponer proveedor externo; los límites de formato/tamaño son comprobables en servidor; la clave inmutable simplifica caché y evita depender del nombre del usuario; fallos de DB al reemplazar conservan el avatar previo.

**Costos y fallos:** el almacenamiento es otra unidad persistente que debe respaldarse junto con PostgreSQL; perder o no montar el volumen puede romper URI ya guardadas. En más de una réplica, un volumen local no compartido puede producir respuestas inconsistentes. El cambio de archivo y la transacción SQL no son una sola transacción distribuida; el orden de publicación, compensación, borrado posterior y limpieza evita la mayoría de estados parciales, pero fallos de proceso pueden dejar archivos huérfanos que requieren reconciliación periódica.

**Compatibilidad:** las rutas REST y el modelo público de SPEC-02 siguen independientes del framework. Los consumidores obtienen el avatar mediante URI, sin acceso al disco de Profile. `userId` sigue siendo referencia opaca; no se introduce una FK ni una consulta directa a Identity. El fallback para perfil ACTIVE no materializado y los 404 indistinguibles para estados no activos se mantienen.

**Requisitos globales:** PostgreSQL cubre la parte SQL del módulo, pero este ADR no declara satisfecho el requisito global de NoSQL ni los requisitos de lenguajes, conectores o procesos de SPEC-13. Integración conserva esas decisiones. La separación de Profile en su propio proceso facilita reinicio independiente, pero el almacenamiento de archivos debe seguir persistente al reiniciarlo.

**Migración/lock-in:** el contrato `AvatarStorage` separa lógica de aplicación y adaptador de archivos. Una migración futura a S3/MinIO debe copiar objetos conservando claves o publicar URIs nuevas, validar checksums, cambiar configuración/base pública y retirar el volumen solo tras confirmar que no quedan referencias activas.

## Verificación

- Comprobar los casos de SPEC-02: GET público no filtra campos privados, PATCH solo permite editar al principal, handle no cambia, fallback activo, 404 uniforme para inactivo/inexistente y 503 ante caída de Identity.
- Rechazar bytes vacíos, formatos no permitidos, imágenes falsas y archivos sobre 10 MB; verificar límites de píxeles. Confirmar upload de un uso y expiración a los 15 minutos.
- Forzar errores antes y después de publicar archivo y durante commit: el perfil anterior debe continuar válido ante fallo, los objetos huérfanos deben limpiarse y el reemplazo exitoso debe eliminar el viejo solo tras commit.
- Reiniciar Profile y su contenedor manteniendo el volumen; las URI existentes deben seguir resolviendo. Verificar que un despliegue con más de una réplica use el mismo almacenamiento consistente antes de habilitarlo.
- Evidencia registrada al preparar este ADR: la suite unitaria Profile (10 pruebas) pasó en la verificación previa del repositorio; no hay evidencia de prueba integrada con volumen/Compose en ese momento porque Docker Engine no estaba disponible.

## Revisión

Profile revisará esta decisión antes de ejecutar más de una réplica, cuando el volumen compartido no cumpla disponibilidad/latencia, cuando el tamaño/retención de medios lo justifique, o ante una política que requiera URLs firmadas, CDN o reglas de acceso distintas. El dueño de Profile decide el adaptador; Integración aprueba los cambios de montaje, proxy, URL pública y despliegue; un cambio de semántica pública requiere actualizar SPEC-02/contratos y registrar ADR sustitutivo.
