import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Panel } from '@/src/components/molecules/Panel';
import type { BroadcastState } from '@/src/modules/streaming/streaming.types';

import styles from './SignalPanel.module.css';

const states: { id: BroadcastState; label: string }[] = [
  { id: 'OFFLINE', label: 'Offline' },
  { id: 'PREPARING', label: 'Preparando' },
  { id: 'LIVE', label: 'En vivo' },
  { id: 'RECONNECT_GRACE', label: 'Reconectando' },
  { id: 'ENDED', label: 'Finalizada' },
  { id: 'ERROR', label: 'Error' },
];
export function SignalPanel({
  state,
  onChange,
}: {
  state: BroadcastState;
  onChange: (state: BroadcastState) => void;
}) {
  return (
    <Panel className={styles.panel}>
      <div className="row">
        <Icon name="broadcast" />
        <h2>Estado de la señal</h2>
        <Badge tone="primary">SIMULACIÓN LOCAL</Badge>
      </div>
      <div className={styles.states} role="group" aria-label="Simular estado de emisión">
        {states.map((item) => (
          <Button
            key={item.id}
            variant={state === item.id ? (item.id === 'LIVE' ? 'danger' : 'primary') : 'ghost'}
            size="sm"
            aria-pressed={state === item.id}
            onClick={() => onChange(item.id)}
          >
            {item.label}
          </Button>
        ))}
      </div>
      <p className="muted" role="status">
        {states.find((item) => item.id === state)?.label} · Los cambios afectan únicamente a esta
        vista previa.
      </p>
    </Panel>
  );
}
