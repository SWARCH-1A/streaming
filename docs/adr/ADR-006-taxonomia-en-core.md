# ADR-006: Catálogo SQL y contextos privados de taxonomía en Core

- Estado: propuesta
- Fecha: 2026-10-03
- Responsable: Core / Catálogo; revisión con Emisiones y Consultas
- SDD/contratos afectados: SPEC-06, SPEC-04, SPEC-07, SPEC-10; RF-066…RF-069; RNF-039, RNF-041, RNF-042, RNF-048, RNF-050.

## Contexto

ADR-005 conserva Java/Spring/PostgreSQL para Catálogo dentro de Core y separa Streaming. SPEC-06 requiere un vocabulario
controlado, IDs estables, incorporación de valores sin reconstruir Web y conservación de etiquetas
de metadatos antiguos. Emisiones será dueño de las asociaciones y Consultas de sus filtros.
Esta propuesta documenta la implementación de Catálogo; no registra una aceptación del equipo ni
declara terminadas las integraciones con consumidores pendientes.

## Decisión propuesta

- Reutilizar JDBC/Flyway y la seguridad Core, sin dependencias nuevas ni runtime/base propios.
  V3 crea el esquema `taxonomy`, `categories`, `tags`, `catalog_state` y vistas públicas con columnas
  `id`, `name`, `active`. V1/V2 permanecen sin cambios.
- V4 conserva V1–V3 y agrega `taxonomy.value_ids`, registro interno con ID único global y tipo
  inmutable. Backfill y triggers AFTER INSERT reservan los IDs transaccionalmente; la PK arbitra
  inserciones concurrentes entre categorías/tags. DELETE/TRUNCATE y cambio de ID/tipo se rechazan.
  Colisiones preexistentes detienen la migración sin renombrar ni borrar datos; actualizar el
  esquema no incrementa la versión del catálogo. No se exige un prefijo para IDs existentes.
- Sembrar siete categorías y ocho etiquetas con IDs opacos fijos `cat_`/`tag_` y 32 caracteres
  hexadecimales. La versión del catálogo inicial es 1. Evolucionar datos mediante migraciones SQL
  revisadas; no añadir una API administrativa ni un archivo de vocabulario en el frontend.
- Incrementar `catalogVersion` una vez por valor creado o cambio real de nombre/estado; no-op no
  incrementa. Proteger identidad y tombstones rechazando cambio de ID, DELETE y TRUNCATE en tablas
  de valores. La baja lógica conserva el último nombre y permite reconstruir metadatos anteriores.
- Servir `GET /api/taxonomy` anónimo como snapshot SQL único de versión/listas activas, ordenadas por
  nombre NFKC/minúsculas con `Locale.ROOT` y luego ID. Usar `Cache-Control: no-cache`, sin ETag ni
  caché de proceso; una lectura posterior a commit obtiene los cambios sin invalidación remota.
- Publicar `CatalogValues.find` para consumidores locales y `CatalogContexts`/`CatalogSelections`
  para resolver/validar IDs tipados con versión y valores de una misma sentencia SQL. Canales
  publica el contexto owner usando Cuentas y Catálogo; Taxonomía publica la resolución batch.
  Streaming consume JSON privado, conserva asociaciones/labels en su SQL y confirma dentro de
  un segundo desde comenzar la consulta. No existen FK ni transacciones entre ambas bases.
  Deduplicar antes del máximo de cinco tags; omitidos en PATCH se preservan en Streaming.
- Añadir un conector privado Core independiente, apagado por defecto. Las dos rutas privadas
  exigen `X-Service-Name: streaming` y comparación constante de `X-Service-Token`. El puerto
  público las rechaza incluso con token válido; headers forwarded no cambian esta decisión.
  El listener privado requiere TLS con PKCS12; HTTP solo se habilita explícitamente en desarrollo
  aislado. Sus POST no usan cookies/CSRF de navegador; el resto conserva CSRF. La credencial de
  sesión viaja exclusivamente en `X-Session-Credential` y nunca se registra ni persiste.
- Comprobar permisos de credencial por cada POST: `CORE_STREAMING_SERVICE_TOKEN` conserva ambos
  contextos para el cliente Rust actual; `CORE_STREAMING_CATALOG_SERVICE_TOKEN`, opcional y
  distinto, solo resuelve catálogo. El segundo nunca autoriza owner-context, aunque reciba una
  sesión válida. Credencial adicional inválida o igual a la principal impide habilitar la entrada.
- Mantener el fallo SQL como `503 CORE_UNAVAILABLE` y las selecciones inválidas como
  `422 INVALID_TAXONOMY` con errores de campo. Discovery adapta sus filtros a `INVALID_FILTER`
  según su contrato GraphQL, sin cambiar la semántica pública del consumidor.

## Opciones consideradas

| Opción | Ajuste y consecuencia |
| --- | --- |
| Valores en código o listas duplicadas en Web | Simple al inicio, pero cambiar vocabulario exige recompilar consumidores y divergen IDs/labels. |
| Servicio Taxonomy, almacén o caché independiente | Añade fallos y sincronización; contradice la ubicación de Catálogo en Core. |
| SQL Core, Flyway, interfaces locales y contexto privado | Implementación propuesta: datos controlados y contrato JSON con Streaming, sin plataforma adicional. |

## Consecuencias

Catálogo comparte disponibilidad/release de Core. PostgreSQL conserva IDs y versión tras reinicio;
no se exportan credenciales ni datos privados. StreamConfig pertenece al SQL privado de Streaming;
sus referencias a Catálogo son opacas con snapshots de labels. Las vistas no transfieren escritura
a Discovery. `CatalogValues.requireActive*` conserva bloqueos únicamente para llamadas locales;
el contexto remoto no mantiene locks. Una inactivación posterior puede coincidir con un comando
autorizado en vuelo; la siguiente autorización debe rechazarla conforme a ADR-005.

Agregar vocabulario requiere una migración nueva, desplegar Core y volver a consultar la API;
Web no necesita reconstrucción. Las credenciales de la relación Core–Streaming tienen permisos
explícitos; la principal conserva ambas rutas y la adicional permite acceso solo al catálogo.
Comparten la configuración Core, sin `.env` por módulo. TLS, overlays locales y pruebas están en el
[runbook Docker](../../infra/local/README.md); construir la imagen omite pruebas y no acredita
su resultado. Un error en una migración detiene el arranque; no activar baseline/clean ni editar
historial aplicado para ocultarlo.

## Verificación

Pruebas del proveedor: 7/8 valores e IDs/versiones estables; 0/5/6 tags únicos y duplicados;
IDs faltantes, inactivos y de tipo incorrecto; snapshot público sin inactivos; no-op/cambio real;
tombstone conservado, alta posterior visible y migración desde V2. Verificar bloqueo/transacción,
lectura pública sin sesión y errores SQL sin detalles internos. Ejecutar `infra/local/test-core.ps1`
y conservar informes Maven locales; esta lista no implica que una ejecución haya pasado.

Streaming debe aportar asociaciones, rollback de edición inválida y edición LIVE; Discovery,
filtros exactos/AND y exclusión de estados no reproducibles. Web y accesibilidad esperan su base
compartida. Estos recorridos y el perfil SPEC-13 permanecen pendientes de integración.

## Revisión

Compartir con responsables de Core/Emisiones/Consultas antes de aceptar el contrato consumidor.
Registrar su revisión y cambiar el estado solo tras decisión del equipo. Revisar la estrategia
si aumenta el volumen/costo de lectura, se necesita administración de catálogo o se propone
extracción conforme a ADR-003; cualquier caché deberá definir invalidación y evidencia de frescura.
