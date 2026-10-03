# ADR-002: Perfil público y almacenamiento de avatares en Cuentas

- Estado: aceptada
- Fecha: 2026-10-01
- Responsable: Core / Cuentas
- SPEC/contratos afectados: SPEC-01 (RF-006, RF-007, RNF-032), SPEC-03, SPEC-10 y SPEC-13; perfil público, edición self y objetos avatar.

## Contexto

Perfil pertenece a Cuentas en Core y posee displayName, bio, avatarUri y profileVersion. La cuenta
posee email/handle/credenciales; Canales posee descripción y portada. Compartir proceso y PostgreSQL
permite crear el perfil inicial con la cuenta y validar sesión localmente, manteniendo DTO públicos
distintos de datos privados.

P1 admite avatar opcional JPEG/PNG/GIF hasta 10 MB y al menos 200×200 px. Nombre visible y bio son
editables; el handle permanece inmutable. Debe conservarse el objeto anterior ante fallo de reemplazo.

## Decisión

1. Usar Java/Spring y PostgreSQL Core de ADR-001. Tabla de perfil con FK a cuenta, permisos de upload ligados al propietario y repositorio encapsulado; registro crea displayName=handle, bio vacía, avatar nulo y versión 0. Validación de sesión por interfaz local de Cuentas.
2. PATCH parcial serializado por usuario; omisión conserva, bio:null limpia y avatarUploadId:null retira. Solo cambios efectivos incrementan versión. Canal/Consultas leen DTO/vista pública local; Chat recibe snapshot del autor en su contexto y conserva el de mensajes anteriores.
3. Guardar archivos en directorio externo al contenedor, configurable mediante PROFILE_AVATAR_STORAGE y montado como volumen persistente. Separar pending/ y public/. Clave UUID inmutable y extensión del formato real; no usar nombres de archivo del cliente ni guardar imagen como blob SQL.
4. Inspeccionar/decodificar bytes; JPEG/PNG/GIF real <=10 MB y ancho/alto >=200 px. Límite defensivo de píxeles/dimensiones. Content-Type y extensión del cliente no prueban formato.
5. Upload crea temporal y permiso de un uso: hash de uploadId, dueño y vencimiento 15 min. PATCH valida/consume permiso y publica objeto nuevo antes del commit de referencia SQL. Rollback elimina el objeto nuevo; tras commit limpia temporal y retira objeto anterior cuando corresponde. Reconciliación periódica elimina temporales vencidos/huérfanos sin borrar objetos referenciados.
6. URI pública GET /api/profile/avatars/{key}, prefijo PROFILE_AVATAR_PUBLIC_BASE (default /api/profile/avatars). Clave opaca y caché de larga duración con URL nueva en cada reemplazo; no revelar ruta física/nombre original. La política de caché debe contemplar privacidad y retiro antes de introducir borrado definitivo de cuentas.
7. Una réplica Core en P1. Varias réplicas requieren volumen compartido consistente o adaptador S3/MinIO seleccionado por ADR; disco efímero no es persistencia. Backup de PostgreSQL y objetos como conjunto recuperable.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Perfil SQL dentro de Cuentas | Elegida: relación 1:1, defaults de registro y lectura/edición local. |
| Perfil en servicio/NoSQL separado | Obliga a activación y lookup remoto; el uso NoSQL de sistema se asigna al historial Chat. |
| Volumen persistente | Elegido para P1: objetos y URI sin proveedor adicional; requiere respaldo/reconciliación. |
| Blob PostgreSQL | Aumenta tamaño y costo de lectura/backups transaccionales; separa peor los archivos. |
| S3/MinIO | Facilita varias réplicas y objetos; requiere servicio, credenciales y operación. Evolución del adaptador si el volumen deja de cumplir. |
| Sobrescribir URL o usar nombre original | Complica caché, colisiones y seguridad; se eligen claves UUID inmutables. |

## Consecuencias

Perfil no introduce fallo de red independiente ni replica identidad. Archivos y SQL no forman una
transacción única: el orden, compensación local y reconciliación conservan el anterior y reparan
huérfanos tras crash. Un volumen perdido puede romper URI persistidas; respaldo/restauración deben
verificar checksums y referencias. El contrato de almacenamiento permite cambiar adaptador preservando
claves o publicando URI nuevas antes de retirar objetos originales.

Los lectores Core consultan el perfil localmente. El snapshot Chat es histórico;
editar perfil no cambia los autores de mensajes ya persistidos. Fallo SQL es 503, no usuario inexistente.

## Verificación

Consulta público/self y privacidad, acceso propio/ajeno, campos permitidos, PATCH parcial/no-op,
validación de formato/tamaño/píxeles/dimensiones, permiso de un uso/expiry y concurrencia de reemplazos.
Inyectar fallos de archivo/SQL/commit y reinicio: conservar referencia anterior ante rollback y recuperar
huérfanos. Reiniciar con volumen persistente y comprobar URI; probar almacenamiento compartido antes
de habilitar varias réplicas.

## Condiciones para cambiar la decisión

Core/Cuentas cambia adaptador ante múltiples réplicas, límites de volumen/latencia o política de acceso,
CDN o retención. Coordinar URL/proxy/montajes y restore con Integración y consumidores.
