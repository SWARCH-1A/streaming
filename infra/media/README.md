# Infraestructura multimedia

MediaMTX autogestionado para RTMP/Low-Latency HLS y adaptador técnico Rust, según
[ADR-005](../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md). Motor, adaptador y servicio
de control se despliegan en contenedores separados. La configuración de MediaMTX, su imagen fijada
por digest y el adaptador están en [Streaming y Media](../../services/streaming/README.md).

El ciclo de vida y los callbacks pertenecen a los contratos de SPEC-04, SPEC-10 y SPEC-11.
Streaming conserva la autoridad del estado de sesión; Media transporta y observa las fuentes.
