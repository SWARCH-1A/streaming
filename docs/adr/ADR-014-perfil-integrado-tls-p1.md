# ADR-014: Perfil integrado P1 con TLS y estado persistente

- Estado: aceptada
- Fecha: 2026-10-08
- Responsable: integración
- SPEC afectadas: SPEC-09…SPEC-13; RNF-025, RNF-043…050

## Contexto

Los fixtures HTTP prueban contratos y recorridos reales, pero no acreditan transporte seguro ni
entrega persistente. El stack propio Streaming debe conservar tres contenedores y no se añade un
servicio de integración. El perfil académico necesita ejecutarse sin credenciales cloud personales.

## Decisión

El Compose raíz coordina nueve contenedores: core/core-db, chat/chat-db (Redis),
live/live-db/media-server y web/proxy separados. Web se compila dentro de Docker; el proxy
verifica también su upstream HTTPS. El proyecto local conserva CA, secretos y volúmenes propios.
Los fixtures de aceptación de infra/p1 conservan Web/proxy juntos: ocho contenedores con una
réplica Core/Chat y diez con dos. Solo HTTPS Web y RTMPS de ingest tienen puertos host, en loopback por defecto. Cada runtime
termina TLS en sus listeners de red; las llamadas privadas conservan credenciales por consumidor.
Caddy verifica certificados de upstream, además de servir HTTPS al navegador. MediaMTX valida la
autorización HTTPS por fingerprint; control y HLS privados usan TLS. PostgreSQL verifica TLS por
hostname y Redis usa rediss. Los clientes confían en una CA explícita; no se admite skip-verify.

Los perfiles locales seleccionan HLS fMP4 con segmentos de un segundo, conservando siete segmentos
en la ventana y el límite de cinco segundos a primer frame. Evita las peticiones adicionales de
partes LL-HLS al reproducir cien clientes. El stack de desarrollo mantiene su variante Low-Latency
HLS; seleccionar la variante segmentada no acredita por sí solo rendimiento ni baja latencia.

Streaming usa axum-server/rustls para conservar un runtime y supervisar sus listeners, shutdown y
adaptador. Reqwest admite una CA adicional configurable. Chat usa TLS nativo Go para ambos
listeners. Core usa TLS Tomcat en ambos conectores. La CA académica se genera por instalación,
con SAN por servicio; no se instala en el almacén del sistema. Claves y configuración quedan en
un directorio ignorado, con permisos limitados. Para una exposición pública se requieren certificados
confiables y operación propia; este perfil no afirma preparación productiva.

Filesystem es una selección explícita permitida por SPEC-13 CA-01/11/12: los volúmenes de imágenes
se comparten entre réplicas Core. S3 sigue siendo el proveedor predeterminado del servicio y puede
configurarse con un bucket privado externo y el AWS SDK. Ejecutar filesystem no acredita S3.
Discovery conserva su cuota global en PostgreSQL Core, con token bucket/ventana móvil atómicos por
HMAC de IP; replicar Core no multiplica el límite. Esto reemplaza la elección de limitador en memoria
de ADR-008. El reloj SQL es autoritativo; un retroceso no concede tokens. SQL inaccesible produce
error GraphQL 503, sin fallback a una cuota independiente.
PostgreSQL y AOF Redis persisten; reiniciar no equivale a eliminar volúmenes. Snapshot Streaming
recupera inventario/lifecycle Chat, nunca mensajes perdidos. Backup/AOF solo recupera mensajes
conservados dentro de la retención.

La carga y los fallos se ejecutan por separado y reportan sus errores. Un run reducido es diagnóstico,
no aceptación de 5 fuentes/100 players/20 mensajes por segundo durante 10 minutos. Las revisiones
manuales asistivas y la confirmación académica RNF-006 siguen siendo puertas independientes.

## Opciones consideradas

- Terminar TLS solo en Caddy: deja cookies y secretos entre contenedores en HTTP; descartado.
- Añadir sidecars TLS por servicio: contradice el stack Streaming de tres contenedores; descartado.
- CA efímera explícita y TLS nativo: seleccionados para el perfil académico reproducible.
- S3 privado externo: admitido y recomendado para infraestructura compartida; requiere provisión.
- Proveedor local S3 adicional obligatorio: añade operación y consumo al prototipo; no se exige
  para la alternativa filesystem expresamente admitida.

## Consecuencias y revisión

Se deben renovar certificados antes de expirar y reiniciar runtimes para cargarlos. Compartir CA no
reemplaza autenticación de servicios. HTTP dev conserva un opt-in separado para pruebas. La
configuración y el runbook deben identificar provider, versiones, commit y condiciones de red.
Revisar esta decisión al desplegar fuera del host académico, adoptar HA o cambiar almacenamiento.

Referencias: [axum-server TLS](https://docs.rs/axum-server/0.8.0/axum_server/tls_rustls/),
[Reqwest CA](https://docs.rs/reqwest/0.13.5/reqwest/struct.ClientBuilder.html#method.tls_certs_merge),
[MediaMTX configuración](https://mediamtx.org/docs/references/configuration-file),
[Caddy TLS upstream](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#the-http-transport),
[Redis TLS](https://redis.io/docs/latest/operate/oss_and_stack/management/security/encryption/).
