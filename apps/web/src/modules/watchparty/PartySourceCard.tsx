import { Link } from 'react-router';

import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { BroadcastPlaceholder } from '@/src/components/molecules/BroadcastPlaceholder';
import { Panel } from '@/src/components/molecules/Panel';
import { ViewErrorBoundary } from '@/src/shell/components/ViewErrorBoundary';

import type { PartySource } from './api';
import { PartySourcePlayer } from './PartySourcePlayer';
import styles from './WatchParty.module.css';

type State = PartySource['state'];

// Text, not color, carries each state; none of the non-playable ones pretends to be a live signal.
const labels: Record<State, { badge: string; title: string; detail: string }> = {
  PLAYABLE: { badge: 'EN VIVO', title: 'En vivo', detail: '' },
  RECONNECTING: {
    badge: 'RECONECTANDO',
    title: 'Reconectando la señal',
    detail: 'El emisor perdió la conexión temporalmente. Vuelve en unos segundos.',
  },
  OFFLINE: {
    badge: 'OFFLINE',
    title: 'El canal está offline',
    detail: 'Aquí volverá a aparecer cuando emita de nuevo.',
  },
  ENDED: {
    badge: 'FINALIZADA',
    title: 'La emisión ha finalizado',
    detail: 'La sala la conserva marcada hasta que quien la creó la quite.',
  },
  SUSPENDED: {
    badge: 'NO DISPONIBLE',
    title: 'Fuente no disponible',
    detail: 'Este canal está suspendido y no puede reproducirse desde la sala.',
  },
  UNKNOWN: {
    badge: 'SIN CONFIRMAR',
    title: 'Estado no confirmado',
    detail: 'No pudimos confirmar el estado de esta emisión; no se reproduce hasta confirmarlo.',
  },
};

export function PartySourceCard({
  source,
  roomOpen,
  canRemove,
  busy,
  onRemove,
}: {
  source: PartySource;
  roomOpen: boolean;
  canRemove: boolean;
  busy: boolean;
  onRemove: (source: PartySource) => void;
}) {
  const label = labels[source.state];
  const playing = roomOpen && source.state === 'PLAYABLE' && !!source.sessionId;
  const heading = source.title ?? 'Fuente sin información';
  const channel = source.channel;
  return (
    <Panel className={`stack ${styles.source}`} role="group" aria-label={heading}>
      {playing && source.sessionId ? (
        <ViewErrorBoundary name="El reproductor">
          <PartySourcePlayer
            key={source.sessionId}
            sessionId={source.sessionId}
            streamId={source.streamId}
            title={heading}
          />
        </ViewErrorBoundary>
      ) : (
        <BroadcastPlaceholder
          title={roomOpen ? label.title : 'Sala cerrada'}
          description={roomOpen ? label.detail : 'Esta sala ya no entrega transmisiones.'}
        />
      )}
      <div className={styles.sourceInfo}>
        <h3>{heading}</h3>
        <Badge tone={playing ? 'live' : source.state === 'SUSPENDED' ? 'error' : 'neutral'} dot>
          {roomOpen ? label.badge : 'SALA CERRADA'}
        </Badge>
      </div>
      {channel ? (
        <p>
          <Link to={`/channels/${channel.handle}`}>
            {channel.displayName} (@{channel.handle})
          </Link>
        </p>
      ) : null}
      {source.category ? (
        <p className="muted">
          {source.category}
          {source.viewerCount !== null ? ` · ${source.viewerCount} espectadores` : ''}
        </p>
      ) : null}
      {canRemove ? (
        <Button
          variant="secondary"
          size="sm"
          disabled={busy}
          aria-label={`Quitar ${heading} de la sala`}
          onClick={() => onRemove(source)}
        >
          Quitar de la sala
        </Button>
      ) : null}
    </Panel>
  );
}
