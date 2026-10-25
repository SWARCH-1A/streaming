# Administración con proveedores reales

Requiere Docker, Python, Node compatible con Web, pnpm 11.17.0 y Chromium instalado por
`pnpm --dir apps/web exec playwright install chromium`. Desde la raíz del repositorio:

```sh
# Java 25; Docker debe estar disponible para las pruebas de Core.
cd services/core
./mvnw -P integration verify
cd ../..
pnpm --dir apps/web build
python tests/integration/administration/run.py
```

El runner rechaza artefactos más antiguos que sus fuentes. Levanta PostgreSQL, el jar Core
y Caddy con el build real de Web. Usa nombres aleatorios, ningún volumen de la aplicación,
puerto público aleatorio en loopback y HTTP exclusivamente en esta fixture aislada.
Genera secretos efímeros; elimina sus contenedores/red al terminar, incluso ante error.

Chromium registra cuentas ficticias, provisiona el rol mediante el script operativo y prueba
login, permisos, CSRF, suspensión/reactivación, revocación, idempotencia, conflicto concurrente,
formulario/foco, anchos de pantalla y Axe. No acredita TLS de despliegue ni revocación de medios
en Streaming/Chat/VOD, ni sustituye pruebas humanas con lector de pantalla.
