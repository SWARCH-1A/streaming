# ADR-018 — Subtítulos aportados por el propietario dentro de VOD

- Estado: propuesta con contratos/validador; revisión de VOD y consumidores pendiente.
- Fecha: 2026-10-25.
- Responsable: Subtítulos / accesibilidad, coordinado con VOD, Web, móvil y Gateway.
- Trazabilidad: SPEC-22; RF-063…065; P2-04/05 y dependencia P2-09/17.

## Contexto

RF-063 permite asociar pistas a contenido almacenado. El alcance encargado es aportar archivos
preparados para VOD; no requiere crear un servicio de transcripción ni subtítulos en vivo.
La identidad, duración, edición temporal y permisos pertenecen a VOD y deben regir también el texto.

## Decisión propuesta

Cargar WebVTT UTF-8 mediante API VOD por propietario activo. La pista se valida contra la duración
y versión del montaje final. Se guarda como objeto privado y solo se anuncia después de confirmar
su disponibilidad. Listado/lectura heredan la autorización del vídeo; retirar conserva tombstone
y limpieza recuperable. El reproductor selecciona/desactiva pistas reales sin reiniciar vídeo.

El perfil p2-webvtt-text-v1 limita tamaño/cues y admite texto, entidades, identificadores, comentarios
y solapamientos. Rechaza estilos, markup y ajustes avanzados con error explícito. La definición
completa está en [SPEC-22](../spec-p2/spec_22_subtitles.md), sin segunda lista contradictoria.
Idiomas tienen un subconjunto sintáctico explícito de BCP 47; no se afirma soporte de todo el registro.
Los límites y firmas quedan sujetos a revisión conjunta antes de integrar los reproductores.

## Alternativas y consecuencias

WebVTT permite texto temporizado interoperable con el elemento track del navegador y adaptadores
nativos. SRT exigiría conversión previa para ese consumo; TTML ofrece más expresividad y más trabajo
de parsing/renderizado. Transcripción automática introduciría procesamiento, calidad lingüística,
costos/proveedor y estados de job que no son necesarios para el alcance acordado.

Un servicio nuevo duplicaría propiedad/autorización de VOD. Reutilizar VOD evita ese salto de red,
pero hace necesaria su entrega antes de probar permisos y persistencia. El perfil de texto reduce
ambigüedad entre players a costa de estilos/posicionamiento avanzados. Un cambio de montaje invalida
pistas anteriores en vez de inventar una transformación temporal. La carga manual exige preparar
y revisar cada idioma: los contratos no certifican traducción, exactitud lingüística ni sincronía visual.

## Estado de implementación y aceptación

Implementado: schemas neutrales, operaciones contract-only, fixtures y checker Python offline.
Python es herramienta de pruebas, no elección del runtime de VOD. No se implementan otro servicio,
base ni rutas de producción. CA-01…07 siguen abiertos hasta revisión de contratos y pruebas de
proveedor/reproductores reales, suspensión por URL directa, recuperación y accesibilidad humana.
