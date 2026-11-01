# ADR-023: Agregación Gateway en Go y transporte Caddy

- Estado: aceptada
- Fecha: 2026-10-29
- Responsable: Integración / Gateway
- SPEC/contratos: SPEC-16, SPEC-18, SPEC-14, P2-06 y P2-17

## Contexto y decisión

Se conserva Caddy como entrada TLS, proxy HTTP/WS/HLS y frontera de headers privados. Se añade
`services/gateway`, Go 1.26 con HTTP estándar, para agregados de canal, watch, VOD y exploración y
para reenviar retiradas VOD. No tiene BD ni reglas de dominio; Core y VOD autorizan las operaciones.

Las consultas independientes son concurrentes, con presupuesto total de tres segundos, cancelación
y cuerpos acotados. Reenvía identidad durante una sola solicitud y propaga requestId. Secciones
UNAVAILABLE no se convierten en listas vacías; DENIED anónimo no implica caída. El recurso esencial
inexistente conserva 404, la identidad inválida 401 y la dependencia caída 503/504. Respuestas de
pantalla usan `no-store, private`. Discovery utiliza la consulta GraphQL canónica generada.

La dirección del cliente se reconstruye en dos saltos: Caddy sustituye X-Forwarded-For por el par
observado y el Gateway la reenvía solo si su par está en `GATEWAY_TRUSTED_PROXIES`. Ambos tienen IP
fija en la red local y Core confía únicamente en ellas; sin esto, todas las pantallas compartirían
la cuota por IP del Gateway.

## Alternativas y consecuencias

Componer todo en el navegador duplica llamadas y deja SSR sin los mismos fallos parciales. Caddy
solo no compone datos. Un framework adicional no es necesario para estos agregados acotados.
El transporte HLS conserva streaming y Range fuera del agregado JSON; no se lee el video completo
para componer una pantalla. Los comandos mantienen idempotencia y no se reintentan automáticamente.

Están implementados los cuatro agregados de `contracts/p2.json`. Siguen pendientes la adopción del
agregado watch por SSR y móvil, la carga sostenida y las retiradas no VOD. La clasificación docente
de la sexta unidad lógica sigue en P2-18; elegir tecnología no acredita ese criterio.

Pruebas: `services/gateway/main_test.go`, el [borde Caddy](../../tests/integration/p2-gateway-edge/run.py)
y el [stack local](../../tests/integration/p2-gateway/run.py), según SPEC-16 §10; regresión SSR en Web.
Configuración: [Gateway](../../services/gateway/README.md).
