# ADR-004: Edición y portadas de Canales dentro de Core

- Estado: aceptada
- Fecha: 2026-10-02
- Responsable: Core / Canales
- SDD/contratos afectados: SPEC-03, SPEC-10…SPEC-13; RF-008…RF-012; bootstrap de canal, PATCH y uploads de portada.

## Contexto

ADR-003 define Canales como módulo Core. Esta rama contenía un prototipo en services/channels con
provisión HTTP, cerca de registro, consulta remota de identidad y proyección de eventos de Emisiones.
Develop ya implementa cuenta/perfil/canal en una transacción y bootstrap local por handle/owner.
La propuesta anterior de persistencia/provisión de Channels también llevaba el número ADR-003;
se retira de la definición vigente, conservando su historial en Git. ADR-003-servicios-cohesivos
es la decisión de arquitectura aplicable.

## Decisión

- Reubicar edición, reglas y archivos bajo services/core/src/main/java/streaming/core/channels.
  Un build, PostgreSQL, gestor de transacciones, seguridad y configuración Core; sin puerto 8083,
  cliente HTTP local, tokens por módulo ni aplicación/Dockerfile/POM propios de Channels.
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
- Retirar endpoints de provisión/eventos, cercas, proyecciones e inbox/outbox internos de Canales.
  Emisiones será fuente local del bootstrap conforme SPEC-04; sigue pendiente en develop. Hasta
  implementarla, stream=null para el canal sin configuración; no acreditar RF-011/RF-012.

## Opciones consideradas

| Opción | Consecuencia |
| --- | --- |
| Mover el servicio sin adaptar provisión/proyección | Conserva coordinación remota e identidad replicada, contradiciendo ADR-003. |
| Mantener solo el bootstrap inicial de develop | Pierde edición y portadas ya implementadas en esta rama. |
| Consolidar las capacidades útiles en Core | Elegida: conserva edición/portadas y adopta transacciones, FK y consultas locales. |

## Consecuencias

Canales comparte release y disponibilidad de Core. PATCH/uploads siguen en /api/channels; lecturas
por handle y owner devuelven el bootstrap compuesto vigente, sin status/activeStream de la proyección
anterior. El consumidor usa channel/handle/profile/stream. La cookie y CSRF son comunes con Cuentas.
El proceso y health son los de Core en 8081; Compose y Docker montan /data/banners además de avatares.

La V2 actualiza bases Core existentes y nuevas; no importa datos del prototipo separado. Para ese
traslado se preservan IDs, versiones, timestamps, claves/URI de imágenes y permisos vigentes con
sus archivos mediante import explícito, según el runbook Core. No desplegar prototipos escribiendo
simultáneamente sobre datos trasladados. Reconciliación y publicación requieren un almacenamiento
compartido consistente antes de habilitar múltiples réplicas.

## Verificación

Pruebas de Core: registro/rollback/retry, composición pública sin secretos, sesión y CSRF, owner/other,
PATCH concurrentes/no-op, descripción Unicode, multipart real, permisos ligados al owner, uso único,
caducidad y conservación del objeto anterior ante fallo SQL o rollback posterior a la escritura.
Las pruebas de BannerFileStore cubren bytes reales, tamaño, dimensiones y claves seguras.
La composición de Emisiones y el perfil integrado SPEC-13 siguen pendientes.

## Revisión

Revisar al implementar la lectura local de Emisiones, antes de habilitar múltiples réplicas o al
cambiar el adaptador de almacenamiento; una extracción requiere los criterios de ADR-003.
