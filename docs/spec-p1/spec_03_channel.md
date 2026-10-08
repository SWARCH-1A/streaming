# SPEC-03 Canal público y propiedad P1

- **Módulo:** channels
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

RF-008…RF-012 definen canal, propiedad, presentación y consulta LIVE. Cuenta, perfil y canal son entidades diferentes, pero su registro y presentación pertenecen al núcleo Core y no necesitan servicios separados.

## 2. Definición del componente

Canales pertenece a Core, crea el canal en la transacción de registro y ofrece una composición pública por handle. Seguimiento y VOD son capacidades futuras.

## 3. Historia de usuario

Como visitante o propietario, quiero consultar y mantener la página del canal, para encontrar al broadcaster y ver si está transmitiendo.

## 4. Alcance

### Dentro de P1

- Crear exactamente un canal por `ownerUserId` en la transacción del registro. El canal no posee ni modifica handle; la ruta se resuelve con el handle canónico de Identity.

- Editar descripción e imagen de portada del canal. Portada opcional, JPEG/PNG/GIF, máximo 10 MB, tamaño recomendado 1200×480 px. ADR-008 fija S3 privado como proveedor predeterminado; filesystem solo se selecciona explícitamente.

- Consultar la página pública, estado LIVE/OFFLINE y stream activo.

- Mostrar solo emisiones en vivo en la sección de streams de canal de P1; VOD se documenta fuera de P1.

### Fuera de P1

- Seguir/dejar de seguir/listar canales seguidos; catálogo VOD del canal; configuración avanzada.

- Cambiar handle o nombre visible; esos datos pertenecen a Identity/Profile en P1.

### Supuestos acordados

- Streaming es fuente autoritativa de estado de sesión; el bootstrap Canales consulta un batch público autoritativo Streaming; los listados Discovery consultan la proyección local con frescura explícita.

- La portada pertenece al canal; avatar/nombre visible pertenecen a Profile.

## 5. Requisitos funcionales

- RF-008 crear el canal al completar registro; una cuenta posee uno.

- RF-009 propietario edita descripción y portada.

- RF-010 página pública de canal consultable sin login.

- RF-011 reflejar estado de emisión según sesión de Streaming.

- RF-012 mostrar streams activos asociados; no mostrar catálogo VOD en P1.

## 6. Criterios de aceptación

- CA-01: registro confirmado crea exactamente un canal por cuenta en la misma transacción; retry conserva channelId. FK y UNIQUE ownerUserId, no provisión HTTP.
- CA-02: owner edita descripción/banner; otro usuario 403, inválido conserva anterior. La publicación usa el proveedor seleccionado, S3 privado por defecto, y un fallo conserva la portada anterior.
- CA-03: /channels/{handle} usa GET /api/channels/by-handle/{handle}, composición local de canal, handle y perfil; bootstrap agrega metadata/estado desde batch Streaming; fallo conserva canal con UNKNOWN. El player consulta directamente la sesión autoritativa. No join Identity→Profile→Channels en navegador.
- CA-04: cambio de disponibilidad confirmado por Emisiones aparece en <=5 s; proyección de emisiones aplicada en SQL Core; estado no confirmado se marca UNKNOWN, sin demorar publicación de cuenta/canal.
- CA-05: LIVE muestra sesión PLAYABLE; gracia indica reconectando, OFFLINE no inventa VOD.
- CA-06: cuentas activas publicables desde commit, inexistente/no activo 404 uniforme; channelVersion inicia 0 y sube solo por cambio real.
- CA-07: PATCH parcial sobre estado más reciente, serializado; campos distintos concurrentes se conservan, mismo campo último commit. Error no cambia versión/datos.
- CA-08: rollback de registro no deja cuenta/canal parcial; respuesta perdida tras commit recupera IDs. No compensation worker ni cerca remota de 24 h.
- CA-09: después de ACTIVE el canal existe y se consulta; sin atraso de publicación entre módulos. Fallo de Core/SQL es indisponibilidad explícita.

## 7. Diseño técnico y datos

Canales posee channelId/ownerUserId/description/banner/version; cuenta posee handle y perfil. FK local hacia cuenta, UNIQUE ownerUserId. Edición por su repositorio; consultas de canal usan read model SQL revisado con perfil y batch público Streaming para metadata/estado; listados Discovery usan proyección pública local. Banner opcional JPEG/PNG/GIF <=10 MB, recomendado 1200×480; upload owner/channel ligado, un uso, 15 min. ADR-008 usa S3 privado por defecto, con objetos `pending/` y `public/` bajo el prefijo de banners; Core sirve `bannerUri` por `/api/channels/banners/{key}` y nunca expone una URL, ACL o credencial del bucket. Filesystem requiere selección explícita y almacenamiento compartido al escalar Core. No guardar nombre visible o estado como otra autoridad.

## 8. Dependencias y contratos de integración

Registro local Cuentas→Canales participa en una transacción; Streaming obtiene validación de dueño por contexto privado Core y publica snapshots de emisión; el registro del canal sigue local. Catálogo/Discovery también son módulos Core. Chat/Media no leen las tablas de canal. Frontend consume bootstrap público compuesto y las rutas de imagen permanecen servidas por Core; SPEC-12 integra sus paths y SPEC-13 documenta el bucket y su configuración.

## 9. Decisiones y preguntas abiertas

Un canal por cuenta, handle inmutable y publicación inmediata tras commit. Core compone datos locales con snapshot público Streaming; Discovery usa su proyección SQL.

## 10. Verificación

Registro/rollback/retry 1:1, own/other/anonymous, consulta handle normalizado, edición parcial concurrente/no-op, imágenes con S3 privado y rutas Core (o filesystem explícito), estados PLAYABLE/gracia/OFFLINE y ausencia de email/hash/secreto.

## 11. Esfuerzo, riesgos y consecuencias

**Riesgos:** conservar IDs al consolidar, URI de banner y composición pública sin secretos. La frontera de proceso de Channels no aporta aislamiento independiente en el diseño Core.
