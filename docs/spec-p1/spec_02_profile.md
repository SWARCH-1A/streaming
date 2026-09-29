# SPEC-02 Perfil público P1

- **Módulo:** profile
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define RF-006…RF-007 y RNF-032. El perfil aporta identidad pública al canal y a mensajes sin exponer credenciales ni información privada.

## 2. Estado del sistema y brecha

Perfil depende del contrato de Identity y lo consumen Channels, Chat y Discovery. Profile es propietario del nombre visible, la biografía y el avatar; no expone datos de autenticación.

## 3. Historia de usuario

Como usuario, quiero consultar y editar mi presentación pública, para que los espectadores reconozcan mi cuenta y canal.

## 4. Alcance

### Dentro de P1

- Consultar nombre visible, biografía y avatar.

- Editar nombre visible, biografía y avatar del usuario autenticado.

- El handle único no se puede cambiar en P1; permanece como identificador de URL del canal.

- Avatar opcional; si no existe se presenta avatar por defecto. Formatos JPEG/PNG/GIF, máximo 10 MB y dimensiones mínimas obligatorias de 200×200 px.

### Fuera de P1

- Cambio de handle, preferencias avanzadas, banner/portada del canal y cualquier dato de autenticación.

- Verificación de correo y recuperación de contraseña.

### Supuestos acordados

- Perfil es propietario del nombre visible, biografía y avatar; Canales es propietario de descripción y portada del canal.

- Email, contraseña y datos de sesión no son datos del perfil público.

## 5. Requisitos funcionales

- RF-006 leer el perfil público sin exponer campos privados.

- RF-007 el propietario edita nombre visible, biografía y avatar; el handle es inmutable en P1.

- RNF-032 — no exponer información privada a vistas públicas.

## 6. Criterios de aceptación

- CA-01 — visitante consulta el canal y ve handle, nombre visible, bio y avatar, pero no email ni credenciales.

- CA-02 — usuario autenticado actualiza sus campos y recibe la nueva representación; los demás usuarios reciben denegación.

- CA-03 — el cambio de nombre visible no modifica el handle ni URL estable.

- CA-04 — archivo no admitido, mayor de 10 MB o con ancho o alto inferior a 200 px se rechaza con mensaje claro y conserva el avatar anterior.

- CA-05 — sin avatar configurado se muestra el recurso predeterminado.

- CA-06 — `GET /api/profile/users/{userId}` solo devuelve cuentas ACTIVE; para PENDING, EXPIRED y userId inexistente devuelve el mismo 404. Si ACTIVE no tiene proyección local aún, Profile consulta el lookup público de Identity y responde displayName=handle, bio vacía, avatarUri=null y profileVersion=0; si Identity no está disponible devuelve 503, nunca 404. El acceso público no permite enumerar handles reservados.
- CA-07 — `ProfilePublicChanged` solo se publica para identidades ACTIVE anunciadas por `IdentityPublicChanged`. Si la proyección local aún no procesó el evento, CA-06 gobierna el fallback mediante lookup de Identity; PENDING/EXPIRED/inexistente conservan 404 uniforme.

## 7. Diseño técnico y datos

- Propiedad: userId externo, displayName, bio, avatar URI, createdAt y updatedAt pertenecen a Profile; email/handle/contraseña son autoridad de Identity.
- Mantener la proyección de identidad activa desde `IdentityPublicChanged`; ante un miss, consultar `GET /api/identity/public/users/{userId}` para distinguir ACTIVE sin proyección de userId no visible. Identity responde solo para ACTIVE y no emite evento para PENDING/EXPIRED. Si Identity no responde, devolver 503; nunca convertir una falla de dependencia en 404.

- Proponer API neutral para consultar perfil público y editar perfil del usuario autenticado; no aceptar userId objetivo desde el cliente para actualizar.

- Usar carga segura de imágenes, validar formato real y tamaño en servidor, limitar exposición de metadatos y configurar cache invalidation.

- ADR del responsable: almacenamiento de objetos local compatible con despliegue reproducible frente a servicio compatible S3/MinIO; formato de URL y eliminación/reemplazo de objetos.

## 8. Dependencias y contratos de integración

- Identity aporta principal autenticado y handle estable, también mediante lookup activo para el fallback del perfil público.

- Channels muestra campos de perfil en la página pública; Chat necesita nombre visible/avatar del autor.

- Contrato del perfil debe separar datos públicos y privados.

## 9. Decisiones y preguntas abiertas

**Decisiones:** avatar/nombre visible/bio pertenecen a Profile; cover/banner pertenece a Channels; P1 no cambia handle; usar formatos/límites observables de Twitch como baseline.

**Abierto:** no hay preguntas de producto bloqueantes. La persona responsable debe registrar el ADR y cerrar los detalles de implementación listados en Diseño.

## 10. Verificación

- lectura pública y edición autenticada/autorizada, incluyendo intento de editar el perfil ajeno.

- validación de tamaño/tipo, sustitución atómica de avatar y fallback.

- prueba de respuesta pública para verificar que no filtra email, contraseña, tokens ni identificadores privados.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** S/M. **Riesgos:** duplicar nombre visible entre módulos, URL del avatar inaccesible al proxy, exposición de metadatos personales y carga de archivos arbitrarios. **Consecuencia:** no implementar capacidades explícitamente fuera de P1.
