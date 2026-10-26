# Contratos compartidos

El significado de las operaciones está en docs/contratos_modelo_datos.md y las SPEC de integración.
Aquí mantenemos la estructura en `p1.json` y generamos los artefactos que usan los consumidores.
Para cambiar un contrato se actualizan sus fuentes y se regeneran los archivos derivados. [ADR-012](../docs/adr/ADR-012-contratos-generados-p1.md) selecciona
JSON Schema 2020-12 y SDL. Con Python 3.12, desde la raíz:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r contracts/requirements.txt
.venv/bin/python contracts/generate.py --write
.venv/bin/python contracts/generate.py --check
.venv/bin/python -m unittest discover -s tests/contracts -p 'test_*.py'
```

En Windows usa `.venv\Scripts\python` en lugar de `.venv/bin/python`. `--check` es de solo lectura y
falla si los artefactos divergen, un ejemplo no valida, hay referencias remotas/inexistentes, un
payload público contiene nombres sensibles o el SDL Core difiere. CI ejecuta el mismo comando.
Los números/versiones interoperables se limitan a enteros exactos de JavaScript; timestamps son UTC.
`--payload PublicStream archivo.json --public` valida una respuesta capturada sin imprimir su contenido.

`operations.json` inventaría HTTP/WS/binarios y `local-interfaces.json` distingue los límites Core.
p1.d.ts deriva tipos TypeScript de los schemas JSON y tipos GraphQL del SDL; Web importa estos tipos y graphql-queries.json mediante @contracts. No editar declaraciones generadas a mano.
Los ejemplos verifican forma; las [pruebas de consumidores reales](../tests/contracts/README.md)
verifican permisos, errores y comportamiento. Contextos Chat y bootstrap necesitan SPEC-11;
schemas generados no acreditan que esos endpoints estén implementados.

## Extensión P2 de eventos y recuperación

La fuente p2.json extiende los schemas P1 sin modificar sus artefactos. Registra nueve familias
y dieciocho suscripciones; los payloads de ciclo, snapshot Discovery y recuperación están listos.
RF-004 incorpora solicitud/confirmación públicas y claim/result privados. Las demás entradas
permanecen pending-owner y no admiten publicación como contratos validados. Las otras operaciones
HTTP/WS/media P2 siguen pendientes de sus responsables.

    python contracts/check_p2.py --write
    python contracts/check_p2.py
    python contracts/check_p2.py --event /ruta/a/envelope.json
    python contracts/check_p2.py --payload P2RecoveryAccepted /ruta/a/respuesta.json --public

El primer comando genera contracts/generated/p2.schema.json y los schemas/tipos/inventarios de
contracts/generated/p2. Web importa @contracts/p2. No editar los derivados a mano. La validación
es offline, comprueba referencias, ejemplos válidos/negativos, identidad/versiones, privacidad,
permisos, idempotencia y trazabilidad de las operaciones, además de detectar drift.
El arranque RabbitMQ y los SDK de cada lenguaje se documentan en infra/broker/README.md y ADR-016.

## Perfil de subtítulos VOD

[SPEC-22](../docs/spec-p2/spec_22_subtitles.md) define el perfil p2-webvtt-text-v1 y su alcance.
El checker `webvtt.py` valida bytes/tiempos sin publicar ni modificar archivos:

```sh
python contracts/webvtt.py tests/contracts/fixtures/subtitles/education-es.vtt --duration-ms 12000
python -m unittest discover -s tests/contracts -p 'test_*.py'
```

`check_p2.py` comprueba además ejemplos, referencias de operaciones, paths y páginas de pistas.
La duración usada en pruebas es ficticia; el futuro proveedor debe consultar la duración/mapping
de VOD y aplicar permisos. No se acredita VOD funcional ni se decide su runtime mediante Python.

Las cuatro operaciones `p2.subtitles.*` son `contract-only`. El mismo generador conserva Recuperación.
Prueba adicional con Ajv (tras instalar las dependencias Web):

```sh
node tests/contracts/test_subtitle_schemas.mjs
```
