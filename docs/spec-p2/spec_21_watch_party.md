# SPEC-21 — Watch parties

**Prioridad:** P2. **Responsable:** Responsable de watch parties. **Estado:** diseño para desarrollo.
**RF:** RF-046…051. **RNF:** RNF-022…030, 038, 041…047.

## 1. Contexto y problema

La plataforma incluye visualización conjunta de varias emisiones. Acceso, permisos y ciclo de
sala deben resolverse por su responsable antes de cerrar contratos dependientes de esas políticas.

## 2. Definición del componente

Capacidad de aplicación con autoridad propia de sala, fuentes y participantes según política.
Su ubicación dentro de Core u otra unidad se justifica con ADR; esta SPEC no crea un proceso
ni permite contar una unidad aún no seleccionada para el mínimo académico.

## 3. Historia de usuario

Como usuario autorizado, quiero crear una sala con varias emisiones, añadir/retirar fuentes
y consultar los canales incluidos.

## 4. Alcance

Crear/consultar sala, agregar/retirar streams, mostrarlos simultáneamente y permitir acceso
según política. RF-049 no exige una mezcla audiovisual, chat nuevo, invitaciones o sincronía
entre dispositivos; esas extensiones quedan sujetas a P2-03.

## 5. Requisitos funcionales

RF-046 crea sala por usuario autorizado; RF-047/048 modifican sus emisiones; RF-049 reproduce
varias fuentes; RF-050 permite acceso; RF-051 muestra canal e información de cada fuente.
Cada fuente conserva autoridad de estado y acceso en su servicio. No se copian secretos de
ingesta ni se usa una sala para eludir suspensión de un canal.

## 6. Criterios de aceptación

- [ ] CA-01: el responsable cierra acceso/roles, cantidad de fuentes, ciclo y comportamiento al acabar una emisión en P2-01…03.
- [ ] CA-02: contrato de crear/consultar/agregar/retirar/cerrar tiene schemas, errores y permisos consistentes con esas decisiones.
- [ ] CA-03: usuario autorizado crea una sala y añade/retira fuentes sin duplicación; usuario sin permiso es rechazado.
- [ ] CA-04: varias fuentes reales se reproducen con su canal visible; caída/fin de una se presenta sin inventar una fuente activa.
- [ ] CA-05: duplicar comandos o reiniciar conserva sala y membresía según ciclo acordado.
- [ ] CA-06: fuente suspendida deja de poder consumirse desde la sala; el resto conserva su estado.
- [ ] CA-07: cliente maneja el límite elegido y errores por fuente sin conexiones o leases huérfanos.

## 7. Diseño técnico y datos

Datos mínimos: partyId, creador, referencias a streamId/sessionId y versión. Política de acceso,
miembros, estados y restricciones dependen de P2-01…03; no se fija su representación antes de
seleccionarlas. Cambios de sala requieren control de concurrencia e idempotencia.

Los contratos de cada fuente continúan disponibles por Gateway. La composición simultánea en
cliente no genera una nueva emisión ni evade leases de cada player. Si el responsable requiere
mezcla audiovisual, debe definir su procesamiento y recursos en la SPEC/ADR, sin atribuirlo a
un requisito que solo exige visualización simultánea.

## 8. Dependencias y contratos de integración

`contracts/p2.json.pendingInterfaces` inventaría operaciones necesarias y bloqueos.
Streaming provee estado/player/leases; Core, canal/permisos; Gateway, entrada y composición;
Web/móvil, presentación. No hay endpoint P2 listo para integrar mientras no se cierre acceso.

## 9. Decisiones y preguntas abiertas

P2-01: pública/privada/enlace y roles. P2-02: máximo, disposición, salida del creador y fin de
fuente. P2-03: sincronía, chat, invitaciones/mezcla y cierre. La resolución corresponde al
responsable; debe actualizar esta SPEC y publicar schemas antes de implementar interfaces.

## 10. Verificación

Después de cerrar políticas, ejecutar matriz de creador/participante/visitante/tercero,
concurrencia, fuente finalizada/suspendida y recuperación. Probar varias reproducciones reales,
no tarjetas estáticas. Los criterios pendientes no se marcan aprobados.

## 11. Esfuerzo, riesgos y consecuencias

Esfuerzo dependiente de P2-01…03. La interfaz de sala está bloqueada por políticas, pero los
players y contratos de fuente se pueden implementar y comprobar por separado.
