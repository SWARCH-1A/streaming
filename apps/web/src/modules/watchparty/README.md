# Salas de visualización conjunta

Interfaz de [SPEC-21](../../../../../docs/spec-p2/spec_21_watch_party.md) (RF-046…051). Habla con
Core por `/api/watch-parties*` y reproduce cada fuente con el `HlsPlayer` existente.

- `/watch-parties`: crear una sala (título) y salas recientes de este navegador. Solo guarda
  `partyId` y título por usuario en `localStorage`; nunca el código de acceso. Sin almacenamiento
  la página funciona igual.
- `/watch-parties/join`: unirse con el código. El enlace compartido lleva el código en el
  fragmento `#…` (el navegador no lo envía al servidor); al entrar, la navegación reemplaza esa
  entrada del historial para que el secreto no quede en él. Es una ruta pública a propósito: sin
  sesión explica que hay que iniciar sesión o crear una cuenta y volver a abrir el enlace, en vez
  de redirigir a `/login` y perder el enlace.
- `/watch-parties/:partyId`: la sala. Sondea cada 5 s, muestra hasta 4 fuentes (1 columna; 2 × 2
  desde 56 rem) y conserva la última información si una lectura falla.

Cada fuente `PLAYABLE` monta su propio `HlsPlayer`; al dejar de serlo, quitarla, cerrar la sala o
salir de la página, el player se desmonta y libera su lease. Las fuentes ENDED, OFFLINE,
RECONNECTING, UNKNOWN y SUSPENDED muestran su estado sin simular señal. No hay sincronía entre
participantes, chat propio ni invitaciones: el código es la única forma de entrar.

Solo el propietario ve los controles (añadir fuente desde las 50 emisiones en vivo más populares de Discovery,
quitar, rotar el código y cerrar con confirmación). El código nuevo vive solo en la memoria de la
pestaña hasta que se oculta o se recarga. Cada mutación lleva `Idempotency-Key`; repetir la misma
acción en el minuto siguiente a un fallo incierto (red o 5xx) reutiliza la clave, y cualquier éxito o
respuesta definitiva la descarta. Los errores del contrato se traducen en
`api.ts`.

La entrada pública es `entry.tsx`. Las pruebas locales están junto al código.
