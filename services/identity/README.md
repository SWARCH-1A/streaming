# Cuentas — autenticación y sesión

Autenticación, credenciales y sesión forman Cuentas junto con el perfil dentro de Core.
La definición es [SPEC-01](../../docs/spec-p1/spec_01_auth.md); plataforma y seguridad en
[ADR-001](../../docs/adr/ADR-001-identity-plataforma-y-seguridad.md).
Registro confirma cuenta, perfil y canal en una transacción Core. Los módulos Core validan sesión localmente.

## Fuentes ejecutables

Esta carpeta contiene la aplicación Identity existente, con Maven Wrapper, Java 25 y Spring Boot 4.1.1.
Se compila con `./mvnw -B package` y ejecuta con `./mvnw spring-boot:run`; puerto 8081,
health `/actuator/health`. La consolidación de estas fuentes en services/core corresponde a una tarea
de implementación; este build por separado no acredita los criterios de registro Core de SPEC-01.
Consulta application.yml para las variables de la aplicación existente. No usar su provisión remota
como contrato normativo del proyecto ni ejecutar migraciones contra bases ajenas a este build.
