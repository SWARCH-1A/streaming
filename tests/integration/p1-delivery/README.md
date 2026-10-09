# Verificación integrada P1 sobre TLS

Estos ejecutables operan **solo** sobre `infra/p1` y su proyecto Docker `streaming-p1`.
Usar datos desechables. Requieren el entorno Python de `p1-domains/requirements.txt`, FFmpeg
con RTMPS/HTTPS y los pasos de [arranque](../../../infra/p1/README.md). No ejecutar dos suites
contra ese proyecto simultáneamente. Las fuentes permanecen fuera de los contenedores.

```sh
python tests/integration/p1-delivery/verify.py --confirm-disposable
python infra/p1/manage.py up --replicas 2
python tests/integration/p1-delivery/replicas.py --confirm-disposable
# Después del seed/carga, ejecutar fallos fuera de la medición nominal:
python tests/integration/p1-delivery/faults.py --confirm-disposable
# Reemplaza exclusivamente volúmenes ficticios de streaming-p1:
python tests/integration/p1-delivery/restore.py --confirm-disposable
```

`verify.py` prueba rollback/reintento de registro, imágenes, publicación, HLS decodificado,
leases, Chat, aislamiento Core y recuperación tras reinicios. Un reinicio de Streaming termina
la sesión cuyo reloj monotónico perdió; el encoder se reconecta con la misma configuración y
clave y obtiene una nueva sesión. MediaMTX puede recuperar la misma sesión durante su grace.
`replicas.py` dirige peticiones a cada réplica física mediante rutas temporales del proxy,
prueba estado/cuotas compartidos y restaura el proxy en `finally`. No usar esas rutas en producto.

`faults.py` detiene Chat, Core y Streaming por separado, verifica HLS independiente de Chat/Core,
rechazo de nuevas escrituras Chat y reconexión con fencing. `restore.py` detiene escritores, captura
tres dumps SQL, AOF Redis y objetos filesystem en un directorio privado, reemplaza solo los volúmenes
del fixture y restaura IDs, historial con ACK e imágenes byte a byte. Conserva el backup privado
para recuperación si una comprobación falla; no publicarlo. Reinicio/restore no extiende la retención
de mensajes ni reconstruye un clock monotónico perdido. No es un procedimiento de producción.

## Carga nominal y diagnóstico

Instalar Firefox del runner y NSS (`libnss3-tools` en Linux; `brew install nss` en macOS):

```sh
pnpm --dir apps/web exec playwright install --with-deps firefox
python tests/integration/p1-delivery/load.py --confirm-disposable --diagnostic
```

El diagnóstico usa cinco players y 30 segundos medidos; **nunca** acredita CA-06.
Para el perfil completo, detener las fuentes, restablecer **solo los datos desechables propios**
y esperar readiness. Reset conserva CA, contraseñas e imágenes compiladas:

```sh
python infra/p1/manage.py reset --confirm-disposable
python infra/p1/manage.py up --replicas 2
curl --fail --cacert infra/p1/.state/ca.crt https://localhost:3443/api/taxonomy
python tests/integration/p1-delivery/load.py --confirm-disposable
```

El seed crea 100 cuentas/perfiles/canales ficticios en Core; la primera cuenta se registra por API,
y las otras usan SQL exclusivo del fixture. Nunca fuerza LIVE ni escribe SQL de Streaming.
El runner comprueba siete categorías, ocho tags y exactamente 100 canales para el perfil completo.
El runner codifica previamente un vídeo ficticio 720p30 H.264/AAC de 30 s con bitrate acotado y verifica
sus codecs/dimensiones/fps con ffprobe. Durante la carga cinco procesos RTMPS independientes lo repiten
en tiempo real con stream copy; la codificación no compite por CPU con los players medidos.
Cinco fuentes reales preceden el calentamiento de 60 s, con cinco players,
10 req/s de API y 20 envíos Chat/s. Tras un segundo separado de drenaje, los 600 s medidos incluyen la rampa de 100 players,
uno por fuente cada 3 s durante los primeros 60 s. Login rota las cuentas con credenciales correctas;
CSRF se obtiene antes de medir. Lease, heartbeat cada 10 s y HLS se cuentan por separado.

El navegador confía únicamente en la CA del fixture mediante un perfil NSS dedicado; no cambia
la confianza del sistema ni omite TLS. Firefox provisto por Playwright no acredita la puerta de
versiones estables de Chrome/Firefox. Cada página serializa la entrega de respuestas HLS a
15 Mbit/s más 20 ms de retardo, con pérdida configurada cero. Es **shaping de respuestas HTTP**
sobre loopback; no acredita por sí solo un enlace físico de 250 Mbit/s ni emula pérdida de paquetes.
El informe registra bytes/egreso observado, mecanismo, versión, CPU/RAM Docker y muestras por
contenedor de CPU, memoria, NetIO y BlockIO. No interpretar bytes de segmentos compartidos como
capacidad medida de una NIC externa.

`infra/p1/.state/load-report.json` conserva submuestras/p95 de cada operación, fallos, atrasos del
generador, ACK/entrega, duplicados/huecos, primer frame, continuidad y borrado de leases,
progreso de vídeo y tiempos de contexto/intento de persistencia. Los tiempos de Streaming son
latencia del servidor; Core aplica además su deadline cliente de 200 ms. No se afirma tiempo
hasta commit físico Redis a partir del tiempo de intento. Los fallos iniciales del calentamiento se conservan aparte; los últimos diez segundos deben estar sanos
antes de iniciar la medición. Un run medido con respuestas no exitosas,
timeouts, fallos de vídeo, generador atrasado o métricas incompletas falla aunque sus p95 sean bajos.

Los informes, logs de FFmpeg y del navegador permanecen privados en `.state`; pueden contener
claves en URLs o configuración. No adjuntar logs sin depurar ni versionar estos archivos. El runner
cierra fuentes/sockets/leases al terminar; conserva las bases para inspección. No ejecutar carga
junto a compilaciones u otras pruebas. Registrar en Plane el resultado, commit/huella de fuentes,
condiciones y limitaciones; no convertir un diagnóstico o una prueba de reinicio en aceptación total.
