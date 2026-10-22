# SPEC-22 — Pistas de subtítulos y controles

**Prioridad:** P2. **Responsable:** Responsable de subtítulos / accesibilidad. **Estado:** diseño para desarrollo.
**RF:** RF-063…065. **RNF:** RNF-022…030, 035…038, 041…047.

## 1. Contexto y problema

El espectador necesita consultar, activar y elegir pistas disponibles. El responsable debe
concretar si se asocian a live, VOD o ambos y cómo se producen antes de cerrar formato/API.

## 2. Definición del componente

Capacidad integrada con dueños del contenido, almacenamiento de pistas y reproductores.
No se selecciona servicio de transcripción ni proveedor externo por esta definición.

## 3. Historia de usuario

Como espectador, quiero activar/desactivar subtítulos y elegir una pista de las disponibles.

## 4. Alcance

Asociación, listado, lectura, selección y retirada de pistas. Carga de archivos o generación,
idiomas y modalidad live/VOD corresponden a P2-04…05. Cada pista hereda permisos y vida útil
del contenido y no se publica mediante un bucket que eluda su restricción.

## 5. Requisitos funcionales

RF-063 asocia una pista a contenido; RF-064 habilita/deshabilita la presentación; RF-065 elige
la pista disponible. Una pista ausente o fallida no se muestra como traducción existente.
El player mantiene controles accesibles y la relación temporal con el video.

## 6. Criterios de aceptación

- [ ] CA-01: el responsable cierra modalidad, origen, formato, validación e idiomas y publica schemas de operaciones/pistas.
- [ ] CA-02: un actor autorizado asocia pista válida; formato o timing inválidos se rechazan sin sustituir una pista vigente.
- [ ] CA-03: listado solo anuncia pistas disponibles; seleccionar y desactivar actualiza el player sin reiniciar innecesariamente el video.
- [ ] CA-04: seek, cambio de calidad y reconexión preservan sincronía según el formato acordado.
- [ ] CA-05: suspender, retirar o eliminar contenido impide acceso directo a pistas y coordina su limpieza cuando corresponde.
- [ ] CA-06: controles funcionan por teclado y exponen nombre, idioma y estado accesibles.
- [ ] CA-07: una pista caída no corta HLS y se presenta un error específico.

## 7. Diseño técnico y datos

Datos mínimos a concretar: trackId, referencia de contenido, idioma/label, formato, versión,
estado y referencia de objeto. La representación de cues/timestamps y el soporte de pista en
vivo requieren el formato elegido. Si existe generación, se especifican job, estados, reintento
y proveedor; no se supone que haya un generador implementado.

En VOD, el mapping temporal debe coincidir con los intervalos del video; no se reutiliza sin
conversión el reloj de sesión si la grabación elimina huecos. Las pistas comparten vida útil
y acceso del VOD, con publicación/validación antes de anunciarlas al player.

## 8. Dependencias y contratos de integración

`contracts/p2.json.pendingInterfaces` enumera attach/list/read/delete y sus bloqueos.
Streaming/VOD identifican contenido y permisos; el almacén conserva pista; Web/móvil eligen
la pista. SPEC-14 y [contratos de medios](../contratos_p2_media.md) rigen acceso y timing.

## 9. Decisiones y preguntas abiertas

P2-04: live/VOD/ambos y autoría. P2-05: carga/generación, formatos, idiomas, validaciones y
sincronía. La elección corresponde al responsable y debe producir schemas y pruebas concretas,
sin que esta SPEC presente una política pendiente como resuelta.

## 10. Verificación

Tras elegir formato, probar pistas válidas/rotas, permisos, cambio de idioma, seek y cambio
de calidad en reproductores reales. Verificar URL directa suspendida y accesibilidad de controles.
Una etiqueta visual de subtítulos no acredita lectura o sincronización de una pista.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo dependiente del origen seleccionado. Transcripción automática puede añadir proveedor
y procesamiento pesado; no se incluye implícitamente por exigir controles de selección.
