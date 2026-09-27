# Fases futuras y capacidades preservadas

**Fuente de alcance:** [catalogo_requisitos_v2.md](catalogo_requisitos_v2.md), con decisiones en
[decisiones_alcance_p1.md](decisiones_alcance_p1.md). Esta página evita que requisitos fuera de P1 se
pierdan al construir ahora los contratos que los habilitan después.

## Capacidades fuera de P1

| Dominio | Requisitos fuente | Alcance a conservar / habilitadores P1 |
| --- | --- | --- |
| Recuperación y endurecimiento de identidad | RF-004 (`AUTH-RF-04`, `SRC-RF-04`); RNF de seguridad aplicables | Verificación de email, recuperación/restablecimiento, políticas avanzadas de credencial y roles. P1 debe evitar acoplar perfil público a credenciales. |
| Seguimiento | SRC-RF-13…SRC-RF-15 | Seguir/dejar de seguir y consultar canales seguidos. Eventos de inicio pueden habilitar notificaciones futuras, pero P1 no añade seguimiento. |
| Calidad/transcoding | SRC-RF-26…SRC-RF-29 | Variantes, selección manual/automática, información a streamer/espectador. P1 entrega una reproducción funcional; manifest/codec adaptativo no presupone ABR completo. |
| Moderación de chat | SRC-RF-35…SRC-RF-36 | Eliminar mensajes y bloquear usuarios; chat events P1 necesitan ID estable y política futura para suprimir moderados del replay. |
| Suscripciones y premium | SRC-RF-37…SRC-RF-44 | Productos/precios, suscripción, estado, acceso premium y elementos exclusivos. Requiere autorización/entitlement y pago con estados/idempotencia. |
| Watch party | SRC-RF-45…SRC-RF-50 | Sesiones con varios streams, acceso, sincronía, límites y metadatos por canal. El título “juntar varios streams para crear uno solo” necesita distinguir layout multivideo de mezcla/transcodificación audiovisual real. |
| Notificaciones | SRC-RF-51…SRC-RF-54 | Notificar inicio de canal seguido, bandeja, leído y preferencias. Depende de follow y eventos fiables, no de polling indiscriminado. |
| VOD | SRC-RF-12 (catálogo), SRC-RF-55…SRC-RF-61, RF-074…RF-075 (partes VOD de SRC-RF-71…72) | Retención, asociación a canal/origen, catálogo, reproducción, edición/eliminación, duración/metadatos, búsqueda por título y filtro por categoría/etiqueta sobre VOD. Guardar `sessionId` y eventos de chat en P1 prepara origen y Chat Replay; no implica almacenar video. |
| Chat Replay | derivado acordado del chat futuro | Reproducir mensajes sincronizados con VOD. Acordar retención, timezone/offset, edición/borrado de moderación, privacidad y relación de borrado del VOD. |
| Subtítulos | SRC-RF-62…SRC-RF-64 | Son opcionales para fases posteriores; P1 no exige upload, selección, activación ni pistas. Mantener player no acoplado a un único mecanismo de accesibilidad. |
| Administración | SRC-RF-73…SRC-RF-74 y restante catálogo | Consulta/operación administrativa con autorización diferenciada y audit log. No crear privilegio admin implícito en P1. |

Las clasificaciones P2/P3/Futuro definitivas son las del catálogo. Esta lista describe temas y
dependencias, no establece el orden de iteraciones.

## Decisiones de compatibilidad a proteger desde P1

- Identidad publica `userId`/handle estable; Profile conserva snapshots de nombre visible para contenido
  histórico donde corresponda.
- Cada emisión tiene `streamId` y cada ejecución una `sessionId`; al reconectar dentro de 30 s se
  conserva sessionId; una nueva ejecución recibe una nueva sesión.
- Eventos de chat incluyen ID, sesión y posición relativa al stream; un VOD podrá exportar/consultar
  por sesión sin acceso directo a tablas privadas.
- API de Taxonomy utiliza IDs controlados en vez de texto libre para categoría/tag.
- Canales distinguen descripción/banner de perfil/avatar/nombre y de stream/VOD.
- Los contratos públicos permiten búsqueda futura de VOD sin incluirlo en resultados P1.

## Puerta para priorizar una fase

Antes de pasar capacidad a P1 de una fase futura, actualizar catálogo, cruce de RF, casos de aceptación,
datos/ERD, errores, contratos, seguridad/privacidad, métricas y ADR. Confirmar dependencias con módulos
consumidores y la política de migración para datos creados en iteraciones anteriores.
