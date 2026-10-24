# Broker P2: Streaming → Core y Chat

RabbitMQ es un componente interno compartido. La selección y sus límites están en
[ADR-016](../../docs/adr/ADR-016-rabbitmq-eventos-p2.md). Se implementan ciclo de sesión y
snapshots Discovery; las otras siete familias permanecen pendientes de sus responsables.
Las dieciocho suscripciones se aprovisionan para conservar las copias de cada dominio.

## Arranque

Desde la raíz, en Linux/macOS o WSL, con los requisitos del README principal:

    python3 infra/local/dev.py init --broker
    python3 infra/local/dev.py build --broker
    python3 infra/local/dev.py up --broker
    python3 infra/local/dev.py status --broker
    python3 infra/local/dev.py down --broker

Detener emisiones y los servicios antes de cambiar de transporte. init --broker conserva
contraseñas/volúmenes y añade certificado/credenciales/definiciones en el estado local ignorado.
Repetirlo actualiza la definición sin rotar las claves existentes. El Compose raíz continúa
arrancando P1 sin broker si no se indica --broker; no combinar instancias de ambos transportes.

El perfil añade un contenedor permanente broker y su volumen. broker-ops es una herramienta
efímera con perfil tools; no cuenta como componente de negocio. No publica AMQP ni administración
en el host. Cada dominio conserva su almacén privado. HTTP sigue para contexto/reconstrucción,
WebSocket para mensajes al cliente y HLS para video.
build --broker prepara también la imagen de operación para que esté disponible antes de una caída.
Los servicios reintentan conexión de forma independiente; su arranque no exige que el broker
ya esté saludable. Las operaciones de dominio no esperan el ACK de todos los consumidores.

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

El registro neutral conserva nueve tipos/dieciocho suscripciones; solo dos payloads están listos.
El código de cada servicio valida su contrato. Los criterios de recuperación/ACL/frescura
de SPEC-17 siguen sujetos a ejecución integrada; arrancar el contenedor no acredita esos criterios.
