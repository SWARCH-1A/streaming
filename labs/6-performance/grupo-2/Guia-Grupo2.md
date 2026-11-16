# Laboratorio 6 · Pruebas del canal público

Medimos `GET /api/screens/channels/lab6_canal`, la consulta de un canal público de Vault TV como visitante. La solicitud pasa por Caddy, el API Gateway, Core y PostgreSQL. El navegador abre `/channels/lab6_canal` para comprobar Web SSR. La curva mide la latencia de la API, no el tiempo de carga de la página.

El kit corresponde al [perfil del Grupo 1, commit `5d85a39`](https://github.com/SWARCH-1A/streaming/blob/5d85a39c7feb0eb21436d7b8b28bc16beb4ba9da/labs/6-performance/README.md). El servidor y el portátil que ejecuta k6 deben ser equipos distintos. En el portátil solo hacen falta PowerShell y k6; usen Excel para registrar los resultados.

## Antes de medir

1. Pidan al Grupo 1 la **URL HTTPS en la LAN**, el **SHA desplegado** y el **certificado público de su CA**. Confirmen que ejecutaron `check.py --seed` y que pasaron sus dos comprobaciones. La dirección `localhost` del ejemplo del repositorio solo funciona en el equipo de despliegue.
2. Desde el portátil de pruebas, abran PowerShell en la carpeta del kit y guarden la dirección sin ruta, por ejemplo `https://192.168.1.50:8443` si esa fuera la dirección del Grupo 1.

```powershell
$base = (Read-Host 'URL HTTPS del Grupo 1, sin ruta').Trim().TrimEnd('/')
$servidor = [Uri]$base
Test-NetConnection -ComputerName $servidor.DnsSafeHost -Port $servidor.Port
```

La prueba de red debe mostrar `TcpTestSucceeded : True`. Si falla, revisen la dirección y el puerto con el Grupo 1.

3. Si Windows no reconoce la CA, confirmen que el archivo que recibieron corresponde al despliegue. Con el certificado público en esta carpeta, usen el comando de abajo. Si Windows solicita confirmación, acéptenla solo después de comprobar que es la CA del laboratorio. Usen siempre la URL que les dio el Grupo 1 para que coincida con el certificado.

```powershell
certutil.exe -user -addstore Root (Resolve-Path -LiteralPath '.\lab6-ca.crt').Path
```

4. **Laura** abre en el navegador la URL del Grupo 1 con `/channels/lab6_canal` al final y comprueba que se vea el canal `lab6_canal`. Luego ejecuta **Verificar** en el mismo portátil que usará k6:

```powershell
.\Ejecutar-Lab6.ps1 -Modo Verificar -BaseUrl $base
```

Esta comprobación debe hacer tres solicitudes y obtener tres `200` con el JSON esperado. Debe aparecer el handle `lab6_canal` con su identificador. El perfil no despliega VOD: `meta.partial=true` y `vods.status=UNAVAILABLE` son normales. También se espera que el canal esté fuera de emisión y que el seguimiento requiera sesión. Revisen que se generen un CSV y un JSON en `resultados`.

El lanzador busca k6 instalado o en el PATH. Si otro portátil no lo tiene, pueden [instalar k6 en Windows](https://grafana.com/docs/k6/latest/set-up/install-k6/#windows) y abrir una terminal nueva. Si PowerShell bloquea el lanzador por venir de un ZIP descargado, después de comprobar la procedencia desbloqueen solo ese archivo con `Unblock-File -LiteralPath '.\Ejecutar-Lab6.ps1'`.

## Ejecutar la serie

**Tú** ejecutas todos los niveles desde el mismo portátil. Empieza con el calentamiento: un usuario virtual durante 10 s.

```powershell
.\Ejecutar-Lab6.ps1 -Modo Calentar -BaseUrl $base
```

Después ejecuta **Medir** una vez por nivel: **1 → 50 → 200 → 500 → 2.000 usuarios virtuales**. Cada corrida sostiene el nivel durante 30 s. Usa el número sin separador de miles en el comando y deja 20 s de pausa antes de subir al siguiente.

```powershell
.\Ejecutar-Lab6.ps1 -Modo Medir -BaseUrl $base -Usuarios 1
```

Cada usuario virtual realiza una consulta por iteración y espera un segundo entre consultas. Mantengan el mismo destino, el mismo portátil y el mismo estado del canal en toda la serie. Si aparecen fallos, conserven el resumen y vuelvan a ejecutar **Verificar** antes del siguiente nivel. Si no pasa, revisen el despliegue con el Grupo 1. Para la prueba de 2.000 VU, acuerden el momento con ellos para que puedan observar el servidor mientras ustedes vigilan el portátil de carga.

## Rellenar el Excel

**Ardila** completa la URL, el SHA, la fecha, ambos equipos y la versión de k6. En **Resultados**, las filas 15–19 son las corridas principales y las filas 20–24 quedan para repeticiones. Guarden el identificador del resumen junto a cada fila.

| Columna | Campo del CSV |
| --- | --- |
| Solicitudes y Solicitudes/s | `solicitudes`, `solicitudes_por_s` |
| Media (ms) y p95 (ms) | `promedio_ms`, `p95_ms` |
| Fallos HTTP (%) | `fallos_http_pct`: incluye respuestas distintas de 200 y fallos de conexión. |
| Contenido 200 inválido (%) | `fallos_contrato_200_pct`: porcentaje de respuestas 200 cuyo JSON no coincide con el perfil. |

El CSV usa punto decimal. Por ejemplo, si `fallos_http_pct` es `2.5`, escriban `2,5%` en Excel en español. Una celda vacía significa que no se pudo calcular esa métrica. `0%` indica que la métrica se calculó y no hubo fallos. Usen solo corridas de **Medir** para la curva.

En **Curva** comparen los usuarios virtuales con la latencia media. Usen p95, la tasa de solicitudes y los fallos para explicar dónde se deterioró el servicio. Si hay dudas, repitan los dos niveles que delimitan el cambio y anoten las repeticiones en su sección. Si no hay un cambio claro, escriban **«codo no observado en el rango medido»**. Si el portátil de carga llega a su límite, déjenlo en observaciones para no atribuirlo al sistema. Al terminar, hagan otra **Verificar** para comprobar la recuperación.

| Si ocurre esto | Revisen esto |
| --- | --- |
| Falla la conexión | IP y puerto de la LAN, conectividad entre los equipos y publicación de Caddy. |
| Falla el certificado | Que sea la CA pública del perfil y que el host de la URL coincida con el certificado. |
| HTTP 404 o JSON inesperado | Que sea la URL del perfil y que `check.py --seed` haya creado `lab6_canal`. |
| HTTP 503 o tiempo de espera en Verificar | Estado del Gateway y sus proveedores con el Grupo 1. No inicien la serie si la verificación falla. |

Entreguen la curva, la tabla y una explicación del intervalo del codo. Conserven los resúmenes y el SHA para poder rastrear cada dato.
