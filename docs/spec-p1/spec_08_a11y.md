# SPEC-08 Accesibilidad del recorrido P1

- **Módulo:** accessibility
- **Padre:** Ninguno
- **Prioridad:** P1

## 1. Contexto y problema

Define los criterios RNF-033…RNF-036 aplicables a navegación, formularios, reproductor, estado de emisión y chat. El alcance acordado incluye accesibilidad de la interfaz aunque subtítulos quedan fuera de P1.

## 2. Estado del sistema y brecha

Esta SPEC convierte las expectativas de teclado y semántica en controles verificables por cada dominio y en una matriz de verificación P1.

## 3. Historia de usuario

Como usuario con distintas capacidades y formas de interacción, quiero navegar y operar el flujo principal sin depender exclusivamente del mouse, color o desplazamiento automático.

## 4. Alcance

### Dentro de P1

- Operar funciones principales con teclado y foco visible/no oculto.

- Controles con nombre accesible, rol, estado y agrupación correctos; formularios con etiquetas, errores asociados y estado comprensible.

- Contraste legible en texto, controles y foco; estados no comunicados solo por color.

- Criterios mínimos verificables del recorrido P1: texto normal contraste ≥4.5:1; texto grande ≥3:1 (al menos 24 CSS px regular o 18.66 CSS px negrita); borde/estado de controles e indicador de foco ≥3:1 respecto a colores adyacentes. Controles objetivo ≥24×24 CSS px, salvo excepciones de WCAG 2.2 criterio 2.5.8.

- Texto alternativo útil para avatares/imágenes cuando transmiten identidad; decorativas se ocultan a tecnología asistiva.

- Chat permite pausar/ocultar autodesplazamiento y navegar historial sin que entren mensajes y roben foco.

- Estados LIVE/OFFLINE, carga, fallo de chat y player son anunciados sin interrumpir continuamente.

### Fuera de P1

- Subtítulos/captions, transcripción, interpretación de lengua de señas y afirmación de conformidad global con WCAG 2.2 AA.

### Supuestos acordados

- La verificación cubre los recorridos y criterios aplicables que implementa P1, registra hallazgos y no equivale a certificación universal.

- No exigir texto alternativo redundante cuando el mismo nombre está inmediatamente adyacente y el gráfico es decorativo.

## 5. Requisitos funcionales y calidad

- **RNF-035:** la funcionalidad esencial P1 es operable por teclado, con foco identificable y sin trampas.

- **RNF-036:** controles, formularios, estados y mensajes de error exponen semántica accesible.

- Aplicar RNF-033…RNF-036 según la matriz de trazabilidad y validar contraste suficiente en la interfaz.

## 6. Criterios de aceptación

- **CA-01:** completar registro/login, edición de perfil/canal, inicio de reproducción y búsqueda solo con teclado, en orden de foco lógico.

- **CA-02:** foco es visible en cada control enfocable y no queda oculto por overlays, encabezados fijos o chat autoscroll.

- **CA-03:** controles operables exponen etiqueta/nombre, rol y estado; errores se anuncian y asocian al campo afectado.

- **CA-04:** información LIVE/OFFLINE y éxito/error no depende únicamente del color.

- **CA-05:** el reproductor puede enfocarse y sus controles disponibles operarse mediante teclado; la página conserva operación básica si el chat falla.

- **CA-06:** usuario pausa/oculta el autoscroll del chat y recupera lectura sin saltos no solicitados.

- **CA-07:** inspección manual con tecnología asistiva y herramienta de análisis registra navegador, versión, recorrido y defectos encontrados.

- **CA-08:** verificación registra WCAG 2.2 AA 1.4.3 (texto), 1.4.11 (contraste no textual), 2.1.1 (teclado), 2.4.7/2.4.11 (foco visible/no oculto), 2.5.8 (tamaño objetivo) y 4.1.2 (nombre/rol/estado) en las vistas P1; un incumplimiento de los mínimos numéricos de contraste o target size reprueba esa vista. Esto no declara conformidad global WCAG.

## 7. Diseño técnico y datos

- Definir patrón común de foco, validación y live regions en shell web; preferir elementos HTML nativos y nombres explícitos.

- Adjuntar texto alternativo/contexto a imágenes en el modelo de presentación; no inferir descripción textual del archivo subido.

- Pruebas automáticas complementan, pero no sustituyen navegación manual por teclado y lector de pantalla.

- La guía aplica a los recorridos P1 los umbrales WCAG 2.2 AA de contraste 1.4.3/1.4.11 y tamaño 2.5.8, teclado 2.1.1, foco 2.4.7/2.4.11 y semántica 4.1.2. Registrar criterios excluidos y motivo; no declarar conformidad universal.

## 8. Dependencias y contratos de integración

- Todos los módulos frontend incorporan estados de foco, carga, error y ausencia de datos y siguen tokens/componentes comunes del shell web.

- El componente Chat expone control de autoscroll y límite del contenedor; Player documenta controles y alternativas de foco.

- Identity/Profile/Channels anuncian errores de validación; Discovery anuncia resultados vacíos/cambio de estado; Streaming y Chat notifican su degradación.

- El proxy no puede romper navegación directa/rutas ni ocultar errores semánticos por fallback genérico.

## 9. Decisiones y preguntas abiertas

**Acordado:** subtítulos fuera de P1, accesibilidad funcional sí; no hacer declaración global de conformidad. **No bloqueante:** equipo/owner elige lectores de pantalla/navegadores del protocolo de verificación y registra versión usada.

## 10. Verificación

- Matriz de criterios por módulo y rutas de prueba; revisión por teclado de cada vista antes de integrar.

- Medir contraste con herramienta sobre texto normal/grande, controles y foco; verificar targets de 24×24 CSS px y registrar las excepciones 2.5.8 aplicadas.
- Inspección de nombre/rol/estado, etiquetas/errores, contraste, foco, alt text y estados dinámicos.

- Pruebas manuales documentadas en lector de pantalla al menos para registro, player y chat.

- Hallazgos ligados a criterios concretos; no cerrar como “WCAG AA completo” si el alcance no cubre el estándar entero.

## 11. Esfuerzo, riesgos y consecuencias

**Esfuerzo:** M. **Riesgos:** tratar accesibilidad como acabado, mensajes que interrumpan lectura, foco perdido en microfrontends e imágenes sin contexto. **Consecuencia:** no se incluye aún accesibilidad de pistas de subtítulos/transcripción.
