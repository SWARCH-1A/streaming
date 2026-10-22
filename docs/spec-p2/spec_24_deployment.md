# SPEC-24 — Despliegue, datos y operación P2

**Prioridad:** P2. **Responsable:** Integración / responsables de componentes. **Estado:** diseño para desarrollo.
**RF:** Todos los recorridos incluidos en P2. **RNF:** RNF-001…010, 015…024, 025, 041…051.

## 1. Contexto y problema

La entrega debe poder construirse y operarse desde un checkout limpio con componentes,
almacenamiento persistente y evidencia de los mínimos académicos y recorridos integrados.

## 2. Definición del componente

Ensamblaje de Gateway, SSR, Core, Streaming/Media, Chat, VOD, Notificaciones, broker y almacenes.
Móvil se compila/instala en su plataforma. S3, SMTP y push son dependencias externas configurables.

## 3. Historia de usuario

Como integrante/evaluador, quiero desplegar el sistema, identificar sus procesos y comprobar
qué se recupera o deja de estar disponible al detener un componente.

## 4. Alcance

Contenedores de servidor, redes, dominio HTTPS, configuración, secretos, volúmenes, health,
readiness, backups, recuperación, comandos y artefacto académico. Perfil local sencillo con
S3 compatible; no se fija producto local ni tamaño de disco en esta SPEC.

## 5. Requisitos funcionales

- Desplegar cada unidad de servidor con ciclo de vida independiente y rutas internas privadas.
- Persistir datos/colas/objetos conforme a sus garantías; no usar almacenamiento efímero como archivo final.
- Acreditar dos presentaciones, seis componentes lógicos, comunicación/orquestación, cuatro datos con relacional/NoSQL, asincronía y cinco lenguajes.
- Publicar instrucciones completas de build/run/stop/health/backup/restore y provisión externa.

## 6. Criterios de aceptación

- [ ] CA-01: desde checkout limpio, comandos documentados construyen y levantan servidores y permiten instalar Android.
- [ ] CA-02: dominio HTTPS sirve SSR/API/WS/HLS; rutas y credenciales internas no están expuestas.
- [ ] CA-03: S3 externo conserva todas las calidades; el perfil local usa endpoint configurable compatible sin forzar proveedor de producción.
- [ ] CA-04: reiniciar servicios/broker con volúmenes conserva cuentas, VOD, mensajes confirmados, bandeja e intenciones pendientes.
- [ ] CA-05: backup/restore demuestra datos y referencias de objetos consistentes; se distingue volumen conservado de recuperación tras pérdida.
- [ ] CA-06: readiness detecta dependencias necesarias y health distingue proceso vivo de capacidad de atender, sin filtrar secretos.
- [ ] CA-07: inventario acredita mínimos con artefactos ejecutados y criterio docente; no cuenta módulos Core como procesos o el broker como BD de negocio.
- [ ] CA-08: logs/métricas permiten seguir requestId/eventId/deliveryId, cola/DLQ y fallos parciales sin tokens ni payloads privados.
- [ ] CA-09: los recorridos de SPEC-15 funcionan con dependencias detenidas y después de recuperarlas.

## 7. Diseño técnico y datos

Configuración por unidad: dirección de escucha, URL interna de dependencias, credenciales de
servicio por permiso, broker y consumidor, almacén propio, endpoint/bucket/prefijo S3 cuando
aplique, origen Web, URLs públicas, SMTP y adaptadores push. Los nombres concretos se documentan
con su implementación; un placeholder no acredita un comando ejecutable.

Secretos fuera de Git, logs y assets SSR/móvil. Contenedores con redes/listeners públicos mínimos;
S3 y bases tienen credenciales por responsabilidad. Política de almacenamiento de Chat y motores
VOD/Notificaciones deben soportar recuperación y el inventario de cuatro componentes de datos.
Bases lógicas dentro de un solo motor no se cuentan automáticamente como componentes distintos.

El dominio usa HTTPS. Local conserva autenticación y aislamiento con un arranque sencillo;
los comandos reales existentes siguen siendo P1 hasta publicar los de P2. Se documenta qué
servicios requieren credenciales/provisión externa y qué pruebas se realizaron con S3 local.

## 8. Dependencias y contratos de integración

Cada servicio entrega build, comando, configuración de ejemplo sin secretos, health y volumen.
SPEC-17 provee broker/topología/recuperación; SPEC-18, runtime SSR; SPEC-19, build móvil;
SPEC-14/20, almacenes y proveedores. [Arquitectura](../arquitectura_c4_cnc_despliegue.md) mantiene
C&C, despliegue, capas y descomposición alineados con ese inventario.

## 9. Decisiones y preguntas abiertas

P2-18: clasificación Gateway/seis lógica y cuatro datos; P2-07/09/10/12: productos y motores;
P2-13: push real. El perfil debe cerrarse con esos ADR antes de acreditar mínimos. No se inventa
un sexto dominio solo para completar un diagrama.

## 10. Verificación

Ejecutar arranque limpio, reinicio individual, broker/consumidor detenido y restauración.
Registrar inventario de lenguajes por fuente/artefacto, de procesos por contenedor/runtime y de
datos por componente real. El artefacto Markdown/PDF incluye equipo, nombre, logo, descripción,
cuatro vistas, elementos/conectores/estilos y comandos probados, con cierre de código el 30 de
octubre, entrega 2 de noviembre y presentación 3 de noviembre.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L transversal. La dependencia de S3/SMTP/push y las decisiones de motores deben
resolverse pronto; la evidencia debe separar configuración local, despliegue externo e iOS real.
