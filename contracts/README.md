# Contratos compartidos

La semántica canónica está en docs/contratos_modelo_datos.md y en las SPEC de integración. Esta carpeta
es para artefactos neutrales generados desde esa fuente y usados por consumidores; no mantengas una
segunda versión manual del contrato. [ADR-012](../docs/adr/ADR-012-contratos-generados-p1.md) selecciona
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
