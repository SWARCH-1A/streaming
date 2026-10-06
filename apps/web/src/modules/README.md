# Módulos de UI

Una aplicación y un build. Accounts, channels, streaming, chat, taxonomy y discovery agrupan
responsabilidades de la Web. Cada módulo publica `entry.tsx`; sus componentes, validación,
estado y fixtures permanecen internos. ESLint bloquea imports de internals ajenos.

[Shell](../shell/README.md) compone rutas y sesión de demostración; `src/components` contiene
primitivas atómicas y `src/styles` sus tokens comunes. Los fixtures actuales no son contratos de
servicio. La futura integración usará clientes por módulo conforme a contratos existentes.
