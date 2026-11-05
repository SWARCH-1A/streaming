# ADR-026 — Rendiciones de origen por Enhanced RTMP, HLS y captura independientes

- Estado: aceptada para integración local P2.
- Fecha: 2026-11-03.
- Responsabilidad: Integración / Streaming / Media / VOD.
- Trazabilidad: SPEC-04, SPEC-14, SPEC-24; P2-08/09; contratos de medios P2.

## Contexto

MediaMTX 1.21.1 recibe las pistas Enhanced RTMP que OBS ≥31 puede enviar por una sola
conexión. Su adaptador HLS original elige solo el primer video y el grabador MPEG-TS reúne
todos los videos. Eso no cumple el requisito de seleccionar y conservar hasta cuatro calidades.

## Decisión

Conservar la conexión Enhanced RTMPS y su autorización única. La instalación acepta de uno a
cuatro videos H.264 y un audio AAC opcional, codificados en el emisor. Validar este límite antes
de añadir el publisher; no crear emisiones auxiliares, claves públicas ni cupos por calidad.

La adaptación se aplica sobre el commit fijado de MediaMTX mediante reemplazos comprobados.
Agrupa las descripciones originales manteniendo sus identidades: un video y el mismo AAC por
grupo. Cada grupo tiene un Reader y muxer HLS independientes; conserva PTS, NTP y unidades
codificadas. El maestro reúne resolución, códecs y ancho de banda calculados por cada muxer;
no deduce una escalera ni genera frames. Los grupos de audio y sus URIs se delimitan por
rendición; playlists, init y segmentos usan nombres públicos planos `rend_N__…`.

Cada grupo tiene un grabador MPEG-TS nativo con su AAC. Una fuente convencional conserva
`rend_source`; varias pistas usan `rend_0`…`rend_3` según el orden anunciado por el emisor.
Cada inventario declara la lista completa de rendiciones antes de capturar segmentos.
Media comprueba bytes/hash/fsync y certifica el cierre de cada grupo. El worker valida el plan,
retiene todas las identidades `(sesión,rendición,generación,secuencia)` y exige el cierre de
todas las rendiciones/generaciones antes de confirmar el índice final. Ausencia, cambio de plan,
reinicio no certificado o bytes contradictorios producen fallo de captura, nunca un VOD parcial
presentado como completo. Los objetos confirmados permanecen bajo propiedad VOD/MinIO.

Los encoders del emisor deben compartir framerate, reloj y keyframes. Se entrega un ejemplo
OBS de cuatro calidades, todas ≤720p; no se anuncia 1080p cuando no se recibió.

## Alternativas y consecuencias

Transcodificar en servidor contradice el alcance y añade consumo y escalado no autorizados.
Varias conexiones independientes complican autorización, sincronía y reserva de cupo sin resolver
el empaquetado. Omitir pistas en el VOD pierde contenido que el producto exige conservar.

Esta adaptación aumenta lectores y buffers hasta cuatro por sesión; no modifica la capacidad
lógica. Fija H.264/AAC para interoperabilidad Web/Android/iOS y no acredita otros códecs.
Actualizar MediaMTX exige revisar la adaptación, recompilar y repetir el recorrido audiovisual.
La configuración OBS requiere codificación suficiente en el equipo de origen y CA confiable.

## Verificación

`tests/integration/p2-multirendition` publica cuatro videos H.264 y AAC reales por una conexión
Enhanced RTMPS. Verifica resoluciones recibidas, maestro, reproducción/cambio manual y automático,
continuidad de posición, sesión única y VOD con todas las calidades. El emisor de prueba codifica
antes de enviar; el servidor no transcodifica. Esta prueba de protocolo no se presenta como una
operación manual ejecutada en la UI de OBS.
