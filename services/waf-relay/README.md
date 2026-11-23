# waf-relay

Publica los bloqueos del WAF como evidencia de seguridad para el Security Logger y Auditor
([ADR-032](../../docs/adr/ADR-032-evidencia-waf-auditor.md), [SPEC-25](../../docs/spec-p3/spec_25_security_audit.md)).

ModSecurity escribe un archivo JSON por transacción en el volumen `waf-audit` (modo `Concurrent`). El
relay recorre ese spool cada 500 ms y, por cada transacción interrumpida con 403, publica un evento
`security.audit.waf.v1` en `p2.events` (persistente, `mandatory`, con confirmación). Solo borra el
archivo después de la confirmación del broker: si el broker no está, el bloqueo espera en el spool.

El evento no lleva actor, IP, cabeceras, consulta ni el fragmento que coincidió:

- `targetId`: los dos primeros segmentos de la ruta, normalizados (`api_taxonomy`).
- `reasonCode`: la familia CRS del ataque (`SQL_INJECTION`, `XSS`, `PATH_TRAVERSAL`…).
- `correlationId`: el `requestId` de la respuesta 403.
- `eventId`: derivado de ese `requestId`, así que un reintento produce el mismo evento.

Las transacciones que no son bloqueos se borran sin publicar. Un archivo que no se puede leer como
JSON se reintenta y se descarta al minuto. Si el spool supera `WAF_RELAY_MAX_BACKLOG` archivos, se
descartan los más antiguos con un aviso en el log; el tope se aplica también mientras el broker no
está disponible, en cada reintento de conexión. Por cada bloqueo publicado, el log registra el
`requestId` y los IDs de las reglas, para ajustar exclusiones.

## Configuración

| Variable | Uso |
| --- | --- |
| `WAF_RELAY_BROKER_URL` | `amqps://waf:…@broker:5671/streaming-p2`; el usuario `waf` solo publica su routing key. Nunca se escribe en el log. |
| `WAF_RELAY_BROKER_CA_FILE` | CA con la que se verifica el certificado del broker. |
| `WAF_RELAY_SPOOL` | Directorio del spool (por defecto `/spool`). |
| `WAF_RELAY_MAX_BACKLOG` | Máximo de archivos pendientes (por defecto 5000). |

En local lo levanta `infra/audit/compose.yaml` con `python3 infra/local/dev.py up`: sin puertos, raíz
de solo lectura, uid 101 (el del WAF) y como única red `waf-messaging`, compartida solo con el broker.
`waf-relay health` comprueba que el bucle sigue vivo.

## Pruebas

```sh
cd services/waf-relay && go vet ./... && go test ./...
```

El recorrido completo (ataque real → consulta ADMIN) está en `tests/integration/audit-ui`.
