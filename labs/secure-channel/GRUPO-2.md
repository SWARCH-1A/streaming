# Laboratorio 5. Canal seguro con TLS.

El Grupo 1 mostró que un mensaje enviado por HTTP se puede leer en Wireshark. En esta parte ponemos TLS en el mismo canal. La guía tiene dos partes:

- **Parte A.** El mismo ejemplo de Node.js del Grupo 1, ahora por HTTPS. Sirve como receta para cualquier proyecto.
- **Parte B.** Cómo lo aplicamos en Vault TV, en la conexión entre el cliente de prueba y Core.

Demostrar que el tráfico va cifrado y que el cliente rechaza certificados falsos le corresponde al Grupo 3.

# Parte A. Receta general

## A1. Qué necesita TLS

El servidor necesita una llave privada y un certificado. El certificado dice para qué nombres vale la llave, por ejemplo `localhost`. Una autoridad certificadora (CA) lo firma. El cliente confía en esa CA, así que puede comprobar que habla con el servidor correcto antes de enviar datos.

En producción la CA es pública, como Let's Encrypt. En el laboratorio creamos una CA privada con OpenSSL. Los pasos en el servidor y en el cliente son los mismos.

Usaremos estos archivos de la carpeta `ejemplo-https`:

| Archivo | Para qué sirve |
| --- | --- |
| `ca.cnf` | Datos de la CA del laboratorio. |
| `server.cnf` | Datos del certificado del servidor. `subjectAltName` lista sus nombres: `localhost` y `127.0.0.1`. |
| `server.mjs` | Inicia el servidor HTTPS en `127.0.0.1:18443`. Usa el mismo `handler.mjs` del Grupo 1. |

Los archivos generados quedan en `ejemplo-https/certs/`, que Git ignora. `ca.key` y `server.key` son secretos: no se suben al repositorio ni se comparten.

## A2. Generar la CA, la llave y el certificado

En Windows, abre **Git Bash** en la carpeta `ejemplo-https`; Git for Windows ya incluye OpenSSL. En macOS o Linux usa una terminal normal. Ejecuta:

```sh
mkdir certs
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out certs/ca.key
openssl req -x509 -new -key certs/ca.key -sha256 -days 30 -config ca.cnf -out certs/ca.crt
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out certs/server.key
openssl req -new -key certs/server.key -config server.cnf -out certs/server.csr
openssl x509 -req -in certs/server.csr -CA certs/ca.crt -CAkey certs/ca.key -CAcreateserial -sha256 -days 30 -extfile server.cnf -extensions server -out certs/server.crt
openssl verify -CAfile certs/ca.crt -verify_hostname localhost certs/server.crt
```

Las dos primeras líneas de OpenSSL crean la CA. Las dos siguientes crean la llave del servidor y una solicitud de certificado (CSR). La quinta hace que la CA firme esa solicitud. La última comprueba el resultado y debe mostrar:

```text
certs/server.crt: OK
```

Si no tienes OpenSSL pero sí Docker, puedes reemplazar `openssl` por `docker run --rm -v "$PWD:/w" -w /w alpine/openssl`.

## A3. Iniciar el servidor HTTPS

Vuelve a la carpeta donde están `ejemplo-http` y `ejemplo-https` y ejecuta:

```powershell
node ejemplo-https/server.mjs
```

Debe aparecer:

```text
Servidor del laboratorio: https://localhost:18443/mensaje
Déjalo abierto. Para terminar, pulsa Ctrl+C en esta terminal.
```

Frente al `server.mjs` del Grupo 1, el cambio es usar `node:https` en lugar de `node:http` y pasarle la llave y el certificado. `handler.mjs` no cambia.

## A4. Enviar la petición confiando en la CA

En una segunda terminal, desde la misma carpeta:

```powershell
curl.exe --noproxy "*" --http1.1 --include `
  --cacert "ejemplo-https/certs/ca.crt" `
  --header "Content-Type: application/json" `
  --data-binary "@ejemplo-http/solicitud.json" `
  https://localhost:18443/mensaje
```

La respuesta debe ser la misma del Grupo 1: `HTTP/1.1 200 OK` y el JSON con `"recibido": true`.

`--cacert` le dice a curl que confíe en nuestra CA. No uses `-k` ni `--insecure`: esas opciones apagan la comprobación del certificado.

Si curl en Windows muestra `CERT_TRUST_REVOCATION_STATUS_UNKNOWN`, agrega `--ssl-revoke-best-effort`. Nuestra CA no publica una lista de certificados revocados. No lo probamos en Windows.

## A5. Aplicarlo en otro proyecto

Cambia `subjectAltName` en `server.cnf` por los nombres que usa tu cliente. En Docker Compose suele ser el nombre del servicio, por ejemplo `DNS:api, DNS:localhost`. Luego configura el servidor y el cliente:

| Tecnología | Servidor | Cliente |
| --- | --- | --- |
| Node.js | `https.createServer({ key, cert }, app)` | `NODE_EXTRA_CA_CERTS=ca.crt` |
| Python (Uvicorn, requests) | `uvicorn app:app --ssl-keyfile server.key --ssl-certfile server.crt` | `requests.get(url, verify="ca.crt")` |
| Spring Boot | `server.ssl.*` con un archivo PKCS#12 (ver B2) | `keytool -importcert -file ca.crt -keystore truststore.p12` |
| Go | `http.ListenAndServeTLS(":8443", "server.crt", "server.key", h)` | `tls.Config{RootCAs: pool}` |
| Nginx | `ssl_certificate server.crt; ssl_certificate_key server.key;` | Depende del cliente |

En todos los casos, permite solo TLS 1.2 y 1.3 y marca las cookies de sesión como `Secure`. Pasa las contraseñas por variables de entorno.

# Parte B. Cómo lo aplicamos en Vault TV

## B1. Qué protegimos

Protegimos la conexión entre el cliente de prueba y Core, la misma de [VAULT-TV.md](VAULT-TV.md). Core es Spring Boot y ya podía usar TLS mediante variables de entorno. No cambiamos su código.

`python labs/secure-channel/lab.py certs` ejecuta [pki.py](pki.py). Ese script corre los mismos comandos de A2 dentro de un contenedor sin red, así nadie tiene que instalar OpenSSL. El certificado vale para `core-lab`, `localhost` y `127.0.0.1`.

## B2. Servidor y cliente

Spring Boot no lee `server.key` y `server.crt` por separado. Necesita un archivo PKCS#12 que guarda la llave y el certificado juntos, protegidos con una contraseña. `pki.py` lo crea así:

```sh
openssl pkcs12 -export -name core-lab -inkey core.key -in core.crt -certfile ca.crt -passout file:keystore-password.txt -out core.p12
```

`lab.py up --mode https` pasa estas variables a Core:

```text
CORE_PUBLIC_TLS_ENABLED=true
CORE_PUBLIC_TLS_KEYSTORE=/tls/core.p12
CORE_PUBLIC_TLS_KEYSTORE_PASSWORD=<contenido de keystore-password.txt>
CORE_SECURE_COOKIE=true
```

El cliente, [client.py](client.py), llama a `https://core-lab:8081` con `curl --cacert /state/tls/ca.crt`.

Los archivos quedan en `labs/secure-channel/.state/`, que Git ignora:

| Archivo | Uso |
| --- | --- |
| `pki/ca.key` | Llave de la CA. No se monta en Core. |
| `tls/ca.crt` | CA en la que confía el cliente. |
| `tls/core.crt` | Certificado de Core. |
| `tls/core.p12` | Llave y certificado de Core. Solo lo lee el usuario `10001` de Core. |
| `tls/keystore-password.txt` | Contraseña de `core.p12`. |

## B3. Pasos

Parte de un clon con `init`, `build` y `seed` hechos, según [VAULT-TV.md](VAULT-TV.md). Si tu clon es anterior a esta guía, ejecuta `git pull` y luego `build`, porque la imagen de herramientas ahora incluye OpenSSL.

```powershell
python labs/secure-channel/lab.py certs
python labs/secure-channel/lab.py up --mode https
python labs/secure-channel/lab.py capture --mode https
```

`certs` debe terminar con `Issued private CA and Core keystore` y mostrar `DNS:core-lab, DNS:localhost, IP Address:127.0.0.1`. `up` debe mostrar `Core UP: https://localhost:18081 (only loopback).` `capture` debe mostrar `CSRF: 200; login: 200; authenticated profile: 200; identity matches seed.`

Con eso el login real de Core funciona por HTTPS. La captura queda en `.state/https/traffic.pcap` para el Grupo 3.

Repetir `certs` conserva los archivos. `certs --force` crea todo de nuevo; después repite `up --mode https`.

Lo probamos en macOS con Docker Desktop. La conexión usó TLS 1.3.

## B4. Problemas comunes

| Mensaje | Qué hacer |
| --- | --- |
| `openssl: not found` en `certs` | Ejecutar `build`. |
| `HTTPS requires .state/tls/...` | Ejecutar `certs` antes de `up --mode https`. |
| Core no arranca en HTTPS | Ejecutar `logs`. Si dice `keystore password was incorrect` o `AccessDeniedException`, ejecutar `certs --force`. |
| `certificate has expired` | Ejecutar `certs --force` y luego `up --mode https`. |

No publiques `.state/` ni `ejemplo-https/certs/`: contienen llaves y contraseñas.

## Referencias

- [Configuración TLS de Core](../../services/core/src/main/resources/application.yml).
- [OpenSSL req](https://docs.openssl.org/3.0/man1/openssl-req/), [x509](https://docs.openssl.org/3.0/man1/openssl-x509/), [verify](https://docs.openssl.org/3.0/man1/openssl-verify/) y [pkcs12](https://docs.openssl.org/3.0/man1/openssl-pkcs12/).
- [Node.js HTTPS](https://nodejs.org/api/https.html).
- [Spring Boot SSL](https://docs.spring.io/spring-boot/reference/features/ssl.html).
