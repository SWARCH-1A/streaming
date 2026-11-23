# Aceptación de segmentación de red

Desde la raíz, con Python 3.12+ y Docker Compose:

```sh
python3 -m unittest discover -s infra/local -p test_network_policy.py
python3 tests/integration/network-segmentation/run.py --static-only
python3 tests/integration/network-segmentation/run.py
python3 tests/integration/network-segmentation/run.py --upgrade
python3 tests/integration/network-segmentation/smoke.py
# Solo si todas las imágenes locales corresponden a este checkout:
python3 tests/integration/network-segmentation/smoke.py --skip-build
```

Docker tiene un número limitado de rangos de red predefinidos: con el stack local levantado puede
faltar espacio para el fixture; `python3 infra/local/dev.py down` (conserva volúmenes) lo libera.

Las dos primeras verificaciones resuelven los Compose reales con secretos ficticios y no
requieren daemon. Rechazan conexiones extra de almacenes, puertos publicados inesperados,
red de host, contenedores privilegiados, pérdida de redes internas y acceso amplio a Auditor.

`run.py` copia las conexiones/IPAM del modelo completo, incluido el perfil `tools`, a un proyecto
aleatorio de 25 contenedores y 16 bridges. Sustituye procesos por listeners TCP Python 3.13 Alpine
en sus puertos reales, sin credenciales ni volúmenes de instalación. Comprueba cada almacén desde
todos los otros servicios por DNS e IP directa, acceso de sus propietarios, dependencias API,
medios, SMTP y AMQP, exclusión del Gateway/SSR/proxy del broker, acceso exclusivo de Core
a la API de Auditor entre aplicaciones y aislamiento de `waf-relay` (solo broker, ADR-032). Espera a los listeners antes de verificar los bloqueos.
Comprueba los tres puertos publicados mediante puertos loopback aleatorios y elimina solo su proyecto.
Salida: 112 conexiones permitidas y 489 bloqueadas. Acredita la política de red con tráfico real;
no acredita protocolos de aplicación, grabación ni carga.

`--upgrade` inicia primero esos mismos fixtures en la antigua red única y aplica después
la segmentación con `up`, sin `down` previo. Comprueba la reutilización de la subred e IP
fijas de la zona API y vuelve a verificar los mismos accesos y bloqueos.

`smoke.py` construye las imágenes desde el checkout, inicializa certificados/CA y credenciales
nuevos y arranca el Compose completo con datos desechables, otra subred y puertos loopback
libres. Requiere OpenSSL y la imagen JDK 25 para el inicializador. Comprueba readiness configurada,
SSR, taxonomía HTTPS, registro/login reales, consulta de auditoría 401/403 y consulta ADMIN
Core–Auditor bajo TLS. Provisiona el rol ADMIN explícitamente mediante SQL solo para su cuenta
ficticia. No usa ni modifica la instalación normal y elimina sus contenedores/redes/volúmenes al salir.
No acredita ingesta/reproducción audiovisual ni entrega SMTP/push; esos recorridos conservan
sus suites propias. La compilación conserva las cachés e imágenes locales de herramientas.
