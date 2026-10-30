# Pruebas compartidas

Las pruebas internas de cada módulo están junto a su código. Esta carpeta reúne las que
comprueban la colaboración entre módulos:

- [contracts](contracts/README.md): compatibilidad entre proveedores y consumidores.
- [integration](integration/README.md): flujos entre módulos y servicios.
- [e2e](e2e/README.md): recorridos completos de usuario.

Cada suite indica los componentes que usa, cómo se ejecuta y qué criterios comprueba.

## VOD (SPEC-14)

`python3 tests/integration/p2-vod/run.py` construye y ejecuta una suite Go con PostgreSQL, MinIO,
RabbitMQ y medios H264 TS/fMP4 reales generados por FFmpeg. Incluye emisión RTMP real H264/AAC
con MediaMTX fijado, reconexión, reinicio de captura, cierre certificado y validación VOD; también
retirada durante emisión sin detener el publisher, limpieza y SIGKILL sin certificar cola.
Crea red/volúmenes desechables,
no publica puertos y limpia solo recursos con su etiqueta de ejecución. No usa la instalación
local. Ver [límites de la evidencia](../services/vod/README.md#verificación-reproducible): Core
es un puerto simulado aquí, permisos Core se prueban en VodAccessIT, y OBS multicalidad,
Chat durable, S3 externo y reproducción audiovisual larga se aceptan por separado.
