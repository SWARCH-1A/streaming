# Fases futuras y capacidades preservadas

**Alcance:** requisitos y capacidades fuera de P1 definidos en el catálogo de esta versión. Esta página
resume las dependencias que deben conservarse al construir los contratos actuales.

## Capacidades fuera de P1

| Dominio | Requisitos | Alcance a conservar / habilitadores P1 |
| --- | --- | --- |
| Recuperación y endurecimiento de identidad | RF-004; RNF-025…RNF-032 | Verificación de email, recuperación/restablecimiento, políticas avanzadas de credencial y roles. P1 debe evitar acoplar perfil público a credenciales. |
| Seguimiento | RF-014…RF-016 | Seguir/dejar de seguir y consultar canales seguidos. Eventos de inicio pueden habilitar notificaciones futuras, pero P1 no añade seguimiento. |
| Calidad/transcoding | RF-027…RF-030 | Variantes, selección manual/automática, información a streamer/espectador. P1 entrega una reproducción funcional; manifest/codec adaptativo no presupone ABR completo. |
| Moderación de chat | RF-036…RF-037 | Eliminar mensajes y bloquear usuarios; los eventos de Chat P1 necesitan ID estable y política futura para suprimir mensajes moderados del replay. |
| Suscripciones y premium | RF-038…RF-045 | Productos/precios, suscripción, estado, acceso premium y elementos exclusivos. Requiere autorización/entitlement y pago con estados/idempotencia. |
| Watch party | RF-046…RF-051 | Sesiones con varios streams, acceso, sincronía, límites y metadatos por canal. El diseño debe distinguir un layout multivideo de una mezcla/transcodificación audiovisual real. |
| Notificaciones | RF-052…RF-055 | Notificar inicio de canal seguido, bandeja, leído y preferencias. Depende de seguimiento y eventos fiables, no de polling indiscriminado. |
| VOD | RF-013, RF-056…RF-062, RF-074…RF-075 | Retención, asociación a canal/origen, catálogo, reproducción, edición/eliminación, duración/metadatos, búsqueda por título y filtro por categoría/etiqueta sobre VOD. Guardar `sessionId` y eventos de chat en P1 prepara origen y Chat Replay; no implica almacenar video. |
| Chat Replay | derivado acordado del chat futuro | Reproducir mensajes sincronizados con VOD. Acordar retención, timezone/offset, edición/borrado de moderación, privacidad y relación de borrado del VOD. |
| Subtítulos | RF-063…RF-065 | Son opcionales para fases posteriores; P1 no exige carga, selección, activación ni pistas. Mantener player no acoplado a un único mecanismo de accesibilidad. |
| Administración | RF-076…RF-079 | Consulta/operación administrativa con autorización diferenciada y audit log. No crear privilegio admin implícito en P1. |

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

Antes de incluir una capacidad futura en P1, actualizar sus RF, casos de aceptación, datos/ERD, errores,
contratos, seguridad/privacidad, métricas y ADR. Confirmar dependencias con módulos consumidores y la
política de migración para los datos existentes.
