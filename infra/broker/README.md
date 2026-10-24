# Broker P2: Streaming → Core y Chat

RabbitMQ es un componente interno compartido. La selección y sus límites están en
[ADR-016](../../docs/adr/ADR-016-rabbitmq-eventos-p2.md). Se implementan ciclo de sesión y
snapshots Discovery; las otras siete familias permanecen pendientes de sus responsables.
Las dieciocho suscripciones se aprovisionan para conservar las copias de cada dominio.

## Arranque

Desde la raíz, en Linux/macOS o WSL, con los requisitos del README principal:

Los comandos habituales incluyen RabbitMQ automáticamente:

    python3 infra/local/dev.py init
    python3 infra/local/dev.py build
    python3 infra/local/dev.py up
    python3 infra/local/dev.py status

Abrir https://localhost:3445 con la CA local confiada según la
[guía de certificados](../local/certificados.md). Se conservan las cuentas, sesiones y volúmenes
existentes. Para detener este perfil conservando los datos:

    python3 infra/local/dev.py down

Al actualizar una instalación HTTP P1, detener las emisiones antes de cambiar de transporte.
init conserva contraseñas/volúmenes y añade certificado/credenciales/definiciones en el estado local ignorado.
Repetirlo actualiza la definición sin rotar las claves existentes. Los perfiles de aceptación
default/load bajo infra/p1 conservan HTTP y sus datos separados. El indicador --broker se
acepta por compatibilidad; el perfil local ya lo incluye en todas sus operaciones.

El perfil añade un contenedor permanente broker y su volumen. broker-ops es una herramienta
efímera con perfil tools; no cuenta como componente de negocio. No publica AMQP ni administración
en el host. Cada dominio conserva su almacén privado. HTTP sigue para contexto/reconstrucción,
WebSocket para mensajes al cliente y HLS para video.
build prepara también la imagen de operación para que esté disponible antes de una caída.
Los servicios reintentan conexión de forma independiente; su arranque no exige que el broker
ya esté saludable. El comando up espera a los servicios con healthcheck y a Web/API antes de
declarar listo el entorno. Las operaciones de dominio no esperan el ACK de todos los consumidores.
En status, el broker debe alcanzar healthy. AMQP usa TLS en el puerto interno 5671 y
administración HTTPS en 15671; ambos permanecen dentro de la red Docker. La configuración
declara solo management.ssl para desactivar administración HTTP; management.tcp.port no
admite el valor none. El script de operación se instala como broker_ops.py para conservar
el acceso al módulo operator de la biblioteca estándar Python.
El healthcheck usa el usuario rabbitmq para evitar crear una cookie Erlang de root durante
un arranque lento.

Para revisar el arranque y las conexiones:

    docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml logs --tail=100 broker live core chat

## Fallos, métricas y DLQ

Las publicaciones se reintentan desde el outbox, con ID/body estables. Core confirma después de
su transacción SQL y Chat después del script Redis. Una copia pendiente de Chat no detiene a Core.
Un resultado desconocido puede repetirse; no se promete exactly-once de transporte.

Hay tres reintentos diferidos (1/5/30 s), luego DLQ; un mensaje inválido/conflictivo va directamente
a DLQ. El envelope permanece intacto. La transferencia a retry/DLQ se confirma antes del ACK
del origen. No hay expiración automática de responsabilidades pendientes.

Los siguientes comandos usan la credencial de operador privada y verifican TLS:

    docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml run --rm broker-ops metrics
    docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml run --rm broker-ops inspect --subscription core.lifecycle
    docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml run --rm broker-ops redrive --subscription core.lifecycle --event-id EVENT_ID --reason "Dependencia recuperada"
    docker compose --env-file infra/p1/.state-local/environment.env -f compose.yaml -f infra/broker/compose.yaml run --rm broker-ops close --subscription core.lifecycle --event-id EVENT_ID --reason "Incidencia resuelta y reconciliada"

inspect devuelve metadatos del primer mensaje sin retirarlo. redrive/close exigen que el ID
coincida con esa cabecera; no recorren ni descartan otros mensajes. Redrive publica únicamente
a la suscripción original, con confirmación; si falla antes del ACK la entrega vuelve a DLQ.
Un envelope sin ID válido se identifica para la operación mediante el hash de su cuerpo.
La auditoría se sincroniza en broker-audit antes de confirmar retiro. No modificar un payload
usando el mismo ID para eludir deduplicación.

metrics muestra pendientes, entregados sin ACK y timestamp de cabecera cuando está disponible.
Los servicios registran eventId/correlationId/consumer, resultado, edad e intentos; Streaming
conserva sus métricas de backlog/edad de outbox. Supervisar DLQ no vacía, edad >5 s en estado
nominal, crecimiento del inbox y límites de disco/memoria/colas.

## Persistencia y recuperación

El nodo rabbit@broker y broker-data deben conservarse juntos. down conserva volúmenes;
reset los elimina y solo procede sobre datos desechables. Un reinicio mantiene colas/suscripciones
y eventos confirmados. La pérdida del disco no está cubierta por un nodo único.

Para backup consistente, detener productores/consumidores y broker, respaldar broker-data,
broker-audit y los almacenes de cada servicio junto al estado privado/certificados. Restaurar
con el mismo nodename, secretos y versión compatible; arrancar broker antes de los servicios.
No copiar en caliente el directorio de datos del broker como garantía de recuperación.

Los inbox P2 guardan identidad/hash sin TTL automático, separados de los inbox P1 de una hora/
24 horas. Se exige compactación coordinada antes de ampliar carga/retención. El historial Chat
continúa con la implementación P1; esta integración no acredita el archivo durable/replay P2.

## Contratos y evidencia

    python3 contracts/check_p2.py
    python3 contracts/check_p2.py --event /ruta/a/envelope.json
    python3 -m unittest discover -s infra/p1 -p 'test_*.py'
    python3 infra/broker/smoke_test.py

La prueba smoke requiere Docker, Python 3.12 y OpenSSL. Crea una red, certificados,
credenciales, imagen de operación y volúmenes desechables con nombre aleatorio. No publica
puertos ni modifica el proyecto local. Comprueba el arranque con la configuración del repo,
CLI de operación, TLS y ACL negativas, suscripciones independientes, filtros, persistencia
de un envelope P2 al reiniciar RabbitMQ, inspect/redrive/close, auditoría y métricas de colas.
Al terminar retira sus recursos; si falla conserva diagnósticos privados en la ruta temporal
indicada. Esta prueba acredita infraestructura; no ejecuta efectos de negocio en Core/Chat.
El workflow Broker regression ejecuta esta prueba y compila la imagen Streaming con su
Cargo.lock, para detectar incompatibilidades del adaptador AMQP. También ejecuta las pruebas
de puertos de repositorio y transacciones PostgreSQL de Core/Discovery con compilación limpia,
incluidos fallos después del efecto y antes de completar el inbox P2.

El registro neutral conserva nueve tipos/dieciocho suscripciones; solo dos payloads están listos.
El código de cada servicio valida su contrato. Los criterios de recuperación/ACL/frescura
de SPEC-17 siguen sujetos a ejecución integrada; arrancar el contenedor no acredita esos criterios.
