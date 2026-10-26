# Artefactos generados

Solo contenido generado desde una fuente de contrato aprobada. No edites estos archivos a mano ni
introduzcas otra definición manual. ADR-012 selecciona la herramienta; ejecuta
`python contracts/generate.py --write` y comprueba con `--check`. La fuente estructural es
contracts/p1.json; la semántica y el SDL están en docs/contratos_modelo_datos.md. `manifest.json` contiene hashes de los artefactos derivados.

`p2/` deriva de `contracts/p2.json` y hereda los schemas P1. Se genera con
`python contracts/check_p2.py --write` y se comprueba con `python contracts/check_p2.py`.
Su manifest y tipos documentan recuperación y los contratos de subtítulos (sin proveedor); las demás familias P2
permanecen en sus documentos hasta publicar su fuente estructural.
