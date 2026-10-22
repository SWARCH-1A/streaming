# SPEC-03 Canal público y propiedad P1

> Perfil de la primera entrega. Para P2 rigen el [alcance vigente](../alcance_p2.md),
> las [SPEC P2](../spec-p2/README.md) y [ADR-015](../adr/ADR-015-arquitectura-p2.md).
> Este documento conserva contratos y aceptación de P1; no acredita las ampliaciones de P2.


- **Módulo:** channels
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

La página del canal reúne la presentación del usuario y el estado de su emisión. RF-008…RF-012
definen su creación, propiedad, edición y consulta en vivo. Aunque cuenta, perfil y canal son
entidades distintas, Core puede registrarlas juntas y componer sus datos públicos localmente.

## 2. Definición del componente

Canales es un módulo Core. Crea el canal dentro de la transacción de registro y ofrece una
consulta pública por handle. El seguimiento de canales y el catálogo VOD quedan para fases futuras.

## 3. Historia de usuario

Como visitante o propietario, quiero consultar y mantener la página del canal, para encontrar al broadcaster y ver si está transmitiendo.

## 4. Alcance

### Dentro de P1

- Crear exactamente un canal por `ownerUserId` en la transacción del registro. El canal no posee ni modifica handle; la ruta se resuelve con el handle canónico de Identity.

- Editar descripción e imagen de portada del canal. Portada opcional, JPEG/PNG/GIF, máximo 10 MB, tamaño recomendado 1200×480 px. ADR-009 fija S3 privado como proveedor predeterminado; filesystem solo se selecciona explícitamente.

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

- CA-01: el registro confirmado crea exactamente un canal por cuenta en la misma transacción. Un reintento conserva channelId. La base aplica FK y UNIQUE sobre ownerUserId; la creación es local, sin provisión HTTP.

- CA-02: el propietario puede editar la descripción y la portada. Otro usuario recibe 403. Una entrada inválida o un fallo de publicación conserva la portada anterior. Se usa el proveedor seleccionado, S3 privado por defecto.

- CA-03: /channels/{handle} consume GET /api/channels/by-handle/{handle}, que compone localmente canal, handle y perfil. GET /api/channels/{channelId} ofrece el mismo bootstrap para abrir Watch directamente. El bootstrap agrega metadatos y estado mediante un batch Streaming; si falla, conserva el canal con UNKNOWN. El reproductor consulta directamente la sesión autoritativa. El navegador recibe la composición sin encadenar consultas Identity→Profile→Channels.

- CA-04: un cambio de disponibilidad confirmado por Emisiones aparece en hasta 5 s. La proyección de emisiones se aplica en SQL Core. Un estado no confirmado se marca UNKNOWN; la publicación de cuenta y canal sigue siendo inmediata.

- CA-05: LIVE muestra la sesión PLAYABLE. Durante la gracia se indica que está reconectando. OFFLINE no muestra contenido VOD ficticio.

- CA-06: una cuenta activa se puede publicar desde el commit. Una cuenta inexistente o no activa devuelve un 404 uniforme. channelVersion comienza en 0 y aumenta solo ante un cambio real.

- CA-07: PATCH se aplica parcialmente sobre el estado más reciente y sus escrituras se serializan. Cambios concurrentes de campos distintos se conservan; si afectan al mismo campo, prevalece el último commit. Un error no cambia los datos ni la versión.

- CA-08: un rollback del registro no deja una cuenta o canal parcial. Si se pierde la respuesta después del commit, el reintento recupera los IDs. No se necesita un worker de compensación ni una barrera remota de 24 h.

- CA-09: al alcanzar ACTIVE, el canal ya existe y se puede consultar, sin esperar una publicación entre módulos. Un fallo Core o SQL se comunica como indisponibilidad.

## 7. Diseño técnico y datos

Canales posee channelId/ownerUserId/description/banner/version; cuenta posee handle y perfil. FK local hacia cuenta, UNIQUE ownerUserId. Edición por su repositorio; consultas de canal usan read model SQL revisado con perfil y batch público Streaming para metadata/estado; listados Discovery usan proyección pública local. Banner opcional JPEG/PNG/GIF <=10 MB, recomendado 1200×480; upload owner/channel ligado, un uso, 15 min. ADR-009 usa S3 privado por defecto, con objetos `pending/` y `public/` bajo el prefijo de banners; Core sirve `bannerUri` por `/api/channels/banners/{key}` y nunca expone una URL, ACL o credencial del bucket. Filesystem requiere selección explícita y almacenamiento compartido al escalar Core. ChannelBootstrapService compone un puerto Streaming con datos locales; batch <=1 s/64 KiB y
observación dentro de 5 s. Ausencia confirmada es OFFLINE/true, error o dato incoherente UNKNOWN/false.
La lectura privada no sigue redirecciones ni reintenta. No guardar nombre visible o estado como otra autoridad.

Las rutas públicas de imágenes sirven bytes con Content-Type image/*. Un Accept incompatible devuelve 406 NOT_ACCEPTABLE; no es un fallo 500 del almacenamiento.

## 8. Dependencias y contratos de integración

Registro local Cuentas→Canales participa en una transacción; Streaming obtiene validación de dueño por contexto privado Core y publica snapshots de emisión; el registro del canal sigue local. Catálogo/Discovery también son módulos Core. Chat/Media no leen las tablas de canal. Frontend consume bootstrap público compuesto y las rutas de imagen permanecen servidas por Core; SPEC-12 integra sus paths y SPEC-13 documenta el bucket y su configuración.

## 9. Decisiones y preguntas abiertas

Un canal por cuenta, handle inmutable y publicación inmediata tras commit. Core compone datos locales con snapshot público Streaming; Discovery usa su proyección SQL.

## 10. Verificación

Comprobar la relación 1:1 del registro, rollback y reintentos; los permisos de propietario,
otro usuario y visitante; la consulta por handle normalizado; y la edición parcial concurrente,
incluidas las solicitudes sin cambios.

Las pruebas de imágenes deben usar S3 privado con rutas Core, o filesystem seleccionado
explícitamente. Verificar PLAYABLE, reconexión y OFFLINE, y revisar que ninguna respuesta incluya
email privado, hashes o secretos.

## 11. Esfuerzo, riesgos y consecuencias

Los principales riesgos son perder IDs al consolidar datos, romper las URI de portada o exponer
secretos al componer la página pública. Canales comparte el proceso Core y, por tanto, su
disponibilidad; no tiene aislamiento de fallo independiente.
