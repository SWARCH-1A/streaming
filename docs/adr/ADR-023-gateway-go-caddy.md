# ADR-023: Agregación Gateway en Go y transporte Caddy

- Estado: aceptada para la integración VOD
- Fecha: 2026-10-29
- Responsable: Integración / Gateway
- SPEC/contratos: SPEC-16, SPEC-18, SPEC-14, P2-06 y P2-17

## Contexto y decisión

Se conserva Caddy como entrada TLS, proxy HTTP/WS/HLS y frontera de headers privados. Se añade
`services/gateway`, Go 1.26 con HTTP estándar, para agregados de canal, VOD y exploración y para
reenviar retiradas VOD. No tiene BD ni reglas de dominio; Core y VOD autorizan las operaciones.

Las consultas independientes son concurrentes, con presupuesto total de tres segundos, cancelación
y cuerpos acotados. Reenvía identidad durante una sola solicitud y propaga requestId. Secciones
UNAVAILABLE no se convierten en listas vacías; DENIED anónimo no implica caída. El recurso esencial
inexistente conserva 404, la identidad inválida 401 y la dependencia caída 503/504. Respuestas de
pantalla usan `no-store, private`. Discovery utiliza la consulta GraphQL canónica generada.

## Alternativas y consecuencias

Componer todo en el navegador duplica llamadas y deja SSR sin los mismos fallos parciales. Caddy
solo no compone datos. Un framework adicional no es necesario para estos agregados acotados.
El transporte HLS conserva streaming y Range fuera del agregado JSON; no se lee el video completo
para componer una pantalla. Los comandos mantienen idempotencia y no se reintentan automáticamente.

Esta entrega cubre los agregados correspondientes a VOD. El agregado live y la aceptación completa
WS/HLS/carga/móvil de SPEC-16 siguen pendientes. La clasificación docente de la sexta unidad lógica
sigue en P2-18; elegir tecnología no acredita ese criterio.

Pruebas: fallos parciales de canal, aislamiento de dos identidades concurrentes y credencial revocada
en `services/gateway/main_test.go`; regresión SSR en Web. Configuración: [Gateway](../../services/gateway/README.md).
