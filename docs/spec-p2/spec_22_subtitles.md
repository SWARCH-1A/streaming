# SPEC-22 — Pistas WebVTT para VOD y controles accesibles

**Prioridad:** P2. **Responsable:** Subtítulos / accesibilidad, con VOD y clientes.
**Estado:** contratos y validador de conformidad; proveedor y reproducción pendientes.
**RF:** RF-063…065. **RNF:** RNF-022…030, 035…038, 041…047.

## 1. Contexto y problema

El propietario aporta texto sincronizado con un vídeo grabado; el espectador puede elegirlo,
cambiarlo o desactivarlo. Se adopta carga manual de WebVTT para VOD exclusivamente. No se genera
transcripción, traducción ni subtítulos de directo. RF-063 admite contenido almacenado.

## 2. Definición y propiedad

Es una capacidad del dominio VOD, no un microservicio. VOD posee pistas, permisos, referencias
privadas a objetos y limpieza; Core sigue siendo autoridad de sesión/estado. Web y móvil poseen
sus controles de presentación. El Gateway enruta, sin escribir datos ni decidir propiedad.
El runtime, almacenamiento de metadatos y proveedor S3 de VOD permanecen en P2-09.

## 3. Historia y recorrido

El propietario elige un VOD validado, recibe su mappingVersion y duración, indica idioma/nombre
y adjunta un archivo. VOD verifica identidad y propiedad, estado del medio, versión temporal,
formato y todos los cues. Confirma el objeto privado antes de anunciar la pista READY; una
carga fallida no sustituye una pista vigente. El espectador consulta las pistas disponibles y
solicita sus bytes por una ruta del mismo dominio de acceso. Seleccionar una pista cambia el
texto presentado sobre la misma posición del vídeo, sin reiniciar la reproducción.

## 4. Alcance y datos

- Carga, listado paginado, lectura WebVTT y retirada por propietario. Cada carga crea un trackId
  opaco; no se reemplazan bytes bajo el mismo ID. Para corregir, cargar otra pista y retirar la anterior.
- P2SubtitleTrack expone trackId, vodId, language, label, kind=subtitles, format=WEBVTT,
  state=READY, trackVersion, mappingVersion, mediaDurationMs y contentPath relativo al VOD.
- No expone claves S3, URLs firmadas, credenciales ni ownerUserId declarado por el cliente.
- Solo READY con mapping vigente se anuncia al player. UPLOADING/FAILED son internos. REMOVED
  conserva tombstone/version para impedir resurrección por reintentos; no aparece en listado.
- No se publica una lista fija de idiomas. El perfil inicial admite etiquetas de idioma de 2–3
  letras minúsculas, script opcional (4 letras, inicial mayúscula) y región opcional (2 mayúsculas
  o 3 dígitos): es, en-US, zh-Hant-TW, es-419. Es un subconjunto de BCP 47, no un validador completo
  del registro IANA; variantes/extensiones quedan fuera de este perfil. label tiene 1–80 caracteres,
  sin controles ni espacios en extremos, y describe la pista, sin afirmar una traducción inexistente.

## 5. Formato interoperable: p2-webvtt-text-v1

Se toma [WebVTT W3C](https://www.w3.org/TR/2026/CRD-webvtt1-20260520/) como formato de intercambio.
El perfil P2 restringe sus características para poder probar el mismo comportamiento en Web/móvil.
No afirma implementar todos los parsers ni todas las capacidades de WebVTT.

| Aspecto | Regla del perfil |
| --- | --- |
| Codificación | UTF-8 estricto; BOM inicial opcional; LF, CRLF o CR |
| Tamaño | Archivo ≤1 MiB; máximo 10 000 cues; límites de este perfil, no del estándar |
| Cabecera | WEBVTT con comentario opcional sin flecha; línea vacía antes de bloques |
| Bloques | Cues con identificador opcional, comentarios NOTE y texto multilínea |
| Tiempo | mm:ss.mmm o hh:mm:ss.mmm; minutos/segundos 00…59; horas de al menos 2 dígitos |
| Intervalos | 0 ≤ inicio < fin ≤ duración autoritativa del VOD; inicios no decrecientes; solapamiento permitido |
| Texto | No vacío; texto y entidades amp/lt/gt/nbsp/lrm/rlm; sin caracteres de control salvo tabulador/saltos |
| Fuera del perfil | STYLE, REGION, ajustes de cue, etiquetas, timestamps dentro del texto; se rechazan, no se eliminan silenciosamente |

Los tiempos son milisegundos desde el inicio del **medio VOD**. No son UTC ni streamOffsetMs.
El archivo se prepara contra el montaje final. VOD comprueba expectedMappingVersion al validar
y nuevamente al confirmar la asociación; si cambió, 409. Si cambia después, deja de anunciar/servir
la pista anterior hasta aportar otra compatible. No se desplazan cues automáticamente para
ocultar huecos de reconexión. El cliente compara mappingVersion con el vídeo que está mostrando.

## 6. Interfaces y seguridad

La fuente es `contracts/p2.json.operations` y sus schemas P2Subtitle*, con status=contract-only.
Las rutas, multipart, errores e idempotencia están en [contratos HTTP](../contratos_p2_http.md#subtitulos-vod).
La especificación estructura estas interfaces; no acredita endpoints ejecutables.

El propietario activo puede cargar/retirar y ver pistas de su borrador validado. El público solo
lista/lee si puede reproducir el VOD publicado; una sesión presentada inválida no se degrada a anónima.
Cookie Web conserva CSRF; Bearer móvil depende del contrato de Cuentas. Un ID en la petición no
demuestra propiedad. Servicio/autoridad caída da 503, no autorización implícita.

VOD valida permisos al confirmar mutaciones y al servir cada recurso. La pista hereda suspensión,
retirada y borrado del VOD; S3 permanece privado y contentPath no acepta URLs ni traversal.
La retirada invalida acceso antes de limpiar; el trabajo de limpieza conserva referencia y
reintentos sin republicar. Reactivar una cuenta no recupera pistas retiradas. No se promete
retirar bytes ya descargados. P2-17 debe acordar propagación y vigencia de autorización.

## 7. Controles de reproductor y accesibilidad

RF-064/065 se implementarán con un control «Subtítulos», opción «Desactivados» y nombres/idiomas
de pistas reales. Inicialmente desactivados; la elección se mantiene durante seek y cambio de
calidad del mismo VOD. Al cambiar VOD, se descarta su trackId anterior. Web usará texto de pista
como tal, nunca innerHTML de contenido cargado; móvil necesita un adaptador probado del mismo perfil.

Teclado: foco visible, selección sin pérdida de foco, Escape al cerrar menú y retorno al control.
Nombre, idioma y selección deben ser accesibles; loading/error se anuncian sin repetir cada cue
por una región aria-live. Un error al cargar una pista muestra un aviso y permite reintentar o
desactivar; no pausa ni reinicia HLS. Se descartan respuestas tardías de pistas ya deseleccionadas.
No se simulan subtítulos cuando no existen. Contraste y tamaño se comprueban sobre vídeo real.

## 8. Criterios de aceptación

- [ ] CA-01: revisión de contratos/perfil con VOD, Gateway, Web y móvil; modalidad y origen definidos.
- [ ] CA-02: proveedor real autoriza propietario y rechaza UTF-8/formato/timing inválidos sin publicación parcial.
- [ ] CA-03: listado READY y controles permiten elegir/cambiar/desactivar sin reiniciar el vídeo.
- [ ] CA-04: seek, calidad, reconexión y cambio de mapping conservan sincronía o invalidan explícitamente.
- [ ] CA-05: suspensión/retirada/borrado impiden URL directa y limpieza se recupera sin resurrección.
- [ ] CA-06: teclado, nombres, foco y lectura asistiva funcionan en reproductores reales.
- [ ] CA-07: pista caída no corta vídeo; errores y reintentos observables.

## 9. Código y verificación disponible

`contracts/webvtt.py` es una herramienta Python offline de **conformidad**, no el backend VOD.
`tests/contracts/test_subtitle_contracts.py` prueba schemas, permisos declarados, rutas, límites,
codificación y tiempos. Los archivos en `tests/contracts/fixtures/subtitles` son ficticios.
La suite común de contratos/CI los descubre sin dependencias nuevas.

```sh
python contracts/check_p2.py
python -m unittest discover -s tests/contracts -p 'test_*.py'
python contracts/webvtt.py tests/contracts/fixtures/subtitles/education-es.vtt --duration-ms 12000
# Tras instalar las dependencias Web; también se ejecuta en CI Web.
node tests/contracts/test_subtitle_schemas.mjs
```

El número recibido por el checker es dato de prueba; el proveedor lo obtendrá de su propia
duración validada, nunca del archivo/cliente. No hay aún persistencia, permisos ejecutables,
controles ni prueba audiovisual. Los ejemplos de dos idiomas prueban offsets iguales, no sincronía
perceptual con un vídeo que no se ha reproducido. No cerrar SPEC-22 con esta evidencia aislada.

## 10. Decisiones, dependencias y costo

[ADR-018](../adr/ADR-018-subtitulos-vod-webvtt.md) fija la propuesta de perfil y alternativas.
P2-04 queda definido como VOD y autoría del propietario; P2-05 como carga WebVTT de texto.
Límites, idiomas y firmas necesitan revisión de consumidores. El costo es preparar las pistas
manualmente y restringir estilos avanzados; se evitan un proveedor de transcripción y otro runtime.
Persistencia/permisos dependen de SPEC-14/P2-09/P2-17; presentación de SPEC-18/19 y SPEC-08.

## 11. Entrega al proveedor y consumidores

Los contratos permiten avanzar sin elegir el runtime de VOD. Antes de implementar el recorrido
integrado, el responsable de cada dominio debe aportar y verificar lo siguiente:

| Responsable | Entrega necesaria |
| --- | --- |
| VOD | vodId, propietario, duración validada y mappingVersion; estados de borrador/publicación/restricción y asociación transaccional de pistas |
| VOD y almacenamiento | Objeto privado confirmado antes de READY; limpieza recuperable; no servir pistas con mapping obsoleto, retiradas o de contenido eliminado |
| Cuentas/VOD/Gateway | Autorización de cada operación y URL directa, suspensión y fallo de autoridad conforme a P2-17; no basta con ocultar un botón |
| Web/móvil | Selección/desactivación, seek, calidad, rechazo de respuestas tardías, controles accesibles y fallo de pista sin cortar vídeo |
| Integración | Proveedores reales, acceso por URL directa y recuperación ante fallos; fixtures de texto no acreditan reproducción |

`pendingInterfaces.subtitles` conserva P2-09 y P2-17 como dependencias de ejecución; P2-04/05
describen las decisiones del perfil ya propuestas. La asignación de VOD requiere coordinación
del equipo. SPEC-22 permanece abierta hasta completar los criterios anteriores.
