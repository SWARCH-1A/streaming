# Integración de dominios P1

SPEC-10/11: Core actual (Java/PostgreSQL), Streaming y adaptador actual (Rust/PostgreSQL),
MediaMTX real, Chat actual (Go) y Redis/AOF. El stack propio de Streaming sigue teniendo tres
contenedores; la composición completa de esta prueba tiene siete. El fixture selecciona filesystem
explícitamente: sin --web no incluye Web/proxy. Ninguna variante de este fixture acredita S3/TLS ni el perfil de carga SPEC-13.

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

## Web integrada (SPEC-12)

Con Node >=22.12/pnpm 11.17, desde apps/web ejecutar install --frozen-lockfile, check y
`pnpm exec playwright install chromium`. Después, desde la raíz:

```sh
pnpm --dir apps/web build
python tests/integration/p1-domains/run.py --web
```

Añade Caddy como octavo contenedor del sistema completo; Streaming mantiene sus tres. Requiere
localhost:3000 libre y el build actual dist. El runner detecta la IP de Caddy, configura la confianza
exacta Core y ejecuta la suite real de escritorio/móvil. Esta cubre registro/login/refresh/logout,
perfil/avatar/canal/banner, catálogo desde API, configuración/metadata/rotación/stop, RTMP/HLS con
frame en navegador y lease, historial/WS con ACK perdido y reintento del mismo ID, caída/reinicio
Chat sin cortar video, Discovery/AND, UNKNOWN y rutas profundas/errores sin fallback HTML, Axe y
teclado. Los cambios de fixtures de catálogo ocurren únicamente en su PostgreSQL desechable para
verificar opciones nuevas sin rebuild y tombstones; no representan una API de administración P1.
La suite fuera de este runner se omite explícitamente; un skip no acredita integración. No habilitar
trazas de navegador que guarden passwords/keys; stdout tampoco debe imprimir DTO owner.

La biblioteca visual /design-system usa ejemplos explícitos, mientras las rutas funcionales usan
proveedores reales. Automatización no acredita por sí sola lector de pantalla ni conformidad WCAG
global. Este perfil filesystem/HTTP sigue sin acreditar S3/TLS/carga de SPEC-13.
