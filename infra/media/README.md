# Infraestructura multimedia

MediaMTX autogestionado para RTMP/Low-Latency HLS y adaptador técnico Rust, según
[ADR-005](../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md). En P1, [ADR-011](../../docs/adr/ADR-011-streaming-tres-contenedores-p1.md) integra el adaptador en el proceso de control Streaming. El stack usa tres contenedores: PostgreSQL, Streaming con adaptador y MediaMTX. La configuración de MediaMTX, su imagen fijada
por digest y el código del adaptador están en [Streaming y Media](../../services/streaming/README.md).

SPEC-04, SPEC-10 y SPEC-11 definen el ciclo de vida y los callbacks.
Media transporta y observa las fuentes; Streaming usa esas señales para decidir el estado de sesión.

## OBS y multicalidad P2

OBS 31 o posterior puede enviar varias pistas de video en una sola conexión Enhanced RTMP,
según la [guía oficial de MediaMTX](https://mediamtx.org/docs/publish/obs-studio).
En Ajustes → Emisión seleccionar servicio personalizado, servidor RTMPS y clave que entrega
la configuración del canal. Para el perfil local, confiar en la CA pública de la instalación
antes de emitir; no desactivar TLS ni copiar claves privadas.

Activar «Enable Multitrack Video» y «Enable Config Override»; pegar
[obs-multitrack.json](obs-multitrack.json) en el campo JSON. Dejar ancho de banda y número
máximo de pistas en Auto. El ejemplo requiere lienzo de al menos 1280×720, 25 fps y capacidad
para cuatro codificadores x264 en el equipo emisor. Puede reducirse a dos entradas; conservar
el mismo framerate e intervalo de keyframes en todas. No configurar una resolución mayor que
la fuente. Una fuente convencional sin multitrack sigue ofreciendo una sola calidad.

La adaptación fijada a MediaMTX 1.21.1 conserva autorización y sesión únicas, divide las pistas
H.264 recibidas en empaquetadores HLS independientes y comparte el AAC recibido. No transcodifica.
Los nombres públicos `rend_N__…` delimitan playlists, init y segmentos de cada calidad.
El grabador nativo guarda cada video con su audio y declara el inventario completo esperado
antes de escribir segmentos. Un inventario ausente impide certificar un VOD completo.
