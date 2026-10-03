# Aplicación Web

Una aplicación y un build. Código bajo `src/`: [shell](src/shell/README.md) compone rutas/layout;
[módulos](src/modules/README.md) aportan vistas y estado de sus funcionalidades;
[accessibility](src/accessibility/README.md) reúne utilidades accesibles compartidas.

`src/modules/accounts` contiene registro, login y perfil del mismo usuario. Los demás módulos son
channels, streaming, chat, taxonomy y discovery. Cada módulo publicará su entry/API de UI;
no se importan internals ajenos ni se crea una aplicación por módulo. Assets públicos y configuración
del build estarán fuera de src cuando el framework seleccionado lo requiera.

La Web todavía es un esqueleto documental. Framework, router, paquetes, entry y pruebas ejecutables
se concretan por ADR antes de implementarlos. Definición: [SPEC-12](../../docs/spec-p1/spec_12_int.md)
y [organización/proxy](../../docs/integracion_frontend_reverse_proxy.md).
