import { useState } from 'react';
import { Link, useParams } from 'react-router';

import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Panel } from '@/src/components/molecules/Panel';
import { TagList } from '@/src/components/molecules/TagList';
import { ChatPanel } from '@/src/modules/chat/entry';
import { demoStreams } from '@/src/modules/discovery/entry';
import type { BroadcastState } from '@/src/modules/streaming/entry';
import { Player, SignalPanel } from '@/src/modules/streaming/entry';
import { ViewErrorBoundary } from '@/src/shell/components/ViewErrorBoundary';
import { NotFoundPage } from '@/src/shell/routes/NotFoundPage';

import styles from './WatchRoute.module.css';

export function WatchRoute() {
  const { streamId } = useParams();
  const stream = demoStreams.find((item) => item.id === streamId);
  const [state, setState] = useState<BroadcastState>('LIVE');
  const [chatVisible, setChatVisible] = useState(true);
  const [chatUnavailable, setChatUnavailable] = useState(false);
  if (!stream) return <NotFoundPage />;
  return (
    <div className="stack">
      <div className={styles.heading}>
        <p className="eyebrow">Canal / {stream.channel.name}</p>
        <Button
          variant="secondary"
          size="sm"
          aria-expanded={chatVisible}
          aria-controls="chat"
          onClick={() => setChatVisible((previous) => !previous)}
        >
          {chatVisible ? 'Ocultar chat' : 'Mostrar chat'}
        </Button>
      </div>
      <div className={`${styles.layout} ${!chatVisible ? styles.wide : ''}`}>
        <div className="stack">
          <Player
            poster={stream.image}
            title={stream.title}
            state={state}
            onRetry={() => setState('LIVE')}
          />
          <Panel className="stack">
            <div className="row">
              <Badge tone="primary">DESTACADO GLOBAL</Badge>
              <Badge tone="success">60 FPS · DEMO</Badge>
            </div>
            <h1>{stream.title}</h1>
            <Link className="row" to={`/channels/${stream.channel.handle}`}>
              <Avatar src={stream.channel.avatar} name={stream.channel.name} size="lg" online />
              <div>
                <h2>{stream.channel.name}</h2>
                <p className="muted">@{stream.channel.handle}</p>
              </div>
            </Link>
            <TagList tags={[stream.category, ...stream.tags]} />
            <p className="muted">
              Bienvenidos a la transmisión en español. Comparte el directo y disfruta de la
              conversación con la comunidad.
            </p>
          </Panel>
          <SignalPanel state={state} onChange={setState} />
          <Panel className={styles.info}>
            <div>
              <h3>Acerca del directo</h3>
              <p>Un espacio para compartir partidas, aprender y disfrutar en comunidad.</p>
            </div>
            <div>
              <h3>Reglas de la sala</h3>
              <p>Respeta a los demás y mantén la conversación relacionada con la transmisión.</p>
            </div>
          </Panel>
        </div>
        {chatVisible ? (
          <div className={styles.chat}>
            <ViewErrorBoundary name="El chat">
              <ChatPanel
                key={stream.id}
                readOnly={state === 'ENDED' || state === 'OFFLINE' || state === 'PREPARING'}
                unavailable={chatUnavailable}
              />
            </ViewErrorBoundary>
            <Button
              variant="ghost"
              size="sm"
              aria-pressed={chatUnavailable}
              onClick={() => setChatUnavailable((previous) => !previous)}
            >
              {chatUnavailable ? 'Restaurar chat de demostración' : 'Simular chat no disponible'}
            </Button>
          </div>
        ) : null}
      </div>
    </div>
  );
}
