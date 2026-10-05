# Core / Catálogo

Catálogo dentro del único runtime Core: Spring/JDBC, PostgreSQL y Flyway compartidos. No contiene
build, base, configuración, token ni puerto independientes. `GET /api/taxonomy` es público y devuelve
`catalogVersion`, `categories` y `tags`; cada valor contiene `id`, `name`, `active` y solo se listan activos.

Definición: [SPEC-06](../../../../../../../../docs/spec-p1/spec_06_tax.md),
[contratos](../../../../../../../../docs/contratos_modelo_datos.md) y
[ADR-006 (propuesta)](../../../../../../../../docs/adr/ADR-006-taxonomia-en-core.md).

`application/CatalogValues` publica lookup local con tombstones y validación local. Sus locks
solo duran la transacción Core y no se extienden a Streaming. `CatalogContexts` y
`CatalogSelections` resuelven/validan valores tipados con versión de una misma sentencia SQL.
Canales consume esas interfaces para `owner-context`; `catalog-values` pertenece a Taxonomía.
Streaming conserva asociaciones/labels en su SQL, sin FK ni transacciones entre bases.
Se deduplican tags antes del máximo de cinco; omitidos se preservan en el consumidor.

V3 crea `taxonomy.categories`, `taxonomy.tags`, `taxonomy.catalog_state`, `taxonomy.public_categories`
y `taxonomy.public_tags`. Las vistas publican columnas explícitas, incluyendo valores inactivos;
los consumidores locales usan interfaces/vistas revisadas, nunca escrituras sobre tablas del módulo.
Nuevos valores o bajas lógicas se incorporan mediante migraciones SQL controladas, sin API CRUD.
La API usa una consulta SQL para versión/listas y `Cache-Control: no-cache`, sin ETag o caché de proceso.

Pruebas en `src/test/java/streaming/core/taxonomy`; ejecución desde la raíz con
`./infra/local/test-core.ps1`. La asociación de metadatos y filtros Discovery siguen perteneciendo
a sus módulos; esta implementación no incluye esas funciones, Web ni accesibilidad.
