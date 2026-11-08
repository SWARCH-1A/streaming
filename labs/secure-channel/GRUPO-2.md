# Laboratorio 5 - Grupo 2: construir el canal seguro con TLS

Esta guía tiene dos partes:

- **Parte A - Receta general.** Sirve para cualquier proyecto con un cliente y un servidor que hablan por HTTP.
- **Parte B - Cómo lo aplicamos en Vault tv.** El mismo recorrido, con nuestros archivos y comandos.

El Grupo 1 mostró el problema: por HTTP se lee la contraseña y la cookie. El Grupo 2 construye el canal seguro. El Grupo 3 demuestra que protege (sección B4).

---

# Parte A - Receta general

## A1 El patrón en una página

**Canal seguro:** cliente y servidor hablan por TLS. Así se logran tres cosas:

| Propiedad | Qué significa |
| --- | --- |
| Confidencialidad | Quien mira la red no puede leer los datos. |
| Integridad | Nadie puede cambiar los datos sin que se note. |
| Autenticación del servidor | El cliente sabe que habla con el servidor real. |

Piezas que se necesitan:

```text
            firma                         confía en
   CA  ───────────────►  certificado  ◄─────────────── cliente
 (ca.key, ca.crt)        del servidor                 (tiene ca.crt)
                         (server.crt)
                              │ se usa con
                              ▼
                         llave privada
                         (server.key)  → solo la tiene el servidor
```

| Pieza | Archivo | Quién lo tiene | ¿Secreto? |
| --- | --- | --- | --- |
| Llave de la CA | `ca.key` | Solo quien firma | Sí |
| Certificado de la CA | `ca.crt` | Cliente | No |
| Llave del servidor | `server.key` | Solo el servidor | Sí |
| Certificado del servidor | `server.crt` | Servidor (lo envía al cliente) | No |

En un laboratorio usamos una **CA privada**. En producción se usa una CA pública, por ejemplo Let's Encrypt. Los pasos del servidor y del cliente son los mismos.

## A2 Generar llaves y certificado con OpenSSL

Usa Git Bash, WSL, macOS o Linux. Si no tienes OpenSSL, puedes cambiar `openssl` por:

```sh
docker run --rm -v "$PWD:/w" -w /w alpine/openssl
```

Cambia `mi-servicio` por el nombre con el que el cliente llama al servidor. En Docker Compose, es el nombre del servicio.

```sh
# 1. CA privada: llave y certificado
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out ca.key
openssl req -x509 -new -key ca.key -sha256 -days 30 -subj "/CN=Lab5 CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" -out ca.crt

# 2. Llave del servidor y solicitud de certificado (CSR)
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out server.key
openssl req -new -key server.key -subj "/CN=mi-servicio" -out server.csr

# 3. Nombres válidos del servidor (SAN)
cat > server.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = DNS:mi-servicio, DNS:localhost, IP:127.0.0.1
EOF

# 4. La CA firma el certificado del servidor
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -sha256 -days 30 -extfile server.ext -out server.crt

# 5. Comprobar antes de usarlo
openssl verify -CAfile ca.crt -verify_hostname mi-servicio server.crt   # server.crt: OK
openssl verify -CAfile ca.crt -verify_hostname otro-nombre server.crt   # debe fallar
```

Tres reglas:

1. El **SAN** (nombres válidos) debe tener cada nombre que usa el cliente. El `CN` ya no basta.
2. `ca.key` y `server.key` **nunca** van a Git. Agrega `*.key`, `*.p12` y `*.pem` a `.gitignore`.
3. El servidor recibe `server.key`, pero nunca `ca.key`.

## A3 Configurar el servidor

Elige la fila de tu tecnología. Algunas piden un archivo PKCS#12 (llave y certificado juntos, con contraseña):

```sh
openssl pkcs12 -export -name servidor -inkey server.key -in server.crt \
  -certfile ca.crt -passout pass:$KEYSTORE_PASSWORD -out server.p12
```

| Tecnología | Archivos | Configuración |
| --- | --- | --- |
| Spring Boot | `server.p12` | `server.ssl.enabled=true`, `server.ssl.key-store=server.p12`, `server.ssl.key-store-type=PKCS12`, `server.ssl.key-store-password=${KEYSTORE_PASSWORD}` |
| Node.js / Express | `server.key`, `server.crt` | `https.createServer({ key, cert }, app)` |
| Python / Uvicorn | `server.key`, `server.crt` | `uvicorn app:app --ssl-keyfile server.key --ssl-certfile server.crt` |
| Go | `server.key`, `server.crt` | `http.ListenAndServeTLS(":8443", "server.crt", "server.key", h)` |
| Nginx (proxy) | `server.key`, `server.crt` | `listen 443 ssl; ssl_certificate server.crt; ssl_certificate_key server.key;` |

En todos los casos:

- Permite solo TLS 1.2 y 1.3.
- Marca las cookies de sesión como `Secure`.
- Pasa contraseñas por variables de entorno, no en el código.
- En Docker, monta los archivos como solo lectura (`:ro`).

## A4 Configurar el cliente

El cliente debe confiar en `ca.crt`. **Nunca** apagues la verificación (`curl -k`, `verify=False`, `rejectUnauthorized: false`).

| Cliente | Cómo confiar en la CA |
| --- | --- |
| curl | `curl --cacert ca.crt https://mi-servicio:8443/...` |
| Node.js | `NODE_EXTRA_CA_CERTS=ca.crt` |
| Python requests | `requests.get(url, verify="ca.crt")` |
| Java | `keytool -importcert -file ca.crt -keystore truststore.p12` y `-Djavax.net.ssl.trustStore=truststore.p12` |
| Navegador | Importar `ca.crt` como CA de confianza (solo en tu equipo de pruebas). |

## A5 Prueba rápida

```sh
curl --cacert ca.crt https://localhost:8443/       # responde
curl https://localhost:8443/                       # falla: (60) certificado no confiable
```

Si la primera responde y la segunda falla, el canal está montado. Probar que protege es el trabajo del Grupo 3.

---

# Parte B - Cómo lo aplicamos en Vault tv

## B1 Qué protegimos

El conector **cliente de prueba → Core**, el mismo del Grupo 1. Core es Spring Boot y ya soportaba TLS por variables. No cambiamos su código.

| Paso de la receta | En Vault tv |
| --- | --- |
| A2 Generar | `lab.py certs` corre [pki.py](pki.py) con los mismos comandos OpenSSL. |
| Nombre del servidor | `core-lab`, `localhost`, `127.0.0.1` |
| A3 Servidor | PKCS#12 `core.p12` + variables `CORE_PUBLIC_TLS_*` y `CORE_SECURE_COOKIE` |
| A4 Cliente | `curl --cacert /state/tls/ca.crt` en [client.py](client.py) |
| A5 Prueba | `lab.py up --mode https` y `lab.py capture --mode https` |

Diferencias con la receta general:

- OpenSSL corre en un contenedor **sin red**. Nadie tiene que instalarlo.
- `pki.py` comprueba los tres nombres antes de publicar los archivos.
- `core.p12` queda con dueño `10001` (el usuario de Core) y permisos `0400`.
- `ca.key` queda en `.state/pki/`, fuera del montaje de Core.

## B2 Archivos

Todo queda en `labs/secure-channel/.state/`, que Git ignora:

| Archivo | Uso | ¿Secreto? |
| --- | --- | --- |
| `pki/ca.key` | Firmar | Sí |
| `tls/ca.crt` | El cliente confía en ella | No |
| `tls/core.crt` | Certificado de Core | No |
| `tls/core.p12` | Llave y certificado para Core | Sí |
| `tls/keystore-password.txt` | Contraseña de `core.p12` | Sí |

## B3 Pasos

Parte del clon del ensayo con Core, con `init`, `build` y `seed` hechos ([guía de Vault TV](VAULT-TV.md)). Si ya tenías el clon, actualízalo y reconstruye, porque la imagen ahora trae `openssl`:

```powershell
git pull
python labs/secure-channel/lab.py build
```

Luego:

```powershell
python labs/secure-channel/lab.py certs
python labs/secure-channel/lab.py up --mode https
python labs/secure-channel/lab.py seed
python labs/secure-channel/lab.py capture --mode https
```

| Comando | Resultado esperado |
| --- | --- |
| `certs` | `Issued private CA and Core keystore` y SAN `DNS:core-lab, DNS:localhost, IP Address:127.0.0.1` |
| `up --mode https` | `Core UP: https://localhost:18081 (only loopback).` |
| `seed` | `Registration: 201`, o idempotente si ya existía la cuenta |
| `capture --mode https` | `CSRF: 200; login: 200; authenticated profile: 200; identity matches seed.` |

Repetir `certs` conserva el material. `certs --force` crea todo de nuevo; después repite `up --mode https`.

Probado en macOS con Docker Desktop: los cuatro pasos pasaron con TLS 1.3. Registra tu propio commit y resultado.

## B4 Entrega al Grupo 3

El Grupo 2 entrega:

- `ca.crt` y `core.crt` para revisar.
- Core en HTTPS con el login real funcionando.
- `capture --mode https` deja `.state/https/traffic.pcap`.

El Grupo 3 demuestra, con su propia guía:

- Que el tráfico va cifrado (`lab.py verify --mode https` y Wireshark).
- Que el cliente rechaza una CA no confiable.
- Que el cliente rechaza un nombre que no está en el SAN.

## B5 Problemas comunes

| Síntoma | Qué hacer |
| --- | --- |
| `openssl: not found` en `certs` | Ejecutar `build`. |
| `HTTPS requires .state/tls/...` | Ejecutar `certs` antes de `up --mode https`. |
| Core no arranca en HTTPS | Ejecutar `logs`. Buscar `keystore password was incorrect` o `AccessDeniedException`. Repetir `certs --force`. |
| curl `(60) self-signed certificate in certificate chain` | El cliente no tiene la CA correcta. No usar `-k`. |
| curl `(60) no alternative certificate subject name` | El nombre no está en el SAN. |
| `certificate has expired` | `certs --force` y luego `up --mode https`. |

No publiques `.state/`: tiene llaves, contraseñas, PCAP y cookies.

## Referencias

- [Ensayo con Core y continuidad TLS](VAULT-TV.md#8-entrega-a-los-grupos-2-y-3).
- [Configuración TLS de Core](../../services/core/src/main/resources/application.yml).
- [OpenSSL req](https://docs.openssl.org/3.0/man1/openssl-req/), [x509](https://docs.openssl.org/3.0/man1/openssl-x509/), [verify](https://docs.openssl.org/3.0/man1/openssl-verify/), [pkcs12](https://docs.openssl.org/3.0/man1/openssl-pkcs12/).
- [Spring Boot SSL](https://docs.spring.io/spring-boot/reference/features/ssl.html).
- [TLS 1.3, RFC 8446](https://www.rfc-editor.org/rfc/rfc8446.html).
