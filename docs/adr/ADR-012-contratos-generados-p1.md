# ADR-012: contratos neutrales generados desde la definición P1

- Estado: aceptada
- Fecha: 2026-10-08
- Responsable: Integración; alcance autorizado por el usuario, consumidores Core/Streaming/Chat/Web
- SPEC: SPEC-10; contribuye a SPEC-11…13 y RNF-030/038/042/046/047.

## Contexto

El inventario semántico existe, pero no había schemas neutrales ni una comprobación de drift.
El harness Core–Streaming seleccionaba un commit histórico por defecto. La integración necesita
contrastar el checkout que se entrega, manteniendo los contratos pendientes de proveedor explícitos.

## Decisión

Mantener en `docs/contratos_modelo_datos.md` un único bloque `p1-contracts` JSON con `$defs`
JSON Schema Draft 2020-12, ejemplos y operaciones. El SDL GraphQL de ese mismo documento sigue
siendo canónico. `contracts/generate.py` extrae ambos determinísticamente a `contracts/generated`;
`--check` verifica schemas, ejemplos, referencias, inventario y drift sin escribir archivos.
El SDL usado por Core debe coincidir semánticamente con el generado. No mantener OpenAPI/AsyncAPI
adicionales ni generar clientes ligados a modelos privados: P1 no necesita esa duplicación.

Python 3.12 y dependencias fijadas en `contracts/requirements.txt`: `jsonschema` valida Draft
2020-12 con `FormatChecker` explícito y `graphql-core` valida SDL/operaciones. La extracción,
inventario y escritura usan biblioteca estándar. Las referencias son locales al bundle; no se
descargan schemas de red durante la validación. El archivo de dependencias fija también transitivas.

Las entradas reflejan rechazo de campos desconocidos solo cuando el proveedor lo define. Las
respuestas permiten campos adicionales para evolución aditiva; la comprobación de privacidad
rechaza nombres sensibles recursivamente en datos públicos. Respuestas owner que revelan una
clave una sola vez, leases y contextos privados se distinguen de DTO públicos. El schema no
sustituye validación de permisos, normalización Unicode, transacciones, versiones ni deadlines.

Cada operación declara proveedor, consumidor, ownership, auth, request/response, status/errors,
timeout, idempotencia y recuperación. Imágenes/HLS se describen como transporte binario; sus bytes,
MIME y reproducción se prueban en SPEC-12/13. Las interfaces locales Core se documentan en el
inventario y conservan transacción/repositorio, sin convertirlas en HTTP.

El runner PowerShell usa el checkout actual por defecto, con identidad HEAD y diff informados;
`-StreamingRef` selecciona explícitamente una regresión histórica. Los tests consumidores siguen
importando el cliente Rust real. La generación no declara implementados contextos Chat ni bootstrap
pendientes: sus pruebas de proveedor real se completan en SPEC-11 antes de aceptar SPEC-10.

## Opciones consideradas

- Schemas escritos por separado: descartado por drift y dos definiciones manuales.
- Generación desde anotaciones Java/Rust/Go: favorece un proveedor y no cubre límites entre lenguajes.
- OpenAPI + AsyncAPI + SDL: posible evolución, pero añade formatos y herramientas sin necesidad P1.
- JSON Schema + inventario y SDL desde el documento canónico: elegido, neutral y revisable.

## Consecuencias

Cambiar un contrato requiere actualizar su fuente, regenerar y probar sus consumidores. Cambios
incompatibles documentan transición, coexistencia y retiro antes de eliminar el contrato anterior;
el checker no pretende demostrar por sí solo compatibilidad semántica. CI y comando local ejecutan
los mismos checks. Pruebas con proveedores reales se identifican separadamente de los ejemplos.

## Verificación

Schemas/ejemplos válidos y negativos, referencias inexistentes, drift, privacidad pública,
compatibilidad aditiva y una ruptura intencional; SDL y queries reales; runner del checkout actual.
Los resultados ejecutados se registran en Plane, sin logs ni informes temporales en commits.

Referencias: [JSON Schema](https://json-schema.org/understanding-json-schema/reference/object),
[validación y formatos](https://python-jsonschema.readthedocs.io/en/stable/validate/).

## Revisión

Revisar si se necesita publicación de API externa, SDK generado o tooling OpenAPI/AsyncAPI.
Integración coordina la revisión con los consumidores sin transferir ownership de dominio.
