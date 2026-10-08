# Integración de dominios P1

SPEC-10/11: Core actual (Java/PostgreSQL), Streaming y adaptador actual (Rust/PostgreSQL),
MediaMTX real, Chat actual (Go) y Redis/AOF. El stack propio de Streaming sigue teniendo tres
contenedores; la composición completa de esta prueba tiene siete. El fixture selecciona filesystem
explícitamente: no demuestra S3, Web, proxy, accesibilidad ni el perfil de carga de SPEC-12/13.

Desde la raíz, con Docker Compose >=2.24, Python 3.12+ y un entorno Python separado:

```sh
python -m pip install -r tests/integration/p1-domains/requirements.txt
python tests/integration/p1-domains/run.py
```

El runner construye los proveedores desde el checkout actual. Para reutilizar exclusivamente una
imagen de Streaming cuyos fuentes actuales no cambiaron desde su build, usar `--streaming-image
<imagen>`. `--build-only` prepara imágenes sin arrancar servicios; `--skip-build` solo puede usarse
después de construir **estos mismos fuentes**. No usar una imagen histórica para acreditar un cambio
de código. Las pruebas Core unitarias/SQL/TLS y las de Chat/Redis complementan este recorrido:

```sh
sh services/core/mvnw -f services/core/pom.xml clean verify -P integration
cd services/chat
go test ./...
# Base dedicada de Redis desechable; la suite integration hace FLUSHDB.
CHAT_TEST_REDIS_URL=redis://localhost:6390/15 go test -tags integration ./internal/store/
```

El proyecto fijo `streaming-p1-domains` y sus volúmenes son desechables: cada ejecución elimina solo
ese proyecto al iniciar/finalizar. No ejecutarlo concurrentemente ni reutilizar su nombre para datos
personales. Requiere libres los puertos loopback 18080/18081/18082/18085/18086/18888/11935/15438/15440;
el runner de contratos usa algunos de ellos y debe ejecutarse en otro momento. Los secretos se
generan por ejecución y no se imprimen. El log diagnóstico queda en un directorio temporal fuera del
repo; revisarlo antes de compartirlo, pues los procesos multimedia pueden registrar su URL privada.

El HTTP interno es un opt-in explícito de desarrollo aislado. La prueba `CoreChatIT` verifica el
listener privado **HTTPS** con certificado efímero, permisos por ruta/servicio y rechazo por el puerto
público; este fixture no acredita TLS de todos los saltos de un despliegue. Eso pertenece a SPEC-13.

El recorrido publica una fuente RTMP y decodifica un frame HLS; no fuerza LIVE mediante SQL.
Comprueba ausencia/configuración/canal compuesto, contexto/autor fresco, WS y dedupe, perfil nuevo
frente a snapshots de mensajes previos, reinicio de Redis/Chat con AOF, gracia/reconexión de la misma
sesión, fin al perder el owner, Chat/Core caídos sin cortar HLS, rechazos sin mensajes nuevos,
logout en los tres proveedores, leases después del frame y cierre, límites reales de tags y conjuntos
exactos Discovery con categoría+tag AND, normalización y exclusión de gracia/ENDED. Las respuestas
seleccionadas se validan independientemente contra los schemas generados; no se guardan DTO manuales
alternativos. Inventario/estado recuperable no reconstruye mensajes de un volumen perdido: hace falta
su AOF/backup dentro de la retención.
