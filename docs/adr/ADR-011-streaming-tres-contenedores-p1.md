# ADR-011: Streaming y Media en tres contenedores para P1

- Estado: aceptada
- Fecha: 2026-10-08
- Responsable: Streaming e Integración
- SPEC/contratos afectados: SPEC-04, SPEC-09…SPEC-13
- Sustituye parcialmente ADR-005: despliegue independiente del adaptador Media; conserva las demás fronteras y contratos.

## Contexto

P1 necesita una instalación pequeña y reproducible. El adaptador técnico y el control de
Streaming ya pertenecen al mismo paquete Rust. Operarlos como procesos separados añade
arranque, configuración y supervisión sin una necesidad de escalado independiente demostrada.
MediaMTX sigue siendo el motor RTMP/LL-HLS y Core/Chat conservan sus procesos independientes.

## Decisión

El stack propio de SPEC-04 usa Docker Compose con exactamente tres contenedores: PostgreSQL,
MediaMTX y Streaming. `streaming-service` ejecuta el control y el adaptador como tareas del
mismo runtime Tokio, con parada coordinada y salida no exitosa ante fallo de cualquiera.
`media-adapter` se conserva únicamente como CLI de migraciones y operación de callbacks.

Las dos bases, roles, repositorios y migraciones se conservan separados. PostgreSQL prepara
idempotentemente el rol/base Media antes de declarar readiness, también al reutilizar un
volumen existente; no hay contenedor de inicialización. No se borran ni trasladan datos.

Los contratos privados de autorización, callbacks y consulta se conservan mediante HTTP
loopback dentro del contenedor. Esa frontera técnica permite una extracción posterior;
no autoriza lecturas cruzadas de tablas. Los saltos entre contenedores mantienen TLS privado
en producción. Loopback interno usa HTTP y credenciales, sin publicar sus listeners.

La API pública mantiene 8080; el contrato interno Streaming usa 8091; la autorización Media
usa 8090; HLS público usa 8888; MediaMTX publica RTMP 1935. Readiness del contenedor exige
salud del control y del adaptador. MediaMTX arranca tras iniciar Streaming, sin esperar
readiness, porque esta comprueba la Control API del motor.

## Opciones consideradas

| Opción | Evaluación |
| --- | --- |
| Cuatro procesos/contenedores permanentes y job de inicialización | Mayor aislamiento, con más piezas operativas; se aplaza. |
| Adaptador dentro del runtime Streaming | Elegida: tres contenedores y conservación de persistencia/contratos. |
| Dos ejecutables en un contenedor con supervisor | Reduce contenedores, pero conserva dos runtimes y añade supervisión de procesos. |
| Eliminar la persistencia técnica o los callbacks durables | Rompe recuperación, deduplicación y operación; descartada. |

## Consecuencias

Streaming y el adaptador comparten release, recursos, fallos y reinicios. Reiniciar Streaming
interrumpe también HLS y la autorización Media; no se promete aislamiento entre ambos módulos.
Se conserva la recuperación existente: sesiones sin anchors válidos terminan sin nueva gracia,
y los callbacks pendientes se reconcilian/reintentan desde SQL. No se afirma una mejora de
rendimiento ni un ahorro de RAM sin medirlos. Las restricciones académicas siguen apoyándose
en Core/Streaming/Chat, SQL/NoSQL y sus lenguajes/conectores reales.

Para actualizar una instalación local anterior: detener su Compose sin `-v`, reconstruir
y arrancar con `--remove-orphans`; se conserva el volumen PostgreSQL. El wrapper PostgreSQL
reconcilia la contraseña Media con la configuración actual. El rollback usa el Compose y
los ejecutables anteriores sobre las mismas bases, sin migración destructiva.

## Verificación

Verificar configuración de tres servicios, arranque con volumen vacío, migraciones de ambas
bases, endpoints de control/Media, RTMP/HLS real, parada/fallo coordinados y reinicio con
volumen persistente. Comprobar actualización de la contraseña Media en un volumen existente.
La aceptación integrada y el perfil de carga siguen sujetos a SPEC-13.

## Revisión

Reevaluar en el próximo prototipo si aparecen necesidades medidas de escala, recursos,
release o aislamiento del adaptador. Streaming e Integración coordinan la extracción usando
las bases y contratos conservados; el próximo prototipo no añade procesos automáticamente.
