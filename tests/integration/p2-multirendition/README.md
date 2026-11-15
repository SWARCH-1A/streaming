# Enhanced RTMPS → cuatro calidades HLS → VOD

Prueba contra la instalación local completa, con video/audio codificados **antes** de enviarlos.
Publica cuatro H.264 (720/540/360/180p) y un AAC por una sola conexión; verifica maestro,
ausencia de 1080p inventado, selección manual/automática y continuidad en Chromium, captura
nativa completa, publicación READY y reproducción de las cuatro calidades VOD.

Requiere el stack `python3 infra/local/dev.py init/build/up`, FFmpeg con libx264, Go 1.26,
Python con las dependencias de `tests/integration/p1-domains/requirements.txt` y el paquete `playwright` y Playwright
Chromium instalado. La CA es `infra/p1/.state-local/ca.crt`. El cliente de prueba valida TLS;
el navegador usa su contexto de prueba HTTPS independiente.

Desde la raíz, con un entorno Python activado:

```sh
python -m playwright install chromium
python tests/integration/p2-multirendition/make_source.py /tmp/streaming-four-qualities.ts
(cd tests/integration/p2-multirendition/publisher && go build -o /tmp/streaming-source-publisher .)
export P2_TEST_ACCOUNTS=/ruta/privada/cuentas-ficticias.json
python tests/integration/p2-multirendition/run.py \
  --publisher /tmp/streaming-source-publisher --source /tmp/streaming-four-qualities.ts
```

`P2_TEST_ACCOUNTS` es un JSON privado 0600, fuera de Git, con `ssr-owner` cuyo objeto incluye
`handle` (prefijo `rel_`) y `password`; la cuenta ficticia debe existir. No usar una cuenta personal.
El runner crea/reutiliza su configuración, rota su clave de emisión y detiene la sesión al salir.
La URL secreta se entrega por stdin al publicador; nunca por argv ni logs. El VOD publicado queda
como fixture revisable. La fuente de 75 s ocupa aproximadamente 40 MB y se puede borrar al terminar.

El publicador puede compilarse en Docker, montando este directorio y escribiendo el binario fuera
del repositorio. En macOS arm64 hay que compilar con `GOOS=darwin GOARCH=arm64`; un binario Linux
de contenedor no se ejecuta directamente en el Mac.

Para repetir solo la reproducción de un VOD ya publicado, sin crear emisión ni rotar claves:

```sh
python tests/integration/p2-multirendition/run.py --vod-only VOD_ID
```

Esta prueba acredita el protocolo Enhanced RTMPS y los frames decodificados. La operación manual
de OBS se comprueba con la [configuración entregada](../../../infra/media/README.md#obs-y-multicalidad-p2)
y su JSON oficial; no se atribuye esa operación de UI al emisor de protocolo.

Añadir `--reconnect` a la ejecución completa desconecta la primera fuente y vuelve a publicar
las cuatro pistas dentro de la gracia: exige la misma sesión, las cuatro variantes y un VOD
final que conserva las cuatro calidades a través de ambas generaciones.
