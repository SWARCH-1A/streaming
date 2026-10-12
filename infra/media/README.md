# Infraestructura multimedia

MediaMTX autogestionado para RTMP/Low-Latency HLS y adaptador técnico Rust, según
[ADR-005](../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md). En P1, [ADR-011](../../docs/adr/ADR-011-streaming-tres-contenedores-p1.md) integra el adaptador en el proceso de control Streaming. El stack usa tres contenedores: PostgreSQL, Streaming con adaptador y MediaMTX. La configuración de MediaMTX, su imagen fijada
por digest y el código del adaptador están en [Streaming y Media](../../services/streaming/README.md).

SPEC-04, SPEC-10 y SPEC-11 definen el ciclo de vida y los callbacks.
Media transporta y observa las fuentes; Streaming usa esas señales para decidir el estado de sesión.
