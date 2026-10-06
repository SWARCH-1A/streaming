import { useState } from 'react';

import monitor from '@/public/images/studio-0.jpg';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Metric } from '@/src/components/molecules/Metric';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { IngestPanel } from '@/src/modules/streaming/components/IngestPanel';
import { MetadataForm } from '@/src/modules/streaming/components/MetadataForm';
import { Player } from '@/src/modules/streaming/components/Player';
import { SignalPanel } from '@/src/modules/streaming/components/SignalPanel';
import { useSession } from '@/src/shell/session/useSession';

import type { BroadcastState, StreamMetadata } from './streaming.types';
import styles from './StudioPage.module.css';

export function StudioPage() {
  const [state, setState] = useState<BroadcastState>('LIVE');
  const [metadata, setMetadata] = useState<StreamMetadata>({
    title: 'Desarrollando una arquitectura de streaming',
    categoryId: 'science',
    tagIds: ['programming', 'spanish', 'educational'],
  });
  const { user } = useSession();
  const active = state === 'LIVE' || state === 'PREPARING' || state === 'RECONNECT_GRACE';
  return (
    <div className="stack">
      <div className={styles.heading}>
        <div>
          <p className="eyebrow">Tu espacio de creación</p>
          <h1>Estudio de emisión</h1>
        </div>
        <ActionLink to="/profile" variant="secondary">
          <Icon name="user" />
          Mi perfil
        </ActionLink>
      </div>
      <nav className="row" aria-label="Herramientas del creador">
        <ActionLink to="/studio/channel" variant="secondary">
          Mi canal & portada
        </ActionLink>
        <ActionLink to="/profile" variant="ghost">
          Perfil
        </ActionLink>
        <ActionLink to="/prototype" variant="ghost">
          Prototipo interactivo
        </ActionLink>
      </nav>
      {!user ? (
        <Notice>
          Estudio de ejemplo. Puedes explorar todos los controles sin conectar un emisor real.
        </Notice>
      ) : null}
      <SignalPanel state={state} onChange={setState} />
      <Panel className={styles.metrics}>
        <Metric label="Tiempo al aire · demo" value={active ? '02:45:21' : '00:00:00'} />
        <Metric label="Espectadores · demo" value={state === 'LIVE' ? '3.420' : '0'} />
        <Badge tone={active ? 'success' : 'neutral'} dot>
          {active ? '6500 kbps · 60 fps · demo' : 'Sin señal'}
        </Badge>
        <Button variant="danger" disabled={!active} onClick={() => setState('ENDED')}>
          <Icon name="broadcast" />
          Terminar demostración
        </Button>
      </Panel>
      <div className={styles.grid}>
        <div className="stack">
          <Panel className="stack">
            <h2>Monitor de retorno</h2>
            <Player
              poster={monitor}
              title={metadata.title}
              state={state}
              onRetry={() => setState('LIVE')}
            />
            <p className="muted">{metadata.title}</p>
          </Panel>
          <IngestPanel canRotate={state === 'OFFLINE' || state === 'ENDED'} />
        </div>
        <div>
          <MetadataForm onSave={setMetadata} />
        </div>
      </div>
    </div>
  );
}
