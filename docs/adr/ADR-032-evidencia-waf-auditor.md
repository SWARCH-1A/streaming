# ADR-032: Bloqueos del WAF como evidencia del Security Logger y Auditor

- Estado: aceptada para integración local
- Fecha: 2026-11-23
- Responsable: Gateway / integración (WAF y relay). La ampliación de Audit y de SPEC-25 la revisa su
  responsable (Persona 4).
- SDD/contratos afectados: SPEC-25 P3, SPEC-16 P2, SPEC-17 P2, SPEC-10 P2; RNF-022, RNF-024, RNF-025,
  RNF-027. Nuevo evento `security.audit.waf.v1` (productor `waf`, suscripción `audit.waf`); la
  consulta `GET /api/admin/audit-events` admite `producer=waf`. Sin cambios en otras operaciones.

## Contexto

El WAF ([ADR-031](ADR-031-waf-modsecurity-crs.md)) bloquea inyección SQL, XSS, *path traversal* y otros
ataques genéricos antes de que lleguen a los servicios, pero esa evidencia solo existía en los logs del
contenedor. Un administrador no tenía forma de ver desde la aplicación que la plataforma estaba siendo
atacada, mientras que las denegaciones de Core y VOD ya se consultan en `/admin/audit`
([ADR-028](ADR-028-security-logger-auditor.md)).

Hay tres restricciones:

- El WAF solo está en la red `edge` y solo alcanza Caddy ([ADR-030](ADR-030-segmentacion-red.md)). No
  puede publicar en el broker sin romper esa frontera.
- Los eventos de auditoría no llevan direcciones, cuerpos, cabeceras ni texto libre (SPEC-25). El
  registro de ModSecurity sí los lleva: IP del cliente y fragmento que coincidió con la regla.
- Un ataque sostenido no debe agotar disco, broker ni base de auditoría, y la caída del broker no debe
  perder evidencia.

## Decisión

1. **Spool como *outbox*.** ModSecurity escribe su registro de auditoría en modo `Concurrent`: un
   archivo por transacción relevante en el volumen `waf-audit`. Ese volumen es lo único que comparten
   el WAF y el relay.
2. **Relay propio (`services/waf-relay`).** Un proceso Go sin puertos, con raíz de solo lectura, sin
   capacidades y con el uid del WAF. Su única red es `waf-messaging`, una bridge interna que solo
   comparte con el broker (como `audit-messaging` con el Auditor): no alcanza a los demás productores.
   Lee cada archivo y publica con confirmación del broker y `mandatory`. Solo borra el archivo después de la confirmación; si el
   broker no está, el archivo espera.
3. **Solo bloqueos.** Se publica únicamente la transacción que ModSecurity interrumpió con 403
   (`is_interrupted`). Las detecciones por debajo del umbral y los 5xx del upstream se descartan.
4. **Evento mínimo.** `actorId` es siempre `null`, porque el WAF nunca verifica una sesión. Además:
   - `action=WAF_BLOCK`, `targetType=ROUTE` y `outcome=DENIED`.
   - `targetId` son los dos primeros segmentos de la ruta, sin consulta y normalizados al alfabeto de
     ID opacos.
   - `reasonCode` es la familia CRS del primer ataque que contribuyó, por ejemplo `SQL_INJECTION`,
     `XSS` o `PATH_TRAVERSAL`.
   - `correlationId` es el `requestId` de la respuesta 403. `eventId` se deriva de él, así que un
     reintento produce el mismo evento y Audit lo guarda una vez.
   - No viajan la IP, las cabeceras, la consulta ni el fragmento que coincidió.
5. **Mismo patrón de atribución que Core/VOD.** El usuario `waf` del broker solo puede publicar la
   routing key `security.audit.waf.v1` y no lee ninguna cola. Audit consume `audit.waf`, con su propia
   DLQ, y rechaza un evento `waf` que tenga actor, resultado `SUCCESS` o una acción de dominio. Ni Core
   ni VOD pueden publicar `WAF_BLOCK`.
6. **Disco acotado.** Si el spool supera `WAF_RELAY_MAX_BACKLOG` archivos (5000 en local), el relay
   descarta los más antiguos y lo registra. El tope se aplica también sin broker, en cada reintento
   de conexión, porque el WAF sigue escribiendo durante una caída. Un archivo ilegible durante más de un minuto también se
   descarta.
7. **Diagnóstico.** El relay registra en su log el `requestId` y los IDs de las reglas que coincidieron
   en cada bloqueo publicado, sin datos. Así se pueden ajustar exclusiones aunque el archivo completo ya
   no exista.
8. **Instalaciones existentes.** `init` añade la credencial `BROKER_WAF_PASSWORD` y regenera las
   definiciones del broker. `up` recrea el broker cuando cambian las definiciones (huella en una
   etiqueta) y amplía el `CHECK` de productores de `audit.events` con el usuario propietario, de forma
   idempotente. Los roles de runtime siguen sin DDL.

## Opciones consideradas

| Opción | Ventajas | Inconvenientes |
| --- | --- | --- |
| Spool + relay en una bridge exclusiva con el broker (elegida) | El WAF sigue solo en `edge`; durable ante caídas del broker; reutiliza Logger, Auditor, ACL y consola. | Un contenedor más; la evidencia es una categoría, no el detalle de la regla. |
| ModSecurity `SecAuditLogType HTTPS` hacia un receptor | Sin volumen compartido. | El WAF tendría que alcanzar otro servicio fuera de Caddy; si el receptor cae, se pierde evidencia. |
| Unir el WAF a `messaging` y publicar desde un módulo | Sin proceso extra. | Rompe ADR-030: la entrada pública alcanzaría el broker y todos los productores. |
| Panel aparte (Grafana + Loki) con los logs del WAF | Gráficas sin tocar Audit. | Otro login fuera de la sesión ADMIN, sin ACL de productor ni retención auditada; duplica la consola. |
| No publicar (solo logs del contenedor) | Nada que cambiar. | El administrador no ve los ataques desde la aplicación. |

## Consecuencias

- **Visibilidad.** En `/admin/audit`, el filtro *Servicio → WAF* lista los ataques bloqueados con
  hora, ruta, tipo de ataque y `requestId`. En local se ven en ~1 s.
- **Privacidad.** Sin la IP del cliente no se puede atribuir un ataque a un origen desde la consola.
  Para eso siguen los logs del WAF, con su retención y acceso de operador.
- **Volumen.** Cada bloqueo es un evento. El límite de 100 peticiones/s del WAF y el tope del spool
  acotan la carga, pero bajo un ataque sostenido la base de auditoría crece; la retención sigue
  pendiente (SPEC-25).
- **Detalle de reglas.** El evento lleva la familia del ataque, no la regla exacta. Para un falso
  positivo, el log del relay y el log de errores del WAF dan las reglas por `requestId`.
- **Redes.** El despliegue pasa a 16 redes. Docker Desktop tiene un número limitado de rangos
  predefinidos: el fixture de `network-segmentation` y el stack local ya no caben a la vez; detener el
  stack (`dev.py down`, conserva volúmenes) antes de esa prueba.
- **Operación.** Tras actualizar, `init` + `build` + `up`. `up` recrea el broker, lo que corta durante
  unos segundos a los productores, que reintentan desde sus *outbox*.

## Verificación

1. `services/waf-relay`: `go test` cubre conversión, filtrado de no-bloqueos, ausencia de IP y datos,
   idempotencia, spool durable ante fallos del broker y tope del backlog, también con el broker
   inalcanzable.
2. `services/audit`: `go test` rechaza eventos `waf` con actor, éxito o acción de dominio, y `WAF_BLOCK`
   desde Core o VOD.
3. `contracts/check_p2.py` valida el esquema y el ejemplo del evento nuevo.
4. `infra/local/test_network_policy.py` y `tests/integration/network-segmentation/run.py` comprueban
   que el relay solo está en `waf-messaging`, que el spool solo lo comparten WAF y relay, y que el relay
   no alcanza servicios, almacenes, Caddy ni el Auditor, ni por DNS ni por IP.
5. `tests/integration/audit-ui/run.py`, en un despliegue desechable: un 403 del WAF aparece una vez en
   la consulta ADMIN como `SQL_INJECTION` sin actor, con el mismo `requestId`, y un usuario común
   recibe 403.
6. Con el stack local: el broker detenido deja los bloqueos en el spool y, al volver, se auditan una
   sola vez; con el broker inalcanzable y un tope de 3, el spool queda en los 3 más recientes.

## Revisión

Revisar si se define retención de auditoría, si el despliegue pasa a varias réplicas del WAF (un spool
por réplica) o si se quiere atribuir ataques a orígenes desde la consola. Esto último exigiría decidir
cómo tratar la IP como dato personal.
