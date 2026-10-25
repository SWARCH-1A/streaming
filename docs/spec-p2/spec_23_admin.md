# SPEC-23 — Administración, suspensión y retirada

**Prioridad:** P2. **Responsable:** Administración / dueños de dominio. **Estado:** incremento Core/Web implementado; aceptación integrada pendiente.
**RF:** RF-076…079. **RNF:** RNF-022…032, 038, 041…050.

## 1. Contexto y problema

La plataforma debe consultar usuarios/canales y restringir contenido/cuentas sin que ocultar
un enlace deje disponibles los bytes, pistas o replay por otra ruta.

## 2. Definición del componente

Cuentas provisiona y valida el rol administrativo. Core administra estado de cuenta/canal;
Streaming, Chat y VOD ejecutan operaciones sobre sus datos. Gateway enruta y la UI ofrece una
superficie común, sin un servicio Admin con acceso a todas las tablas.

## 3. Historia de usuario

Como administrador autorizado, quiero consultar usuarios/canales, retirar contenido y
suspender/reactivar cuentas conservando las decisiones previas de publicación/moderación.

## 4. Alcance

Listados paginados, cambios de estado con versión/motivo, retirada por dueño, auditoría,
revocación de sesiones, corte de emisión y bloqueo de recursos asociados. El registro público
no permite obtener rol administrativo.

## 5. Requisitos funcionales

- RF-076/077: consultas restringidas, sin hashes ni credenciales.
- RF-078: retirada mediante el dominio dueño, con operación idempotente y trazable.
- RF-079: suspensión revoca sesiones y nuevas acciones, detiene emisión y vuelve indisponible canal/VOD/pistas/replay.
- Reactivación conserva borradores, borrados, retiros administrativos y supresiones; no republica por defecto.

## 6. Criterios de aceptación

- [ ] CA-01: visitante/usuario ordinario no lista ni administra cuentas; un rol provisionado sí.
- [ ] CA-02: cambio con versión vieja da conflicto; repetir idempotency key conserva resultado y auditoría lógica.
- [ ] CA-03: después del commit de suspensión, Core rechaza nueva sesión/contexto autenticado y las sesiones previas quedan revocadas.
- [ ] CA-04: Streaming detiene fuente y nuevas entregas; Chat rechaza mensajes; Gateway/VOD rechazan lectura del contenido restringido.
- [ ] CA-05: manifiesto, segmento, pista y replay por URL directa no evaden restricción; el bucket permanece privado.
- [ ] CA-06: consumidores atrasados no revierten una suspensión/reactivación más nueva y se reconcilian tras reinicio.
- [ ] CA-07: reactivar permite contenido previamente publicado, sin recuperar borradores, retirados ni eliminados.
- [ ] CA-08: si la autoridad de acceso no responde, una nueva entrega protegida no se concede por omisión; UI informa indisponibilidad.

## 7. Diseño técnico y datos

Cuenta mantiene status/accountVersion. Cambiar estado confirma revocación, auditoría e intención
de evento localmente. `account.status.changed.v1` informa a consumidores; los eventos no son
el único control de acceso: contextos Core validan estado actual para nuevas operaciones.

La entrega de medios exige autorización de contenido en su proveedor/Gateway. S3 privado no
entrega enlaces públicos permanentes para contenido revocable. El protocolo de retirada entre
commit, corte de fuente, comprobación de segmentos y notificación al player se cierra en P2-17
con plazo y prueba de fallo. Los bytes ya recibidos no pueden recuperarse; la prueba distingue
cese de nuevas entregas y actualización del player de una revocación retroactiva imposible.

Las retiradas se despachan a Streaming/VOD/Chat según resourceType y conservan su versión.
Gateway no escribe auditoría de dominio en una base compartida ni confirma una operación que
su dueño no aceptó. La auditoría incluye actor, objetivo, motivo, instante y resultado sin secretos.

### Incremento Core/Web disponible

`services/core/src/main/java/streaming/core/accounts/administration` implementa listados y
cambio de estado. V9 añade rol/estado/versión, operaciones idempotentes, auditoría y outbox.
`apps/web/src/modules/administration` ofrece `/admin`; el servidor valida el rol en cada
operación. La lectura de canales usa una interfaz publicada por Canales.

Los listados aceptan limit 1…50 (20 por defecto) y cursor firmado de cinco minutos, ligado
al administrador y tipo de listado. Ordenan por ID; no son snapshots inmutables entre páginas.
El cambio exige cookie, CSRF e Idempotency-Key UUID. Motivo: 1…500 caracteres no blancos;
expectedVersion debe coincidir. Una repetición idéntica conserva resultado/auditoría; otro
comando con la misma clave da 409. El motivo se compara tras recortar espacios exteriores.
Sin cambio real se audita, pero no aumenta versión ni genera evento. Los registros se
conservan sin vencimiento automático en este incremento. Reactivar exige nuevo login.

El outbox se habilita con CORE_EVENTS_TRANSPORT=amqp, usando la configuración RabbitMQ
existente. Mantiene el envelope ante fallo y reintenta tras 30 segundos. No hay un consumidor
integrado de suspensión acreditado todavía. `/api/admin/content-removals` y
`/internal/core/access-context` siguen pendientes; no se anuncia RF-078/079 completo.

La provisión se describe en el [README Core](../../services/core/README.md). La decisión
está en [ADR-017](../adr/ADR-017-administracion-core.md).

## 8. Dependencias y contratos de integración

`p2.admin.*`, `p2.access.context`, `account.status.changed.v1` y disponibilidad VOD.
[Contratos HTTP](../contratos_p2_http.md) y [eventos](../contratos_p2_eventos.md) fijan formas;
SPEC-04/05/14/16 ejecutan corte, bloqueo, acceso y enrutamiento.

## 9. Decisiones y preguntas abiertas

P2-17: plazo de propagación, interrupción de reproducción y recuperación de autoridad caída.
El mecanismo concreto debe ratificarse entre Core/Gateway/Streaming/VOD antes de cerrar RF-079;
la ausencia de decisión no permite anunciar acceso revocable por una URL S3 pública.

## 10. Verificación

Ejecutar rol válido/inválido, concurrencia, evento viejo, consumidor detenido y Core caído.
Suspender mientras hay live/VOD/replay activos; solicitar cada recurso directo y comprobar
rechazo. Reactivar una mezcla de borradores/publicados/retirados/eliminados y comparar estados.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo L transversal. La propagación por eventos sola deja una ventana de acceso; el
contrato de comprobación y los plazos deben verificarse antes de declarar suspensión completa.
