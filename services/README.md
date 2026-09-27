# Servicios de dominio

Cada subcarpeta contiene la lógica backend del dominio indicado. La carpeta define ownership de código,
no obliga a usar un proceso o contenedor independiente. Un despliegue puede agrupar módulos solo si
cumple las restricciones y límites de SPEC-09…SPEC-13. Usa contratos publicados para comunicarte con
otros módulos; no accedas directamente a sus almacenes.
