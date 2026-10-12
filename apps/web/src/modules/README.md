# Módulos de interfaz

Accounts, channels, streaming, chat, taxonomy y discovery organizan las funciones de una misma
aplicación Web. Cada módulo publica `entry.tsx` y mantiene internos sus componentes, validación,
estado y datos de prueba. ESLint comprueba que otro módulo no importe esas partes internas.

El [shell](../shell/README.md) reúne las rutas y la sesión común. `src/components` contiene los
componentes compartidos y `src/styles`, los tokens visuales.

Las vistas funcionales usan los contratos de los servicios reales. Los fixtures se reservan
para pruebas y demos explícitas; no reemplazan una respuesta fallida de la API.
