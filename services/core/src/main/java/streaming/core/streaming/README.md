# Frontera con Streaming

El control de emisiones pertenece al servicio Rust en [services/streaming](../../../../../../../streaming/README.md), según [ADR-005](../../../../../../../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md). Core conserva Cuentas, Canales, Catálogo y Discovery.

Están pendientes los endpoints de contexto owner/catálogo, la proyección pública/inbox Discovery y la consulta de sesión/timeline Streaming para el contexto Chat. Los contratos canónicos están en [contratos](../../../../../../../../docs/contratos_modelo_datos.md); no existe otro escritor de emisiones en Core.
