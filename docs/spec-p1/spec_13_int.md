# SPEC-13 Despliegue integrado y verificación E2E P1

- **Módulo:** integration
- **Padre:** SPEC-09
- **Prioridad:** P1

## 1. Contexto y problema

Define el ensamblaje reproducible de los procesos P1, su configuración, salud, reinicio y pruebas integradas que demuestran restricciones distribuidas y recorrido vertical utilizable. Hijo de SPEC-09.

## 2. Definición del componente

SPEC-13 define el perfil local de contenedores que deben integrar los módulos, junto con sus runtimes, puertos, comandos y requisitos de health/reinicio. La configuración real debe reflejar únicamente componentes que estén implementados.

## 3. Historia de usuario

Como integrante del equipo, quiero arrancar y verificar desde checkout limpio todo el prototipo y reiniciar un servicio por separado, para demostrar arquitectura y depurar integración.

## 4. Alcance

### Dentro de P1

- Topología de procesos, imágenes, puertos internos, redes, volúmenes, health checks y configuración por entorno.

- SQL y NoSQL con uso real y justificación ADR; al menos dos procesos de lógica reiniciables por separado; tres lenguajes generales y dos conectores HTTP demostrables.

- Instrucciones reproducibles de install/seed/start/stop/health sin secretos desde checkout limpio de la organización.

- Flujo E2E: registro/login, canal/profile, RTMP→HLS, metadata/taxonomía, chat anónimo read/autenticado send, viewer count, discovery y finalización/grace.

- Perfil P1 reproducible: warm-up de 60 s no medido; luego 10 min medidos. Mantener 5 fuentes RTMP 720p30 H.264/AAC de ≤2.5 Mbit/s y subir linealmente 100 reproductores (20 por fuente) durante los primeros 60 s de medición, un reproductor nuevo por fuente cada 3 s. Medir el tiempo a primer frame de cada uno; al primer frame cada player crea un lease y envía heartbeat cada 10 s.

- Carga de API de aplicación sostenida: **10 solicitudes HTTP/s totales** distribuidas exactamente como 2 login correcto, 3 consultas Discovery streams, 1 búsqueda de canal, 1 lectura de Taxonomy, 1 lectura de canal, 1 lectura de perfil y 1 lectura de estado de stream por segundo. Login rota 100 cuentas semilla y solo usa credenciales correctas. Las 100 creaciones iniciales de lease se reparten uniformemente en la rampa de 60 s; 100 heartbeats cada 10 s generan **10 solicitudes/s adicionales en promedio** tras la rampa. HLS playlist/segment requests, leases y heartbeat se reportan separados de la tasa de API de aplicación.

- Chat mantiene 20 mensajes/s agregados en cinco salas por 25 cuentas semilla; cada cuenta envía uno cada 1.25 s (0.8/s) con tiempos escalonados para no exceder el límite móvil de 1000 ms. Cada envío válido obtiene un contexto Core (hasta 20 llamadas Chat→Core/s y 20 Core→Streaming/s adicionales); reportar p95/error/timeout y presupuesto de autorización. Las pruebas de falla son runs separados: Core caído rechaza nuevas escrituras con CORE_UNAVAILABLE sin persistir; Streaming caído devuelve STREAMING_UNAVAILABLE sin persistir; Chat caído conserva HLS; sus fallos inyectados son resultados esperados de esos runs, no se mezclan con la tasa de error del run nominal. Mantener 100 canales en semilla (5 LIVE), títulos con/sin coincidencias, siete categorías y ocho tags. Perfil de red por reproductor: 15 Mbit/s down, RTT ≤50 ms, pérdida configurada 0%; el egreso HLS objetivo agregado alcanza 250 Mbit/s si cada reproducción usa 2.5 Mbit/s. Registrar host, CPU/RAM, NIC/egreso y versiones de navegador.

- Logs/métricas con correlation ID, salud de componentes y evidencia de arranque/reinicio/aislamiento.

### Fuera de P1

- HA, autoscaling global, observabilidad empresarial, CI/CD completo y producción multi-región.

### Supuestos acordados

- Docker Compose es candidato, no decisión; herramienta final requiere ADR y comandos reproducibles.

- REST, GraphQL sobre HTTP/JSON y WebSocket HTTP Upgrade quedan definidos como tres patrones HTTP demostrables. El perfil incluye ejercicios de REST/GraphQL/WebSocket; RTMP/HLS son transportes de medios y no sustituyen conectores HTTP. La aceptación del RNF académico de “dos tipos de conectores basados en HTTP” se marca pendiente hasta confirmar que la guía/docente cuenta al menos dos de esos patrones como distintos; no afirmar cumplimiento sin esa evidencia.

## 5. Requisitos de despliegue y demostración

- Redes privadas; exponer solo reverse proxy/listeners requeridos; bases no accesibles desde navegador.

- Health distinguible; falta de Chat no marca player unhealthy; límites de restart y readiness documentados.

- Datos seed se recrean/restablecen con pasos declarados; no se requieren credenciales personales.

- Separar config no sensible y secretos; valores demo falsos no llegan a logs/commits.

- Capturar evidencia de procesos reiniciables, SQL, NoSQL, conectores, lenguajes, contenedores y runbook.

## 6. Criterios de aceptación

- **CA-01:** integrante clona checkout limpio y ejecuta comandos documentados hasta health operativo sin pasos manuales omitidos.

- **CA-02:** reiniciar un proceso lógico no detiene los demás; al volver se reintenta/reconcilia o se muestra fallo recuperable.

- **CA-03:** SQL y NoSQL cumplen usos concretos; ADR enlaza cada almacén a entidad/acceso real.

- **CA-04:** tres lenguajes de propósito general aparecen en procesos/artefactos reales; no cuentan markup/config/SQL.

- **CA-05:** REST, GraphQL y WebSocket HTTP Upgrade se prueban cada uno con petición/mensaje nominal, error y timeout. No contar RTMP/HLS como conector HTTP; registrar en el informe docente/evaluador qué dos patrones reconoce para el RNF y no marcarlo cumplido sin esa confirmación.

- **CA-06:** el perfil nominal completo de carga arriba se ejecuta durante 10 min con dependencias sanas. RNF-011 mide 10 req/s totales de API, incluyendo 2 logins correctos/s; cada endpoint informa su submuestra y p95 ≤2 s. RNF-012 mide las 100 solicitudes de inicio de player de la rampa y **todas** llegan al primer frame ≤5 s. RNF-013 mide solo mensajes aceptados y exige p95 <1 s hasta entrega (criterio estricto RF-033, también cumple RNF-013 ≤1 s). Cada envío nuevo Chat implica contexto Core y estado/timeline actual Streaming: hasta20 solicitudes/s en cada hop. Contexto<=400ms incluyendo Core→Streaming<=200ms; commit<=500ms desde iniciar contexto. Sin lookup Profile remoto ni timeline replicado. Registrar p95/error/timeout y tiempo previo al commit aparte. Cualquier timeout, 5xx, 429 inesperado o respuesta no exitosa de una operación válida reprueba el run nominal; los p95 se calculan sobre respuestas 2xx y no pueden esconder fallos, que además se informan aparte. Ejecutar además los runs de falla descritos arriba y validar su comportamiento esperado sin confundirlos con la medición nominal. Reportar muestras, status/error rate, pérdida/duplicación, host y red.

- **CA-07:** E2E valida registro/canal, browse, reproducción, sala, grace/fin, viewer expiry y estado público.

- **CA-08:** fallo Chat o del endpoint de consulta Discovery no corta playback activo; health/log correlaciona componente sin secretos.

- **CA-09:** scripts de seed/cleanup son idempotentes en desarrollo y no borran datos ajenos.

- **CA-10:** recorrido integrado funciona en las versiones estables disponibles de Chrome y Firefox; el informe registra fecha, versiones y pasos/error.

- **CA-11:** escalar en forma aislada al menos una réplica de Chat y una de Core mantiene paths/schemas y servicio a consumidores sin modificar frontend ni módulo no afectado; se documenta qué estado requiere afinidad/compartición.

- **CA-12:** crear cuenta, canal, stream metadata y eventos persistentes; reiniciar Core, Streaming, Chat y Media por separado y confirmar mismos IDs/datos sin creación manual. Comprobar Channel desde datos Core y batch Streaming, Discovery desde SQL Core con proyección pública y reconstrucción mediante corte consistente/watermark e inbox durable durante rebuild. Chat recupera salas conocidas mediante snapshot/outbox; pérdida total requiere backup/inventario o enumeración consistente, no lookups puntuales.

- **CA-13:** revisión de arquitectura confirma que añadir VOD, notificaciones, watch party o premium usa contratos/nuevos componentes o extensiones acotadas, sin cambios sustanciales en componentes no relacionados.
- **CA-14:** E2E fuerza fallo entre escrituras de registro y rollback total; después pierde respuesta tras commit Core y repite misma clave sin duplicar cuenta/perfil/canal. No PENDING nuevo ni 404 temporal por activación. Callback media repetido tras perder ACK produce una sola transición, y un cambio de viewers aparece en proyección SQL Discovery en<=5s con freshness correcta; duplicados/desorden/ENDED/rebuild concurrente no regresan datos.
- **CA-15:** con reloj controlable, callbacks sin respuesta reciben retry al calendario fijado, alertan una vez al alcanzar 30 s y detienen el envío automático al cumplir 15 min dejando registro durable en dead-letter. El redrive manual reutiliza eventId/payload y abre una nueva ventana de 15 min; una respuesta 410 resuelve el evento como obsoleto sin reintento.

## 7. Diseño técnico y datos

- Diagrama de despliegue coordinado con SPEC-09; manifest por servicio, variable/puerto y condición de readiness explícitos.

- SQL/NoSQL/seed según ADR de dueños; documentar backup o cleanup pertinente a demo y límites del estado guardado.

- Health no consulta dependencias sin timeout; logs incluyen request/event ID y excluyen passwords, tokens, stream key y datos privados.

- Runner de carga usa cinco fuentes controladas y rampa de 100 clientes; mantiene métricas separadas de egreso/bitrate HLS, 10 solicitudes/s de API, 10 heartbeats/s adicionales después de rampa, creación de leases, tráfico WebSocket y mensajes. Reporta resultados por operación, muestra, p95/máximo según RNF, fallos, pérdidas/duplicados y configuración de CPU/RAM/NIC/red.

## 8. Dependencias y contratos de integración

- Consume schemas SPEC-10, secuencias SPEC-11 y rutas web/proxy SPEC-12.

- Cada unidad desplegable aporta imagen/comando/variables/puerto/health/seed; Streaming Rust tiene SQL privado con pool y backup propio; los módulos Core comparten runtime y release. Integration coordina el manifiesto, sin servicio orquestador propio.

- Compose/CI pueden ejecutar contract/smoke/E2E; evidencia se limita al prototipo del curso, no producción.

## 9. Decisiones y preguntas abiertas

**Acordado:** restricciones globales, perfil de carga, reinicio independiente, contenedores, compatibilidad Chrome/Firefox, reproducibilidad y monorepo modular. **No bloqueante:** herramienta de despliegue, seed, runtime y herramienta de carga se seleccionan por ADR; el resultado de carga debe respetar el perfil y umbrales aquí fijados.

## 10. Verificación

- Runbook ejecutado desde checkout limpio por otro integrante.

- Matriz restricción de curso→proceso/conector/base/lenguaje/container con evidencia de demo y ADR.

- Smoke/E2E nominal, caída independiente, recovery y versión de entorno/carga registrada.

- Carga P1 por diez minutos según CA-06; tasa total y submuestras por endpoint, p95 de API/chat, 100 máximos de inicio HLS y cada fallo se informan y aplican la regla de reprobación. El runbook incluye CA-10…13 de compatibilidad, escalado, recuperación y evolución.

- Inspección de logs, secretos y health/restart.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** L. **Riesgos:** irreproducibilidad, carga no controlada, falsos health positivos, secretos, NoSQL sin uso y confusión entre HLS/RTMP y conectores HTTP. **Consecuencia:** alta disponibilidad y seguridad operacional productiva se aplazan.
