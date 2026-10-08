# ADR-008: Almacenamiento S3 privado para avatares y portadas

- Estado: aceptada
- Fecha: 2026-10-07
- Responsable: Core / Cuentas y Canales
- ADR relacionadas: actualiza el almacenamiento de [ADR-002](ADR-002-profile-persistencia-y-avatar.md) y [ADR-004](ADR-004-canales-en-core.md)
- SPEC/contratos afectados: SPEC-01, SPEC-03, SPEC-12 y SPEC-13; RF-006…RF-010; URI de avatar/portada y despliegue de Core.

## Contexto

Avatares y portadas ya se validan y guardan fuera de PostgreSQL. El proveedor filesystem necesita
volumen persistente y compartido al ejecutar varias réplicas Core. Los contratos ya publican
`avatarUri` y `bannerUri` como rutas estables de lectura; cambiar el almacén no debe cambiar esas rutas,
los IDs de objeto ni requerir permisos del navegador sobre el bucket.

## Decisión

1. Usar un bucket S3 privado como proveedor predeterminado para ambos tipos de imagen mediante AWS SDK
   para Java 2.x. Los objetos usan namespaces configurables y claves `pending/{uuid.ext}` y
   `public/{uuid.ext}`. El proveedor filesystem queda disponible para desarrollo y pruebas cuando se
   selecciona explícitamente.
2. Mantener `GET /api/profile/avatars/{key}` y `GET /api/channels/banners/{key}` como URLs públicas
   estables. Core hace GET autenticado a S3 con su identidad de servicio y responde los bytes con el
   `Content-Type` validado y caché actual; el navegador no recibe credenciales, ACL ni URL directa S3.
   Los objetos no se suben con ACL pública. Una CDN futura puede distribuir estas rutas con su propio
   origen privado, mediante una decisión de despliegue revisada.
3. Los uploads conservan el ciclo actual: escritura en `pending`, copia al área `public` antes del
   commit SQL, compensación ante rollback, borrado posterior al commit y reconciliación de huérfanos.
   El bucket contiene los bytes; PostgreSQL conserva la clave opaca, el tipo y las referencias de dominio.
4. Usar la cadena de credenciales por defecto del SDK. En AWS, preferir rol IAM unido a la tarea/instancia;
   limitar permisos al bucket y prefijos de Core para ListBucket, GetObject, PutObject y DeleteObject.
   CopyObject requiere lectura del origen y escritura del destino. Región obligatoria; endpoint override
   y path-style quedan para proveedores compatibles.
5. No cambiar el esquema SQL ni las rutas públicas. La base actual está vacía; usar el proveedor
   predeterminado requiere configurar un bucket existente y sus prefijos, sin copiar objetos ni cambiar
   registros.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Bucket S3 privado y rutas públicas Core | Elegida: bucket no expuesto, rutas/contratos inmutables y almacén común para réplicas. Core añade lecturas de red en la ruta de imágenes. |
| Bucket con lectura pública directa | Rechazada: expone el almacén y sus políticas al navegador y acopla contratos al proveedor/endpoint. |
| URL prefirmada entregada al navegador | Rechazada para lectura pública de larga duración: expira y complica el caché/URI estable. Puede evaluarse para upload directo si el tamaño o tráfico lo justifican. |
| Volúmenes locales | Se mantienen como alternativa explícita para desarrollo y pruebas; una réplica por volumen o configuración compartida consistente. |
| Blob en PostgreSQL | Rechazado: aumenta tablas/backups SQL y acopla la disponibilidad de imágenes a la base. |

## Consecuencias

S3 es el proveedor predeterminado, por lo que cada entorno debe configurar el nombre de un bucket
existente; los proveedores compatibles pueden requerir endpoint y path-style. El mismo bucket sirve a
todas las réplicas y separa objetos de la imagen efímera de cada contenedor.
La plataforma requiere permisos IAM, conectividad y operación del bucket; una falla del proveedor afecta
uploads y la lectura de imágenes. Core continúa como punto de entrada público, por lo que costo/latencia
de GET y necesidad de CDN deben observarse antes de aumentar el tráfico.

La escritura S3 y la transacción SQL no son atómicas. El orden y compensaciones actuales evitan sustituir
la imagen anterior antes del commit; la reconciliación retira objetos huérfanos.

## Verificación

Verificar el adaptador contra el endpoint S3 configurado: upload validado a `pending`, copia a `public`,
GET por las rutas Core, Content-Type, caché, claves inválidas/no existentes, deletes, listados y
reconciliación. Inyectar fallos de S3 y SQL para comprobar rollback y conservación del objeto anterior.
Probar permisos mínimos, bucket privado, credenciales del entorno real, restauración y varias réplicas
antes de habilitar el despliegue escalado. La decisión aceptada no constituye por sí sola evidencia de
esas pruebas.

## Revisión

Revisar si el tráfico de imágenes hace necesario servir las rutas por CDN, si el costo/latencia de GET
por Core supera el presupuesto o si cambia el proveedor de objetos. Toda CDN debe conservar claves
inmutables, caché y origen privado sin alterar DTOs públicos.
