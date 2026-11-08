# Laboratorio 5 Canal seguro en Vault tv

Este laboratorio demuestra que el inicio de sesión real de Vault tv expone sus datos cuando el conector **cliente de prueba → Core** utiliza HTTP. El Grupo 1 prepara ese contraejemplo y obtiene una captura de paquetes. Los grupos 2 y 3 utilizarán el mismo recorrido para configurar TLS y comprobar su protección.

El perfil HTTP es deliberadamente inseguro y exclusivo del laboratorio. No es una configuración de entrega del producto. No se modifican los defaults de Core, la autenticación, CSRF ni el hashing de contraseñas.

## 1 Alcance y aislamiento

La base de trabajo es `develop` en el commit `1d8c562a55cab419d8c95657c399932d5ddc01ce`. La rama exclusiva es `feature/lab-05-secure-channel-g1` y utiliza un worktree separado. No se debe ejecutar este perfil desde la carpeta del despliegue personal ni incorporar el perfil inseguro mediante un PR hacia `develop`.

| Pieza | Función en el ensayo |
| --- | --- |
| Core | APIs reales de registro, sesión y perfil. |
| PostgreSQL | Cuenta ficticia y datos exclusivos del laboratorio. |
| Cliente | Ejecuta solicitudes reproducibles mediante `curl`. |
| Observador | Captura con `tcpdump`; `tshark` reconstruye y verifica las conversaciones. |

El helper utiliza un nombre de proyecto Compose generado, una red y volúmenes propios. Core se publica exclusivamente en `127.0.0.1:18081`; PostgreSQL no publica un puerto del equipo. El despliegue normal de Vault tv no interviene: no se utiliza Caddy ni el API Gateway en este conector.

El observador comparte el espacio de red de Core y filtra TCP `8081`. Se eliminan las capacidades por defecto: conserva `NET_RAW` para capturar sin modo promiscuo y `DAC_OVERRIDE` para escribir la evidencia en el directorio privado montado desde Linux. No usa `NET_ADMIN`, modo privilegiado ni la red del host. La prueba supone acceso autorizado al trayecto del tráfico. Estar conectado a la misma red Wi-Fi no permite, por sí solo, leer las conversaciones unicast de otros equipos.

## 2 Herramientas y carpeta de trabajo

Se necesitan Git, Docker Desktop con contenedores Linux y Docker Compose, Python 3.10 o posterior y Wireshark para la inspección visual. En Ubuntu/WSL se usa `python3`; en PowerShell, `python` o el ejecutable Python configurado en el equipo. No se necesitan un IDE, Java instalado en Windows ni herramientas de captura instaladas dentro de Ubuntu: se construyen en las imágenes del laboratorio.

Abre Docker Desktop y espera a que su motor esté disponible. Si ejecutas desde Ubuntu, habilita su integración en Docker Desktop.

En este equipo, abre PowerShell y entra a la raíz del worktree:

```powershell
cd "C:\Users\Asus ROG\.codex\visualizations\2026\10\03\01a102bb-4781-7a81-9028-c5056c480029\lab-05-secure-channel-g1"
git branch --show-current
python --version
docker compose version
docker info
```

La rama debe ser `feature/lab-05-secure-channel-g1`; `docker info` debe comunicarse con el motor. En otro equipo se utilizará la raíz del checkout dedicado de esa rama. Comprueba que exista `labs/secure-channel/lab.py` antes de continuar.

Si prefieres Ubuntu, entra a la misma raíz mediante su ruta `/mnt/c/...` y sustituye `python` por `python3` en todos los comandos siguientes. Ejecuta el recorrido completo desde una misma terminal para evitar confundir carpetas y estados.

## 3 Inicializar construir e iniciar

Desde la raíz del worktree:

```powershell
python labs/secure-channel/lab.py init
python labs/secure-channel/lab.py build
python labs/secure-channel/lab.py up --mode http
python labs/secure-channel/lab.py status
```

| Comando | Resultado esperado |
| --- | --- |
| `init` | Genera proyecto, secretos y cuenta ficticia en `.state/`. Repetirlo conserva el estado existente. |
| `build` | Construye Core con su Dockerfile existente y la imagen de herramientas del ensayo. |
| `up --mode http` | Inicia la base y Core; espera la respuesta de salud `UP`. |
| `status` | Muestra los contenedores propios del laboratorio. |

La dirección local es `http://localhost:18081`. Core ofrece APIs: no se despliega aquí la Web de Vault tv. El cliente del ensayo habla con `http://core-lab:8081` dentro de la red Docker. Por eso el puerto visible en el PCAP es **8081**, aunque Windows accede mediante **18081**.

En el perfil HTTP no se genera ni se instala un certificado de confianza. La preparación del certificado corresponde al Grupo 2 para el perfil HTTPS.

Si el arranque falla, no continúes suponiendo que Core está listo:

```powershell
python labs/secure-channel/lab.py logs
```

## 4 Crear la cuenta ficticia

```powershell
python labs/secure-channel/lab.py seed
```

El helper llama a `POST /api/identity/registrations` con correo del dominio reservado `example.test`, handle y contraseña ficticios. La contraseña contiene el marcador `LAB5_CANAL_INSEGURO`, seguido de una parte aleatoria. Se conserva el UUID de idempotencia y el mismo cuerpo para que repetir `seed` no cree otra cuenta.

El resultado esperado es registro `201`. La cuenta y sus IDs quedan guardados exclusivamente en `.state/`. El registro se realiza **antes de iniciar la captura principal** para que la evidencia se concentre en autenticación y consulta del perfil.

No publiques `.state/credentials.json`, cookies, cabeceras, PCAP, claves o archivos de configuración privada. Aunque todos los datos sean ficticios, una sesión del ensayo sigue siendo una credencial durante su vigencia. `.state/` está ignorado por Git.

## 5 Capturar el recorrido real

```powershell
python labs/secure-channel/lab.py capture --mode http
python labs/secure-channel/lab.py verify --mode http
```

`capture` espera a que `tcpdump` esté escuchando, ejecuta el cliente y detiene la captura de forma controlada. El cliente realiza:

1. `GET /api/identity/csrf`: obtiene token y cookie CSRF.
2. `POST /api/identity/sessions`: envía JSON con **`login` y `password`**, la cookie y la cabecera `X-XSRF-TOKEN`.
3. Conserva la cookie de sesión `stream_session` obtenida en el login.
4. `GET /api/profile/me`: utiliza esa sesión para consultar el perfil.

Se esperan respuestas `200` y el mismo `userId` que produjo el registro. El script no imprime la contraseña ni la cookie en la terminal.

`verify` comprueba la captura TCP reconstruida, las credenciales ficticias exactas, el éxito del login, la sesión utilizada por la consulta de perfil y la identidad de sus respuestas. Un marcador aislado o un `200` de salud no bastan para aprobar el ensayo.

### Archivos locales producidos

Todos se guardan bajo `labs/secure-channel/.state/`:

| Archivo | Uso |
| --- | --- |
| `http/traffic.pcap` | Evidencia de red que se abre en Wireshark. |
| `http/capture.json` | Fecha UTC, referencia de código y hash de la captura. |
| `http/verification.json` | Comprobaciones realizadas sobre el PCAP. |
| `http/follow-streams.txt` | Conversaciones reconstruidas para inspección local. Contiene credenciales ficticias. |
| `http/client-result.json` | Resultado funcional del cliente. |
| `http/tcpdump.log` | Diagnóstico del observador. |

Repetir `capture` en el mismo modo reemplaza la captura y sus resultados. Si debes conservar una ejecución, copia primero su carpeta `http` a otra ubicación privada fuera del repositorio. No uses los archivos de una ejecución anterior para describir como aprobada una ejecución nueva que haya fallado.

## 6 Inspeccionar la captura en Wireshark

1. Abre Wireshark en Windows.
2. Selecciona **File → Open** y abre `labs/secure-channel/.state/http/traffic.pcap` del worktree.
3. En el filtro de visualización introduce:

   ```text
   tcp.port == 8081
   ```

4. Si no se reconocen las peticiones HTTP, selecciona un paquete del flujo y utiliza **Analyze → Decode As → HTTP** para el puerto TCP `8081`.
5. Localiza la petición `POST /api/identity/sessions`. Puedes utilizar el filtro `http.request.method == "POST"` después de decodificar HTTP.
6. Haz clic derecho y selecciona **Follow → TCP Stream**. Muestra la conversación como ASCII o UTF-8.
7. Identifica método, ruta, JSON con `login` y `password`, marcador `LAB5_CANAL_INSEGURO`, respuesta `200` y cabecera `Set-Cookie` de `stream_session`.
8. Cierra la ventana de seguimiento, limpia el filtro aplicado por Follow y localiza `GET /api/profile/me`. Comprueba la cookie enviada y el perfil devuelto. El perfil puede pertenecer a otro flujo TCP porque cada solicitud de `curl` abre su conexión.

La captura de pantalla de `curl` no sustituye esta evidencia: muestra qué quiso enviar el cliente. El PCAP y Follow TCP Stream permiten observar qué información pudo reconstruir el observador del trayecto.

Para compartir capturas en el informe, oculta la contraseña completa, los tokens y las cookies. Conserva visible el marcador, las rutas y los estados HTTP necesarios para explicar el resultado. No distribuyas el PCAP públicamente.

## 7 Qué demostrar y cómo explicarlo

| Comprobación | Qué permite concluir |
| --- | --- |
| Registro `201`, login `200`, perfil `200` e identidad coincidente | Se utilizó un recorrido funcional del sistema, no un servidor de eco. |
| JSON de login legible en el flujo TCP | HTTP expone las credenciales de la cuenta ficticia en tránsito. |
| Cookie de sesión legible y reutilizada en el perfil | También queda expuesta la credencial de sesión transportada por HTTP. |
| Token y cookie CSRF conservados | La exposición no depende de haber desactivado CSRF. |
| Captura restringida al conector del laboratorio | La demostración tiene un alcance concreto y autorizado. |

RNF-025 exige HTTPS/TLS para credenciales, tokens y datos privados en comunicaciones accesibles por red. Este perfil constituye el contraejemplo deliberado que el laboratorio busca corregir. RNF-026 exige hashing apropiado de contraseñas en almacenamiento: Core mantiene ese mecanismo, pero el hash de la base no cifra el trayecto cliente-servidor.

Explicación para la demostración:

> El cliente inicia sesión en Core usando su API real. La cuenta y la sesión funcionan, pero el transporte HTTP permite reconstruir el identificador, la contraseña ficticia y la cookie. El hashing protege la contraseña almacenada; TLS es necesario para proteger su tránsito. Esta observación requiere acceso al trayecto de red, como el observador autorizado que preparamos en Docker.

El Grupo 1 no demuestra ataques activos, acceso a redes ajenas ni seguridad de todo Vault tv. La comparación TLS posterior debe evaluar confidencialidad, integridad y autenticación del servidor para este mismo conector; no constituye una prueba de disponibilidad.

## 8 Entrega a los grupos 2 y 3

Antes de comparar resultados, los grupos deben confirmar el mismo conector, cuenta, cuerpo de login, endpoints y punto de captura. El perfil HTTPS conservará el puerto interno `8081` y el publicado `18081`; los modos se ejecutan de manera excluyente.

| Grupo | Responsabilidad |
| --- | --- |
| Grupo 1 | Perfil HTTP aislado, cuenta ficticia, captura, análisis y reproducción de la exposición. |
| Grupo 2 | Generar llaves y certificado con OpenSSL; configurar TLS directo en Core y la confianza del cliente. |
| Grupo 3 | Repetir el recorrido por HTTPS; comprobar tráfico TLS y rechazo de certificados no confiables o nombres incorrectos. |

Core ya permite configurar TLS público mediante `CORE_PUBLIC_TLS_ENABLED`, `CORE_PUBLIC_TLS_KEYSTORE`, `CORE_PUBLIC_TLS_KEYSTORE_PASSWORD` y `CORE_SECURE_COOKIE`. Su almacén es **PKCS#12**: no basta con montar únicamente un certificado PEM y una llave.

El helper reserva esta interfaz local para el material del Grupo 2:

```text
labs/secure-channel/.state/tls/
  core.p12
  ca.crt
  keystore-password.txt
```

El certificado debe incluir los SAN DNS `core-lab` y `localhost`, y el SAN IP `127.0.0.1`. `core.p12` debe poder ser leído por el usuario `10001` de Core; el directorio se monta como solo lectura. La contraseña del almacén se guarda únicamente en `keystore-password.txt`, sin espacios añadidos. La clave privada y ese archivo no se comparten por Git.

Cuando el Grupo 2 haya preparado y revisado esos archivos:

```powershell
python labs/secure-channel/lab.py up --mode https
python labs/secure-channel/lab.py capture --mode https
python labs/secure-channel/lab.py verify --mode https
```

El cliente usa explícitamente `--cacert /state/tls/ca.crt`; no desactiva la validación mediante `-k`. No es necesario importar la CA en Windows para ejecutar este cliente dentro de Docker. Una prueba adicional desde un navegador requerirá el procedimiento de confianza correspondiente.

Estos comandos describen el soporte preparado para la comparación. No afirman que la generación del certificado ni la aceptación TLS de los otros grupos ya se hayan ejecutado. El Grupo 3 debe comprobar operación funcional, handshake y registros TLS, además de los casos negativos de CA y nombre. La mera ausencia del marcador no prueba que el canal sea seguro. Wireshark no debe tener cargadas claves de sesión TLS durante esa comparación.

## 9 Diagnóstico y cierre

| Síntoma | Comprobación |
| --- | --- |
| Docker no responde | Abrir Docker Desktop; revisar integración WSL si corresponde. |
| `Run init first` | Ejecutar `init` desde el worktree dedicado. |
| Puerto `18081` ocupado | Identificar el proceso que lo utiliza. No detener otros despliegues sin comprobar su propietario. |
| Build falla | Revisar el primer error y la conectividad para descargar imágenes y dependencias. |
| Core no queda listo | Ejecutar `logs`; revisar migraciones, conexión a la base y variables. No omitir readiness. |
| `capture` pide `seed` | Preparar primero la cuenta ficticia mediante `seed`. |
| Falta tráfico o marcador | Verificar que se abrió el PCAP nuevo y que la captura terminó. Usar `8081`, no `18081`, en el filtro de esta captura. |
| La verificación falla | No presentar la ejecución como aprobada. Conservar el error y revisar el flujo que falta. |
| HTTPS exige archivos | Coordinar la generación PKCS#12 y CA con el Grupo 2. No usar `-k` para ocultar el problema. |

Para detener únicamente los contenedores del laboratorio y conservar datos y evidencia:

```powershell
python labs/secure-channel/lab.py down
```

Para repetir otro día sin reconstruir:

```powershell
python labs/secure-channel/lab.py up --mode http
python labs/secure-channel/lab.py seed
python labs/secure-channel/lab.py capture --mode http
python labs/secure-channel/lab.py verify --mode http
python labs/secure-channel/lab.py down
```

No uses `down -v`, borres volúmenes ni elimines `.state/` durante el recorrido. Si cambias el código de Core o la imagen de herramientas, ejecuta `build` antes de repetirlo.

### Evidencia para la entrega

Registra commit probado, fecha, versiones de Docker y Wireshark, resultado funcional, hash del PCAP y hallazgos observados. Distingue la ejecución HTTP del Grupo 1 de las comprobaciones HTTPS posteriores. Daniel debe repetir la guía en su equipo para verificar que sea reproducible por otra persona.

## Referencias

- [SPEC-01 Cuentas y sesión](../../docs/spec-p2/spec_01_auth.md).
- [Catálogo de requisitos RNF-025 y RNF-026](../../docs/catalogo_requisitos.md).
- [Configuración actual de Core](../../services/core/src/main/resources/application.yml).
- [Follow TCP Stream en Wireshark](https://www.wireshark.org/docs/wsug_html_chunked/ChAdvFollowStreamSection.html).
- [OpenSSL PKCS12](https://docs.openssl.org/3.0/man1/openssl-pkcs12/).
- [TLS 1.3 RFC 8446](https://www.rfc-editor.org/rfc/rfc8446.html).
