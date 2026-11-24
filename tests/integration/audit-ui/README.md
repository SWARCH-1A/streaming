# Consulta Web de auditoría a través del WAF

Desde la raíz, con Docker, OpenSSL y Python 3.12+:

```sh
python3 tests/integration/audit-ui/run.py
# Solo si todas las imágenes corresponden al checkout actual:
python3 tests/integration/audit-ui/run.py --skip-build
# Para observar la UI con las cuentas ficticias del escenario:
python3 tests/integration/audit-ui/run.py --skip-build --hold
```

El runner inicializa un proyecto aleatorio con el Compose completo actual, incluido WAF,
TLS, broker y Auditor. Usa certificados, credenciales, puertos loopback y volúmenes nuevos;
no modifica la instalación local. Construye las imágenes de runtime con el checkout actual.
El cliente de captura Python 3.13 conserva verificación de cadena/hostname y usa la política
de CA académica existente, sin las extensiones adicionales exigidas por VERIFY_X509_STRICT.

Registra tres cuentas ficticias y provisiona ADMIN explícitamente mediante SQL solo en esa
instalación. Crea metadatos de captura por la API privada real de VOD (sin escribir sus tablas),
cinco ediciones ajenas denegadas, una edición del propietario y accesos administrativos Core
suficientes para dos páginas. No genera video ni acredita reproducción.

Comprueba por la entrada WAF: consulta 401/403, páginas de 50 sin posiciones repetidas,
filtros de servicio/resultado/recurso y correlación por requestId. `--hold` escribe credenciales
ficticias en el archivo privado `browser.json` cuyo camino muestra; no imprimir ni versionar
su contenido. Conserva el entorno hasta crear el archivo `release` indicado, durante un máximo
de 30 minutos. Después elimina únicamente sus contenedores/redes/volúmenes.

Recorrido de navegador con una sesión aislada y la CA/certificado del fixture:

1. Visitante: abrir `/admin/audit` redirige al login; entrar con `admin` y `password` de la fixture.
2. Administración enlaza a Auditoría; la primera consulta muestra hasta 50 eventos de las últimas
   24 horas. Siguiente/Anterior conserva el intervalo; cambiar filtros reinicia la página.
3. Elegir VOD, Denegado y el `vodId` ficticio: aparecen los cinco intentos. Buscar por el
   `requestId` de la fixture devuelve su evento. Abrir detalle y cerrar devuelve el foco al botón.
4. Verificar fechas personalizadas, validación de intervalo y resultado vacío. Comprobar
   320/390/768/1440 px, scroll de tabla mediante teclado y ausencia de desbordamiento de página.
5. Detener solo `audit` del fixture y repetir consulta: muestra fallo/reintento, sin una lista vacía
   engañosa. Arrancarlo y reintentar recupera los resultados. El usuario `viewer` ve falta de permisos.
6. Guardar capturas fuera de Git, cerrar el navegador y crear el archivo `release`.

Las unitarias verifican filtros sin consulta por pulsación, conversión UTC, cursor/fechas,
reinicio de página, detalle/foco, error/vacío, cancelación e ignorar respuestas tardías y retirada
de resultados ante un 403 posterior. Se ejecutan con el check habitual de Web:

```sh
cd apps/web
pnpm check
```

Esta aceptación usa pocos eventos; no demuestra tiempos de consulta con millones de registros.
La suite también envía un ataque a través del WAF y comprueba que su bloqueo aparece una vez como
productor `waf`, sin actor, con el `requestId` del 403 y solo para ADMIN. La vista no recopila logs
técnicos del WAF (IP, fragmento coincidente).
