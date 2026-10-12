# Accesibilidad Web

Utilidades, patrones y componentes accesibles compartidos. Cada módulo cumple los criterios en
sus vistas, formularios y recorridos; esta carpeta no sustituye sus pruebas.
Definición: [SPEC-08](../../../../docs/spec-p1/spec_08_a11y.md).

## Patrones

- RouteFocus espera el encabezado de una ruta y no roba foco tras interacción del usuario.
- useErrorFocus enfoca una sola vez por envío fallido, después de habilitar los campos.
- fieldErrors acepta únicamente mensajes de texto para los campos conocidos del formulario.
- Las ayudas y errores usan aria-describedby; los errores de operación no invalidan otro campo.
- El Chat conserva el editor enfocable y de solo lectura mientras espera ACK. Pausar autoscroll
  también desactiva los anuncios del historial; mensajes entrantes no mueven el foco.

## Protocolo SPEC-08

Registrar commit, sistema operativo, navegador y versión, herramienta/lector y versión, ruta,
acción, resultado esperado, observado y defecto. Mantener informes/capturas fuera del repositorio.

| Criterio | Recorrido y comprobación                                                                                                                                                                                                           |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CA-01    | Registro/login, perfil/canal, estudio, búsqueda y reproducción con Tab, Shift+Tab, Enter, Espacio y Escape. Comprobar salto al contenido y orden lógico.                                                                           |
| CA-02    | Foco visible, recuperación tras errores y menú; no oculto por cabecera, diálogo, player o Chat. Repetir con carga lenta y a 320 CSS px.                                                                                            |
| CA-03    | Nombres/roles/estados, ayudas de subida, errores por campo, selección de etiquetas y controles de clave sin anunciar el secreto.                                                                                                   |
| CA-04    | LIVE/OFFLINE, error y éxito se expresan con texto, no solo color.                                                                                                                                                                  |
| CA-05    | Enfocar vídeo y operar controles nativos. Interrumpir Chat en el fixture: el vídeo debe continuar.                                                                                                                                 |
| CA-06    | Pausar historial, recibir mensajes en otra sesión, conservar scroll, borrador y foco; reanudar explícitamente.                                                                                                                     |
| CA-07    | Narrador de Windows con Edge: escuchar registro, reproductor y Chat. Registrar observación humana; Axe no sustituye esta prueba.                                                                                                   |
| CA-08    | Medir contraste y controles en estado normal, hover, foco, selección y error; texto normal 4.5:1, grande 3:1, controles/foco 3:1; targets 24×24 CSS px o excepción justificada. Revisión responsive y zoom del navegador al 200 %. |

Ejecutar `pnpm check` en apps/web y `python tests/integration/p1-domains/run.py --web` desde la
raíz, con las dependencias y Docker indicados en el README Web. El runner incluye proveedores
reales, Axe, reproducción HLS, Chat, fallos controlados y varias anchuras. Los E2E omitidos fuera
del runner no acreditan aceptación. No desactivar contraste para aprobar una vista.

La revisión humana incluye foco realmente perceptible, controles nativos de vídeo y anuncios
comprensibles, no solamente atributos DOM. Los subtítulos siguen fuera de P1. Ninguna de estas
pruebas equivale a una declaración de conformidad global WCAG.
