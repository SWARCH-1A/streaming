# iPhone físico con el Mac como servidor local

Se puede instalar esta app de desarrollo con una cuenta gratuita de Apple (Personal Team).
Los perfiles gratuitos vencen a los siete días: volver a ejecutar desde Xcode renueva la instalación.
No se necesita App Store ni TestFlight. Push remoto/APNs sigue requiriendo una membresía habilitada;
no forma parte de este procedimiento. [Firma de desarrollo de Apple](https://help.apple.com/xcode/mac/current/en.lproj/dev60b6fbbc7.html).

## Preparar el servidor

Mac y iPhone deben estar en la misma LAN, con comunicación entre dispositivos. Evitar una red de
invitados con aislamiento de clientes. El Mac debe permanecer encendido y Docker Desktop iniciado.
Desde la raíz del repositorio:

```sh
python3 infra/local/dev.py init
python3 infra/local/dev.py lan
python3 infra/local/dev.py build  # al cambiar código del servidor o Web
python3 infra/local/dev.py up
```

En macOS, `lan` usa el nombre Bonjour del Mac y detecta su IPv4 privada. Muestra el origen HTTPS
que deben compartir navegador, app, WS y enlaces multimedia. Por ejemplo:

```sh
python3 infra/local/dev.py lan --host macbook-pro.local --address 192.168.0.7
python3 infra/local/dev.py up
```

Se publica solo la entrada HTTPS en todas las interfaces IPv4. RTMPS para OBS en el Mac y Mailpit
siguen en loopback; bases y APIs internas no se publican. El certificado de entrada conserva la CA
y sus nombres internos e incorpora el nombre LAN/IP. Las visitas por localhost/IP se redirigen al
origen canónico para conservar cookies y comprobaciones CSRF. Abrir el nombre mostrado también
desde el navegador del Mac. La app debe compilarse con ese origen; una app antigua configurada
con localhost debe actualizarse. El puerto HTTPS predeterminado es 3445.

El nombre `.local` evita recompilar al cambiar la IP por DHCP. Si cambia la red, volver a ejecutar
`lan` y `up` para actualizar el alias IP del certificado; mantener el nombre Bonjour. Si la red
bloquea mDNS, reservar la IP del Mac en el router y usar `lan --host IP --address IP`, luego actualizar
el origen de la app. La subred LAN no puede solaparse con la de Docker.

## Confiar en el certificado en el iPhone

1. En el Mac, envía por AirDrop **solo** `infra/p1/.state-local/ca.cer` al iPhone. `lan` genera
   esta copia DER pública de `ca.crt`; ambos representan la misma CA.
2. En el iPhone, abre Ajustes → General → VPN y gestión de dispositivos y instala el perfil
   descargado. Puede aparecer directamente «Perfil descargado» en Ajustes.
3. Ve a Ajustes → General → Información → Ajustes de confianza de los certificados y activa la
   confianza total para «STREAMING P1 local CA».
4. Abre en Safari el origen mostrado por `lan`, por ejemplo `https://macbook-pro.local:3445`.
   Debe cargar sin advertencias de certificado.

Si Safari también muestra una advertencia y la app devuelve `NSURLErrorDomain -1202`,
compara la CA instalada con la del perfil activo: los perfiles `.state`, `.state-local` y
`.state-load` tienen CA distintas aunque compartan el nombre «STREAMING P1 local CA».
En los detalles del certificado del perfil de iOS, comprueba caducidad y huella SHA-256 contra:

```sh
openssl x509 -in infra/p1/.state-local/ca.crt -noout -dates -fingerprint -sha256
```

Si no coincide, elimina únicamente el perfil de esa CA del proyecto, envía de nuevo el
`ca.cer` de `.state-local`, instálalo y activa su confianza total. El certificado del
desarrollador de Xcode es independiente. Comprueba también la fecha y hora del iPhone.
Continuar tras la advertencia de Safari no configura la confianza de la app; reinstalar
la app tampoco instala la CA. Antes de repetir la instalación desde Xcode, Safari debe
abrir el origen sin advertencias.

No enviar `ca.key`, claves de servidor ni `environment.env`. No instalar un servidor HTTP sobre
el directorio de estado: contiene secretos. [Confianza manual de certificados en iOS](https://support.apple.com/en-gb/102390).

## Instalar y ejecutar con Xcode

1. Abre `apps/mobile/iosApp/VaultTV.xcodeproj`. El proyecto compila el framework Kotlin y copia
   sus recursos automáticamente; no requiere CocoaPods ni XcodeGen. Requiere JDK arm64 21+.
2. Copia `Config.local.xcconfig.example` a `Config.local.xcconfig` y ajusta `VAULT_API_ORIGIN` al
   origen del servidor. El archivo local está ignorado por Git. Conserva `https:/$()/...`: `$()`
   evita que `//` se interprete como comentario de xcconfig. La app recibe `https://...`.
3. En Xcode → Settings → Accounts, añade tu Apple Account si aún no aparece.
4. En el target **VaultTV → Signing & Capabilities**, conserva **Automatically manage signing**
   y selecciona tu **Personal Team**. Si `dev.swarch.vault` no está disponible, define un Bundle
   Identifier único en `Config.local.xcconfig`, por ejemplo `dev.tunombre.vault`. No añadir la
   capacidad Push Notifications a esta instalación gratuita.
5. Conecta el iPhone por USB, desbloquéalo y acepta «Confiar en este ordenador». Selecciónalo como
   destino del esquema **VaultTV**. En el iPhone activa Ajustes → Privacidad y seguridad → Modo
   de desarrollador, reinicia y confirma cuando lo solicite. [Modo de desarrollador](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device).
6. Ejecuta **Product → Run** (`⌘R`). Si iOS solicita confiar en tu desarrollador, hazlo en
   Ajustes → General → VPN y gestión de dispositivos. Xcode administra certificado, App ID y perfil.
7. Acepta el permiso **Red local** al abrir Vault tv. Si lo denegaste, actívalo en
   Ajustes → Privacidad y seguridad → Red local. Si la primera consulta falló mientras respondías
   al permiso, pulsa Reintentar o vuelve a abrir la app.

Después de instalar, la app puede comunicarse por Wi-Fi con el Mac sin el cable. Para depuración
inalámbrica, emparejar primero el teléfono desde Xcode → Window → Devices and Simulators.
Probar explorar, login, canal, video y chat; cambiar a segundo plano y volver. Compilar o abrir
Safari no acredita esos recorridos en el teléfono.

## Diagnóstico y vuelta a localhost

Desde el Mac, usando el origen real:

```sh
curl --fail --cacert infra/p1/.state-local/ca.crt https://macbook-pro.local:3445/api/taxonomy
python3 infra/local/dev.py status
```

Si Safari no llega al servidor, comprobar misma LAN, aislamiento Wi-Fi, resolución `.local`,
VPN y permiso del firewall de macOS para Docker. No es necesario abrir puertos en el router.
Si Chrome/Brave muestra `ERR_TUNNEL_CONNECTION_FAILED` mientras la conexión directa funciona,
revisar también las extensiones de proxy/VPN del navegador: pueden enviar el nombre `.local`
a un proxy remoto. En Proton VPN, abrir **Features → Split tunneling → Exclude mode → Add website**
y excluir únicamente el nombre canónico del servidor, sin ruta ni puerto. Antes de activar esta
función, revisar las demás exclusiones guardadas porque también se activan. Recargar la página.
El resto de sitios puede continuar usando la VPN. [Guía de la extensión Proton VPN](https://protonvpn.com/support/browser-extensions).
Si Safari funciona y la app falla, comprobar origen compilado, confianza total de la CA y permiso
Red local. No usar localhost en el iPhone: señalaría al propio teléfono. La verificación TLS sigue activa.

Para retirar el acceso LAN conservando datos y CA:

```sh
python3 infra/local/dev.py lan --loopback
python3 infra/local/dev.py up
```

La app LAN deja de tener acceso hasta reactivar ese modo o recompilar con un servidor alcanzable.
