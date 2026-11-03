# Recorridos locales de integración

Ejecutar desde la raíz del repositorio. El perfil canónico se prepara con
`python3 infra/local/dev.py init`, `build`, `up`; usa HTTPS 3445, CA privada en
`infra/p1/.state-local/ca.crt`, Mailpit 8025 y volúmenes persistentes. Requisitos Python/Playwright:
[guía de entrega](p1-delivery/README.md). Nunca usar cuentas ni dispositivos personales.

Los escenarios repetibles aceptan `P2_TEST_ACCOUNTS=/ruta/privada/fixtures.json` (0600, fuera de Git).
Cada entrada `replay-owner`, `replay-fan`, `ssr-owner`, `ssr-fan` contiene `handle`, `password` y
`account` (respuesta pública de registro). Deben ser cuentas locales ficticias existentes;
los recorridos de administración/suspensión exigen prefijo `rel_`. No imprimir el archivo.
Las cuentas separadas permiten ejecutar pruebas sin agotar cuotas creando usuarios repetidos.

| Recorrido | Ejecución y efecto |
| --- | --- |
| SSR / hidratación | `python tests/integration/p2-ssr-browser/run.py`; usa backend real y cuentas ficticias |
| Captura / replay / moderación | `python tests/integration/p2-chat-replay/run.py`; publica RTMPS y deja VOD de prueba |
| Suspensión live | `python tests/integration/p2-account-live/run.py`; provisiona ADMIN solo a la fixture `ssr-fan`, suspende/reactiva `ssr-owner` y recupera su estado al salir |
| Multicalidad | [p2-multirendition](p2-multirendition/README.md); cuatro pistas reales y reconexión opcional, sin transcodificación del servidor |
| VOD aislado | `python tests/integration/p2-vod/run.py`; contenedores desechables, captura nativa y caídas del recorder |
| Recuperación por correo | [p2-password-recovery](p2-password-recovery/README.md); solicitud, Mailpit, enlace/formulario; confirmación UI manual |
| Android | `python tests/integration/p2-mobile/run.py --serial emulator-NNNN --adb /ruta/adb --reset-fixture-app`; borra solo `dev.swarch.vault` en el emulador dedicado seleccionado, nunca un dispositivo físico |
| Restore | `python tests/integration/p2-recovery/run.py --backup /ruta/privada/backup --prefix streaming-restore-NOMBRE`; solo lectura contra una restauración aislada previamente arrancada |

Android requiere el APK instalado, CA confiada y `adb reverse tcp:3445 tcp:3445`.
La matriz ejecutada y la limitación del decoder Goldfish están en
[apps/mobile](../../apps/mobile/README.md). FCM/APNs y sus adaptadores conservan su
[pendiente explícito](../../apps/mobile/PUSH.md); estos runners no simulan una entrega push exitosa.

Backup/restauración y validación de checksums están en [infra/local](../../infra/local/README.md).
El smoke RabbitMQ usa `python infra/broker/smoke_test.py` y crea/limpia exclusivamente su proyecto
aleatorio. Los logs, capturas, fuentes audiovisuales y claves de estos recorridos permanecen
fuera de Git. Consultar cada runner antes de ejecutarlo y detener emisiones ficticias al finalizar.
