# ADR-004: Edición y portadas de Canales dentro de Core

- Estado: aceptada para Canales; composición de Emisiones actualizada por ADR-005
- Fecha: 2026-10-02
- Responsable: Core / Canales
- SDD/contratos afectados: SPEC-03, SPEC-10…SPEC-13; RF-008…RF-012; bootstrap de canal, PATCH y uploads de portada.

## Contexto

Canales pertenece a Core según ADR-005. Cuenta, perfil y canal se crean en una transacción
PostgreSQL. La edición, las portadas y el bootstrap público por handle/owner necesitan conservar
esa propiedad local y compartir la seguridad de Cuentas. El estado de emisión pertenece a Streaming.

## Decisión

- Ubicar edición, reglas y archivos bajo services/core/src/main/java/streaming/core/channels.
  Un build, PostgreSQL, gestor de transacciones, seguridad y configuración Core.
- Conservar ChannelInitializer y registro transaccional de Cuentas. JdbcChannels posee las escrituras
  del módulo y expone ChannelQueries con composición de las vistas públicas de Cuentas.
- PATCH parcial valida la sesión mediante la interfaz local de Cuentas y bloquea la fila del canal.
  Owner exclusivo; descripción de hasta 500 puntos de código, null limpia a cadena vacía; banner
  null retira. No-op conserva channelVersion; cada cambio real incrementa uno. No outbox de réplica.
- Reutilizar el ciclo de archivos de ADR-002 para portadas en un volumen separado. JPEG/PNG/GIF
  decodificados, <=10 MB, sin mínimo dimensional (1200×480 recomendado; límite defensivo 40 MP).
  Upload opaco de un uso, ligado a owner/channel y válido 15 min. Publicación antes del commit,
  eliminación anterior después y retiro del nuevo objeto ante rollback. Reconciliar archivos sin
  referencia tras una gracia de un día; limpieza de permisos vencidos en lotes de 500 cada 5 min.
- Aplicar V2__channel_editing_and_banners.sql sobre el historial Core, sin alterar V1. FK compuesta
  en los permisos garantiza pertenencia del canal al owner; banner_key acompaña la URI pública.
- La creación de canal usa la transacción local de registro. Streaming Rust es fuente autoritativa
  del estado de emisión en el bootstrap mediante batch público según ADR-005. Si Streaming falla,
  la lectura conserva cuenta/perfil/canal con estado UNKNOWN.

## Opciones consideradas

| Opción | Consecuencia |
| --- | --- |
| Canales como servicio separado con provisión y proyección | Introduce coordinación remota y réplica de identidad para datos que comparten transacción con Cuentas. |
| Bootstrap público sin edición ni portadas | No cubre las capacidades de Canales de SPEC-03. |
| Canales como módulo Core | Elegida: edición/portadas con transacciones, FK y consultas locales. |

## Consecuencias

Canales comparte release y disponibilidad de Core. PATCH/uploads siguen en /api/channels; lecturas
por handle y owner devuelven el bootstrap compuesto channel/handle/profile/stream.
La cookie y CSRF son comunes con Cuentas.
El proceso y health son los de Core en 8081; Compose y Docker montan /data/banners además de avatares.

La V2 actualiza bases Core existentes y nuevas. Reconciliación y publicación requieren un almacenamiento
compartido consistente antes de habilitar múltiples réplicas.

## Verificación

Pruebas de Core: registro/rollback/retry, composición pública sin secretos, sesión y CSRF, owner/other,
PATCH concurrentes/no-op, descripción Unicode, multipart real, permisos ligados al owner, uso único,
caducidad y conservación del objeto anterior ante fallo SQL o rollback posterior a la escritura.
Las pruebas de BannerFileStore cubren bytes reales, tamaño, dimensiones y claves seguras.
La aceptación de la composición de Emisiones y del perfil integrado corresponde a SPEC-13.

## Revisión

Revisar al implementar la composición del snapshot Streaming, antes de habilitar múltiples réplicas o al
cambiar el adaptador de almacenamiento; otras extracciones requieren los criterios de fases_futuras.md.
