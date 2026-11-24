# ADR-031: Firewall de aplicaciones web (ModSecurity v3 + OWASP CRS) en la entrada pública

- Estado: aceptada para integración local
- Fecha: 2026-11-22
- Responsable: Gateway / integración
- SDD/contratos afectados: SPEC-16 P2, SPEC-24 P2; RNF-025, RNF-027, RNF-030, RNF-031. Sin cambios en
  contratos HTTP, de eventos ni de Media: el WAF solo añade respuestas de rechazo propias (400, 403, 405,
  413, 429, 503) con la forma de error común `{code, message, requestId}`.

## Contexto

Caddy era la única entrada pública: terminaba TLS, enrutaba por lista blanca de rutas, bloqueaba
`/internal`, `/actuator` y las rutas de salud, y eliminaba las cabeceras de identidad que enviaba el
cliente. Cada dominio valida sus entradas (RNF-030) y autoriza sus operaciones (RNF-027).

Ningún componente inspeccionaba el contenido de las peticiones antes de que llegaran a los servicios.
Un intento de inyección SQL, XSS, *path traversal*, ejecución remota de comandos o un escáner
automático llegaba al servicio de destino, y su rechazo dependía solo del código de ese servicio. Un
fallo de validación en cualquiera de los cinco runtimes (Java, Rust, Go, TypeScript y el SSR) quedaba
expuesto sin otra barrera.

## Decisión

1. **Componente propio en la entrada.** Un contenedor `waf` con la imagen oficial del proyecto OWASP CRS
   (`owasp/modsecurity-crs`, variante nginx Alpine, rama LTS 4.25), fijada por etiqueta y digest, se
   coloca delante de Caddy. Publica el puerto 3445 (antes publicado por Caddy), termina el TLS público con
   el certificado `web.crt` y vuelve a cifrar hacia `proxy:3443`, verificando la CA del proyecto y el
   nombre `proxy`. Caddy deja de publicar puertos y conserva el enrutamiento.
2. **Modo bloqueo con las reglas genéricas.** ModSecurity v3 aplica el OWASP Core Rule Set con
   `SecRuleEngine On`, nivel de paranoia 1 y umbrales de anomalía 5 (entrada) y 4 (salida), los valores
   recomendados por el CRS. Cubre inyección SQL, XSS, LFI/RFI, RCE, inyección PHP/Java, fijación de sesión,
   escáneres y violaciones de protocolo, además de fugas de datos en las respuestas HTML.
3. **Ajustes propios de la aplicación.** Se permiten los métodos que usan las APIs (GET, HEAD, POST,
   OPTIONS, PUT, PATCH, DELETE). Los límites de tamaño reflejan los de Core: archivos de 10 MB,
   11 MB por petición, 12 MB de cuerpo y 128 KiB para cuerpos sin archivos. Las contraseñas (`password`,
   `newPassword`) quedan fuera de la inspección, porque se cifran con Argon2id y nunca se interpretan.
   Cualquier otra exclusión debe nombrar la regla y el campo concretos, con un falso positivo verificado
   (`infra/waf/rules`).
4. **Protecciones de nginx.** HTTPS solo con TLS 1.2 y 1.3, con HTTP/2 para los navegadores como
   ofrecía Caddy; se retira el `Alt-Svc` de HTTP/3 que Caddy anuncia en su puerto interno; límites de tiempo de cabeceras y cuerpo
   contra clientes lentos (slowloris); límite de peticiones (100/s, ráfaga de 400) y de conexiones
   (512) por dirección; sin cabecera `Server`; healthcheck solo en loopback, fuera del puerto público.
5. **La dirección del cliente la fija el WAF.** El WAF escribe `X-Forwarded-For` con la dirección de la
   conexión y descarta la que envíe el cliente. Caddy confía solo en la IP fija del WAF
   (`LOCAL_WAF_IP`, `trusted_proxies_strict`) para leerla y la propaga a los servicios, que siguen
   confiando en Caddy y el Gateway. Así las cuotas por IP de Core siguen siendo por cliente.
6. **Zona de borde propia.** Con la segmentación de [ADR-030](ADR-030-segmentacion-red.md), el WAF
   solo pertenece a la red `edge`, que comparte únicamente con Caddy. Tiene subred propia
   (`LOCAL_EDGE_SUBNET`) e IP fijas para el WAF (`.2`) y el proxy (`.3`). Un WAF comprometido no alcanza
   Core, Chat, Streaming, el broker ni ningún almacén sin pasar por Caddy.
7. **Registro sin datos sensibles.** El log de acceso guarda método, ruta sin consulta, estado y
   duración. El log de auditoría de ModSecurity guarda solo las partes A, H y Z (cabecera, reglas que
   coincidieron y cierre), nunca cabeceras ni cuerpos, así que no guarda cookies, tokens ni contraseñas.
   Los archivos subidos no se guardan en disco.

La decisión aplica a la instalación raíz (`compose.yaml`, gestionada por `infra/local/dev.py`).

## Opciones consideradas

| Opción | Ajuste | Esfuerzo y riesgo |
| --- | --- | --- |
| **A. ModSecurity v3 + CRS con nginx, componente delante de Caddy (elegida)** | Motor WAF de referencia con el conjunto de reglas OWASP; el WAF aparece como componente propio de la entrada | Medio: un contenedor, otro salto TLS y la cadena de confianza de `X-Forwarded-For`. La imagen oficial se configura por variables |
| B. Coraza + CRS como módulo de Caddy | Mismas reglas y lenguaje, dentro del proxy existente | Bajo, pero exige compilar una imagen propia de Caddy con `xcaddy`, y el WAF no queda como componente separado |
| C. BunkerWeb o SafeLine | WAF completos con panel, antibots y listas | Alto: sustituyen funciones de Caddy y añaden servicios y bases propias |
| D. Sin WAF | Ninguno | Cada servicio queda como única barrera ante ataques genéricos |

## Consecuencias

- **Seguridad.** Los ataques genéricos se rechazan con 403 antes de llegar a los servicios, y cada
  rechazo queda en el log de auditoría con la regla que lo produjo. Es una defensa adicional: la
  validación y la autorización siguen en cada dominio.
- **Canal seguro.** El tráfico sigue cifrado en todo el trayecto (cliente → WAF → Caddy → servicio). HSTS
  y las demás cabeceras de seguridad las sigue añadiendo Caddy.
- **Rendimiento.** Hay un salto TLS más y la inspección de cada petición. HLS y el WebSocket de Chat
  pasan sin almacenamiento intermedio. El WAF no ve los mensajes del WebSocket después del *upgrade*.
- **Falsos positivos.** Un texto legítimo puede parecerse a un ataque y recibir 403. La respuesta lleva
  `requestId`, que permite encontrar la regla en el log y añadir una exclusión acotada.
- **Ráfagas de un cliente.** Un cliente que supera 100 peticiones/s sostenidas recibe 429 hasta
  que baja su ritmo, aunque las peticiones sean legítimas. Un bucle de navegación en la Web lo
  convierte en un fallo visible, en lugar de quedar oculto.
- **Límites por dirección.** Con Docker Desktop, todo el tráfico del equipo anfitrión llega al
  contenedor desde la misma dirección NAT, así que en local los límites de peticiones y conexiones se
  comportan como límites globales. En un despliegue real se aplican por cliente.
- **Operación.** `python3 infra/local/dev.py init` elige una subred libre para `edge` y añade
  `LOCAL_EDGE_SUBNET`, `LOCAL_WAF_IP` y `LOCAL_EDGE_PROXY_IP` a las instalaciones existentes sin rotar
  secretos; `up` regenera la Caddyfile. Una restauración elige subredes propias para su copia, y un
  backup anterior a este ADR se restaura con la Caddyfile actual. El perfil P1 (`infra/p1`, perfiles `default` y `load`) no tiene WAF y conserva su
  comportamiento.
- **Fuera del alcance.** RTMPS (11938) va directo a MediaMTX y no es HTTP. Mailpit (8025) es una
  herramienta de desarrollo y no pasa por el WAF.

## Verificación

Con la instalación raíz arriba:

1. Tráfico normal por `https://localhost:3445`: páginas SSR, APIs, GraphQL, WebSocket de Chat, HLS y
   subida de archivos responden igual que sin WAF.
2. Ataques en la consulta, en cabeceras y en cuerpos JSON (inyección SQL, XSS, *path traversal*, RCE,
   `${jndi:…}`, `/.env`, `/.git`, User-Agent de escáner, `Content-Type` no permitido) reciben 403
   `REQUEST_BLOCKED`. Un cuerpo de más de 12 MB recibe 413.
3. Core registra la dirección del cliente y no la del WAF. Una `X-Forwarded-For` falsificada por el
   cliente, o enviada directamente a Caddy desde otro contenedor, se ignora.
4. TLS 1.1 y HTTP plano se rechazan; `/healthz` no es accesible desde fuera.
5. Las suites de integración pasan a través del WAF sin bloqueos de tráfico legítimo.
6. `tests/integration/network-segmentation/run.py` comprueba que el WAF llega a Caddy y que no
   alcanza ningún otro servicio, ni por DNS ni por IP.

## Revisión

Revisar esta decisión si aparecen falsos positivos recurrentes, si se sube el nivel de paranoia, si el
sistema se despliega con un dominio y clientes reales (límites por cliente y certificado público), o si
se decide integrar el WAF en el proxy (opción B). La revisa la persona responsable del Gateway con los
responsables de los dominios afectados por cada exclusión.
