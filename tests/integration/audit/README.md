# Aceptación Security Logger y Auditor

```sh
python3 tests/integration/audit/run.py
# Solo si las tres imágenes corresponden al checkout actual:
python3 tests/integration/audit/run.py --skip-build
```

Requiere Docker, Python >=3.12 y OpenSSL. Construye Core/VOD/Audit; usa PostgreSQL 18,
RabbitMQ 4.3.7 y Go 1.26 en contenedores. Red y nombres aleatorios, puertos solo loopback,
credenciales temporales privadas. El runner elimina únicamente sus recursos al terminar.
Las cachés de compilación Go persisten como volúmenes de herramientas.

El escenario ejecuta registro/login Core real, provisión ADMIN explícita, creación de metadatos
de grabación por API privada de captura, cinco intentos de edición ajena, edición del propietario,
suspensión administrativa, retirada/borrado VOD y consulta protegida. No escribe datos de negocio VOD mediante SQL;
SQL de fixture se usa para provisión ADMIN, inyección de fallo y reentrega del outbox.
No genera video ni utiliza S3: prueba el plano de control/auditoría, no reproducción o limpieza
física. Core–Auditor, Auditor–SQL y AMQP usan TLS con CA/hostname verificados. HTTP y SQL
de Core/VOD quedan confinados a esta red de prueba. El despliegue global con proxy/media
y la restauración completa requieren sus pruebas de integración propias.

Comprueba: evento por intento, actor verificado, correlación, filtros/paginación, límites,
consulta 401/403 y revocación ADMIN, auditoría de lecturas, rollback si falla outbox,
idempotencia de comando, consumer dedupe, conflicto a DLQ, roles SQL sin alteración/borrado,
reinicio de Logger/VOD/broker y recuperación de pendientes, exclusión de secretos.
Imprime JSON con resultado y latencia nominal medida. Objetivo <=2s no equivale a benchmark
bajo carga; la Persona 6 mantiene el análisis de rendimiento global.

Pruebas de schema/contratos adicionales desde raíz con las dependencias contracts/requirements.txt:

```sh
python3 contracts/check_p2.py
python3 -m unittest discover -s tests/contracts -p 'test_*contracts.py'
```

Ninguna prueba borra tablas o volúmenes de la instalación local.
