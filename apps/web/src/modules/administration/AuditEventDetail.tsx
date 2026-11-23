import { useEffect, useRef } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';

import styles from './AuditPage.module.css';
import { auditActions, type AuditEntry, auditPayload, auditTime } from './auditQuery';

export function AuditEventDetail({ entry, onClose }: { entry: AuditEntry; onClose: () => void }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    heading.current?.focus();
  }, [entry.event.eventId]);
  const payload = auditPayload(entry);
  const fields = [
    ['Servicio', entry.event.producer === 'core' ? 'Core' : 'VOD'],
    ['Acción', auditActions[payload.action]],
    ['Resultado', payload.outcome === 'SUCCESS' ? 'Permitido' : 'Denegado'],
    ['Usuario', payload.actorId ?? 'Identidad no verificada'],
    ['Tipo de recurso', payload.targetType],
    ['Recurso', payload.targetId],
    ['Código de motivo', payload.reasonCode],
    ['ID de solicitud', entry.event.correlationId],
    ['ID de evento', entry.event.eventId],
    ['Posición de ingreso', entry.position],
  ];
  return (
    <section
      id="audit-event-detail"
      className={styles.detail}
      aria-labelledby="audit-detail-heading"
    >
      <header className={styles.detailHeader}>
        <div>
          <p className={styles.eyebrow}>Evidencia registrada</p>
          <h2 id="audit-detail-heading" tabIndex={-1} ref={heading}>
            Detalle del evento
          </h2>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose} aria-label="Cerrar detalle">
          <Icon name="close" />
        </Button>
      </header>
      <dl className={styles.detailFields}>
        {fields.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
        <div>
          <dt>Fecha de la acción</dt>
          <dd>
            <time dateTime={entry.event.occurredAtUtc}>{auditTime(entry.event.occurredAtUtc)}</time>
          </dd>
        </div>
        <div>
          <dt>Recibido por auditoría</dt>
          <dd>
            <time dateTime={entry.receivedAtUtc}>{auditTime(entry.receivedAtUtc)}</time>
          </dd>
        </div>
      </dl>
      <details className={styles.rawEvent}>
        <summary>Ver evento JSON</summary>
        <pre>{JSON.stringify(entry.event, null, 2)}</pre>
      </details>
    </section>
  );
}
