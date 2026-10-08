import { useState } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';

import styles from './IngestPanel.module.css';

export function IngestPanel({ canRotate }: { canRotate: boolean }) {
  const [visible, setVisible] = useState(false);
  const [generation, setGeneration] = useState(1);
  const [message, setMessage] = useState('');
  const demoKey = `DEMO-NOT-A-REAL-KEY-${generation}`;
  async function copy(value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setMessage('Valor de ejemplo copiado.');
    } catch {
      setMessage('No se pudo copiar. Selecciona el campo y copia el valor manualmente.');
    }
  }
  return (
    <Panel className="stack">
      <div className="row">
        <Icon name="key" />
        <h2>Configuración RTMP</h2>
      </div>
      <FormField id="ingest-url" label="Servidor de ejemplo">
        <div className={styles.field}>
          <Input id="ingest-url" value="rtmp://ingest.example.invalid/live" readOnly />
          <Button
            variant="secondary"
            onClick={() => {
              void copy('rtmp://ingest.example.invalid/live');
            }}
            aria-label="Copiar servidor de ejemplo"
          >
            <Icon name="copy" />
          </Button>
        </div>
      </FormField>
      <FormField id="ingest-key" label="Clave de ejemplo">
        <div className={styles.field}>
          <Input id="ingest-key" value={demoKey} type={visible ? 'text' : 'password'} readOnly />
          <Button
            size="icon"
            variant="ghost"
            aria-label={visible ? 'Ocultar clave de ejemplo' : 'Mostrar clave de ejemplo'}
            aria-pressed={visible}
            onClick={() => setVisible((previous) => !previous)}
          >
            <Icon name="eye" />
          </Button>
          <Button
            variant="secondary"
            aria-label="Copiar clave de ejemplo"
            onClick={() => {
              void copy(demoKey);
            }}
          >
            <Icon name="copy" />
          </Button>
        </div>
      </FormField>
      <Notice>
        Estos valores son ficticios. La rotación solo se habilita cuando la emisión está offline o
        finalizada.
      </Notice>
      <Button
        variant="secondary"
        disabled={!canRotate}
        onClick={() => {
          setGeneration((previous) => previous + 1);
          setMessage('Clave de ejemplo rotada.');
        }}
      >
        <Icon name="refresh" />
        Rotar clave de ejemplo
      </Button>
      {message ? (
        <p role="status" className="muted">
          {message}
        </p>
      ) : null}
    </Panel>
  );
}
