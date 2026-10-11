# Certificado local para navegador y OBS

El despliegue usa HTTPS y RTMPS con certificados firmados por la CA del perfil. Para abrir la Web
y transmitir con OBS, instala **`ca.crt` del perfil que estás ejecutando** en la máquina donde
corren el navegador y OBS. Aceptar una excepción en el navegador no configura la confianza de OBS.
El [cliente RTMPS de OBS](https://github.com/obsproject/obs-studio/blob/master/plugins/obs-outputs/librtmp/rtmp.c)
lee las CA del sistema: `/etc/ssl/certs` en Linux, anclas de confianza de macOS y almacén `ROOT` en Windows.

Ejecuta los comandos desde la raíz del repositorio, después de inicializar el perfil:

| Perfil | CA pública | Web / RTMPS predeterminados |
| --- | --- | --- |
| Instalación local de equipo | `infra/p1/.state-local/ca.crt` | `3445` / `11938` |
| Aceptación P1 | `infra/p1/.state/ca.crt` | `3443` / `11936` |
| Carga aislada | `infra/p1/.state-load/ca.crt` | `3444` / `11937` |

Para la instalación local de equipo:

```sh
STREAMING_CA="$PWD/infra/p1/.state-local/ca.crt"
STREAMING_HTTPS_PORT=3445
STREAMING_RTMPS_PORT=11938
openssl x509 -in "$STREAMING_CA" -noout -subject -dates -fingerprint -sha256
```

En aceptación P1 usa `.state`, `3443` y `11936`; en carga usa `.state-load`, `3444` y `11937`.
Si elegiste otros puertos durante `init`, usa esos valores. Cada perfil tiene su propia CA.
Solo se comparte/importa `ca.crt`; `ca.key`, las demás claves privadas y `environment.env`
permanecen en el directorio privado del despliegue.

## Linux

En Ubuntu/Debian, instala la CA en el almacén del sistema y reinicia OBS:

```sh
sudo install -m 0644 "$STREAMING_CA" /usr/local/share/ca-certificates/streaming-local-ca.crt
sudo update-ca-certificates
```

El archivo debe tener extensión `.crt`, como indica la
[guía de Ubuntu](https://documentation.ubuntu.com/server/how-to/security/install-a-root-ca-certificate-in-the-trust-store/index.html).
En Fedora/RHEL:

```sh
sudo install -m 0644 "$STREAMING_CA" /etc/pki/ca-trust/source/anchors/streaming-local-ca.crt
sudo update-ca-trust
```

Este es el procedimiento del [almacén de Red Hat](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/securing_networks/using-shared-system-certificates).
Usa OBS nativo para el recorrido de clase. Flatpak/Snap pueden tener un almacén aislado:
si HTTPS funciona en el host pero OBS rechaza la CA, comprueba la confianza dentro de ese paquete
o usa la instalación nativa. Importar la CA solo en Firefox no configura OBS.

## macOS

Instala la CA como raíz confiable en el llavero Sistema; macOS solicita permisos de administrador:

```sh
sudo security add-trusted-cert -d -r trustRoot \
  -k /Library/Keychains/System.keychain "$STREAMING_CA"
```

También puedes abrir **Acceso a Llaveros → Sistema**, importar `ca.crt`, abrir el certificado
«STREAMING P1 local CA» y seleccionar **Confiar → Al usar este certificado → Confiar siempre**.
Consulta el [procedimiento de Apple](https://support.apple.com/es-co/guide/keychain-access/kyca11871/mac).
Cierra completamente OBS y el navegador y vuelve a abrirlos para cargar la confianza.

## Windows como cliente de un despliegue Unix/WSL

El backend corre en Linux/macOS o WSL. Si OBS corre en Windows, importa la CA también en Windows:
la confianza instalada en WSL solo aplica dentro de WSL. Copia únicamente `ca.crt` del perfil
a una carpeta de Windows y ejecuta en PowerShell:

```powershell
certutil.exe -user -addstore Root "C:\ruta\streaming-ca.crt"
```

El certificado queda en **Entidades de certificación raíz de confianza → Usuario actual**;
usa la misma cuenta que ejecuta OBS. El asistente **Instalar certificado → Usuario actual →
Colocar todos los certificados en el siguiente almacén → Entidades de certificación raíz de confianza**
es la alternativa gráfica. Reinicia OBS y el navegador. Referencia:
[almacenes de certificados de Microsoft](https://learn.microsoft.com/en-us/windows-server/identity/ad-cs/inspect-certificates-manage-certificate-stores).

## Verificar y transmitir

Comprueba primero la cadena de certificados y el nombre del servidor, sin enviar claves de emisión:

```sh
curl --fail --cacert "$STREAMING_CA" "https://localhost:$STREAMING_HTTPS_PORT/api/taxonomy"
openssl s_client -connect "localhost:$STREAMING_RTMPS_PORT" -servername localhost \
  -CAfile "$STREAMING_CA" -verify_hostname localhost -verify_return_error </dev/null
```

La primera prueba devuelve Taxonomy; la segunda muestra `Verification: OK` o
`Verify return code: 0 (ok)`. Estas pruebas usan la CA explícitamente: después de instalarla,
abre la Web sin advertencia TLS y transmite desde OBS para comprobar la confianza del cliente.

En **OBS → Ajustes → Emisión**, usa **Servicio: Personalizado**, copia **Servidor** y
**Clave de retransmisión** desde `/studio`, y desactiva **Usar autenticación**.
Pega únicamente la clave `sk_...`, sin añadir usuario, contraseña ni streamId.
El servidor local es `rtmps://localhost:11938/live` (aceptación P1: `11936`); usa el valor del estudio.

Si OBS rechaza el certificado, comprueba la CA del perfil, reinicia OBS y revisa la fecha del equipo
y el vencimiento de `ca.crt` y `mediamtx.crt` con `openssl x509 -in <archivo> -noout -dates`.
Usa `localhost`: los certificados generados incluyen ese nombre y `127.0.0.1`; para otra máquina
se requiere un certificado con su nombre/IP y publicar el listener correspondiente, que aquí está en loopback.
La verificación TLS permanece activa; no se usa `curl -k` ni una opción de OBS para ignorar certificados.
