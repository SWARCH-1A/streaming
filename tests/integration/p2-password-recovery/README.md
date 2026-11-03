# Solicitud Web y entrega de recuperación local

Con el stack completo, Mailpit, Playwright Chromium y una cuenta **ficticia** `replay-fan`
en el JSON privado `P2_TEST_ACCOUNTS`:

```sh
python tests/integration/p2-password-recovery/run.py
```

La cuenta usa un handle `rel_` y correo `HANDLE@example.test`. El runner solicita recuperación
desde Web, espera un correo nuevo del worker SMTP, abre su enlace canónico y comprueba que
se elimina el fragmento del navegador y aparece el formulario. No imprime el token ni cambia
contraseñas. La confirmación desde UI es una comprobación manual; las suites Core verifican
uso único, vencimiento, concurrencia, revocación y que recuperar no reactiva una cuenta suspendida.
