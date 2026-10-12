# Frontera con Streaming

El control de emisiones está en el [servicio Rust](../../../../../../../streaming/README.md),
según [ADR-005](../../../../../../../../docs/adr/ADR-005-streaming-rust-y-proyeccion-discovery.md).
Core conserva Cuentas, Canales, Catálogo y Discovery.

Core publica los contextos privados de propietario y catálogo. Para Chat, compone la identidad
y el perfil locales con el estado y la línea temporal actual que consulta a Streaming.
Canales usa un batch de snapshots Streaming para su página pública. Discovery recibe snapshots
versionados en su inbox y mantiene la proyección SQL de consulta.

Los [contratos](../../../../../../../../docs/contratos_modelo_datos.md) definen esas comunicaciones.
Streaming es el único escritor del estado de emisiones; Core consume sus datos públicos.
