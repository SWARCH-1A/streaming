# WAF de la entrada pública

El contenedor `waf` del Compose raíz es la entrada pública de la instalación local
([ADR-031](../../docs/adr/ADR-031-waf-modsecurity-crs.md)). Usa la imagen oficial del proyecto
OWASP CRS (`owasp/modsecurity-crs`, nginx Alpine, rama LTS 4.25, fijada por digest), con
ModSecurity v3 y el OWASP Core Rule Set en modo bloqueo.

```
Cliente ──HTTPS :3445──► waf (nginx + ModSecurity + CRS) ──HTTPS──► proxy (Caddy) ──HTTPS──► servicios
                         └────────── red edge ──────────┘           └──── red default ────┘
```

El WAF solo pertenece a la red `edge`, que comparte con Caddy ([ADR-030](../../docs/adr/ADR-030-segmentacion-red.md)):
no puede abrir conexiones a ningún servicio ni almacén sin pasar por el proxy.

| Archivo | Contenido |
| --- | --- |
| `default.conf.template` | Servidor nginx: TLS público, proxy cifrado hacia Caddy (CA y nombre verificados), límites de tiempo, tamaño, peticiones y conexiones, errores JSON y healthcheck en loopback. Sustituye la plantilla de la imagen. |
| `rules/REQUEST-900-EXCLUSION-RULES-BEFORE-CRS.conf` | Reglas propias que se cargan antes del CRS. |
| `rules/RESPONSE-999-EXCLUSION-RULES-AFTER-CRS.conf` | Exclusiones que se cargan después del CRS (por ahora, los campos de contraseña). |

La configuración de ModSecurity y del CRS (modo, paranoia, umbrales, métodos y límites) se pasa
por variables de entorno en `compose.yaml`; la imagen las aplica al arrancar.

## Operación

El WAF se levanta con el resto del sistema (`python3 infra/local/dev.py up`). Tras cambiar
`default.conf.template` o las reglas hay que recrear el contenedor, porque la plantilla se procesa al
arrancar:

    docker compose --env-file infra/p1/.state-local/environment.env -p streaming-local -f compose.yaml \
      -f infra/broker/compose.yaml -f infra/audit/compose.yaml up -d --force-recreate --no-deps waf
    docker exec streaming-local-waf-1 nginx -t

## Investigar un 403

Una petición bloqueada recibe `{"code":"REQUEST_BLOCKED", …, "requestId": "<id>"}`. Cada bloqueo
aparece también en `/admin/audit` (filtro *Servicio → WAF*), con hora, ruta, tipo de ataque y ese
`requestId`, sin usuario ni IP ([ADR-032](../../docs/adr/ADR-032-evidencia-waf-auditor.md)).

ModSecurity escribe un archivo JSON por transacción en el volumen `waf-audit` (modo `Concurrent`);
`waf-relay` publica los bloqueos al broker y borra el archivo tras la confirmación. Para ver las reglas
exactas de un bloqueo (sin el fragmento que coincidió):

    docker logs streaming-local-waf-relay-1 2>&1 | grep '<id>'

El log de errores del WAF también nombra las reglas, la ruta del archivo de auditoría y la IP del
cliente; los mensajes pueden incluir el fragmento que coincidió, así que hay que revisarlos antes de
compartirlos:

    docker logs streaming-local-waf-1 2>&1 | grep '<id>'

El log de acceso guarda método, ruta sin consulta, estado y duración. El registro de auditoría solo
guarda la cabecera, las reglas y el cierre (partes A, H y Z): ni cabeceras, ni cuerpos, ni cookies.

Una petición cuya ruta sube por encima de la raíz (`/a/../../x`) la rechaza nginx antes de
ModSecurity con `400 BAD_REQUEST`; no cuenta como bloqueo del WAF.

## Añadir una exclusión

Solo para un falso positivo reproducido: una petición legítima de la aplicación que recibe 403.
Excluir el campo y la regla concretos, nunca una ruta o una regla completa, por ejemplo:

    SecRuleUpdateTargetById 942100 "!ARGS:json.description"

El ID de la regla y el nombre del campo salen del log de auditoría. Documentar el motivo junto a la
exclusión, recrear el contenedor y repetir la prueba que falló.

## Comprobación rápida

Con la CA del estado local (`infra/p1/.state-local/ca.crt`):

    curl -s --cacert <ca.crt> -o /dev/null -w '%{http_code}\n' https://localhost:3445/api/taxonomy             # 200
    curl -s --cacert <ca.crt> -o /dev/null -w '%{http_code}\n' "https://localhost:3445/api/taxonomy?q=%27%20OR%201%3D1--"  # 403
