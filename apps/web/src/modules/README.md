# Módulos de UI

Cada carpeta agrupa una funcionalidad de la única Web. Cuentas reúne autenticación y perfil en
accounts; canales, player/emisión, chat, catálogo y búsqueda conservan su ámbito.

Cada módulo publica una entrada de UI; sus componentes, estado y clientes específicos permanecen
internos. [Shell](../shell/README.md) compone rutas/layout y sesión común. Cliente HTTP/CSRF y estilos
compartidos se ubicarán según el ADR Web; no anticipar stores globales o paquetes vacíos.
