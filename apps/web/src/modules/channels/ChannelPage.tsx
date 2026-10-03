import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Icon } from '@/src/components/atoms/Icon';
import { EmptyState } from '@/src/components/molecules/EmptyState';
import { Panel } from '@/src/components/molecules/Panel';
import type { DemoChannel } from '@/src/modules/discovery/entry';

import styles from './ChannelPage.module.css';

export function ChannelPage({ channel }: { channel: DemoChannel }) {
  return (
    <div className="stack">
      <div className={styles.banner}>
        {channel.stream ? <img src={channel.stream.image} alt="" /> : null}
        <div className={styles.identity}>
          <Avatar
            name={channel.name}
            src={channel.avatar}
            size="lg"
            online={channel.status === 'LIVE'}
          />
          <div>
            <p className="eyebrow">Perfil del canal</p>
            <h1>{channel.name}</h1>
            <p className="muted">@{channel.handle}</p>
          </div>
          <Badge tone={channel.status === 'LIVE' ? 'live' : 'neutral'} dot>
            {channel.status === 'LIVE' ? 'EN VIVO' : 'OFFLINE'}
          </Badge>
        </div>
      </div>
      {channel.stream ? (
        <Panel className={styles.current}>
          <div>
            <Badge tone="live" dot>
              AHORA EN DIRECTO
            </Badge>
            <h2>{channel.stream.title}</h2>
          </div>
          <ActionLink to={`/watch/${channel.stream.id}`} size="lg">
            <Icon name="play" />
            Ver transmisión
          </ActionLink>
        </Panel>
      ) : (
        <EmptyState
          title="El canal está offline"
          description="Cuando comience una nueva transmisión, podrás verla aquí."
          icon="broadcast"
          action={<ActionLink to="/">Explorar otros directos</ActionLink>}
        />
      )}
      <Panel className="stack">
        <h2>Acerca del canal</h2>
        <p className="muted">{channel.description}</p>
      </Panel>
    </div>
  );
}
