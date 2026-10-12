# Interfaz de chat

`LiveChatPanel` consume el historial REST y el WebSocket de Chat mediante el proxy.
Abre WebSocket antes del historial, combina mensajes por secuencia y conserva el identificador
al reintentar un envío. Los errores de Chat quedan separados del reproductor.

## Organización

La entrada pública es `entry.tsx`. Los consumidores usan esa entrada y los contratos del módulo;
sus componentes y estado interno permanecen encapsulados. El [shell](../../shell/README.md)
organiza las rutas globales y el layout.

La [SPEC](../../../../../docs/spec-p1/spec_05_chat.md) define el comportamiento.
[SPEC-08](../../../../../docs/spec-p1/spec_08_a11y.md) fija los criterios de accesibilidad
de sus recorridos. Los comandos están en el [README Web](../../../README.md),
y las pruebas locales se mantienen junto al código.
