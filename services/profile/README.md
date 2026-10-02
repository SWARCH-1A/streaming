# Cuentas — perfil público

Nombre visible, bio y avatar forman Cuentas junto con autenticación dentro de Core.
La definición es [SPEC-01](../../docs/spec-p1/spec_01_auth.md); almacenamiento y ciclo de archivos en
[ADR-002](../../docs/adr/ADR-002-profile-persistencia-y-avatar.md).
Perfil inicial se crea en el registro; validación de sesión y consultas públicas son locales a Core.

## Fuentes ejecutables

Esta carpeta contiene la aplicación Profile existente, con Maven Wrapper, Java 25 y Spring Boot 4.1.1.
Se compila con `./mvnw -B package` y ejecuta con `./mvnw spring-boot:run`; puerto 8082,
health `/actuator/health`, PostgreSQL y volumen de imágenes persistente. Consulta application.yml
para sus variables. Su consolidación en services/core corresponde a una tarea de implementación;
este build separado no acredita los criterios Core de SPEC-01. Conservar respaldos de SQL y objetos
antes de migrar; no combinar migraciones V1 de dos aplicaciones sobre una misma historia Flyway.
