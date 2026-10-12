import { Link } from 'react-router';

import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Panel } from '@/src/components/molecules/Panel';
import type { DemoChannel } from '@/src/modules/discovery/discovery.types';

import styles from './ChannelCard.module.css';

export function ChannelCard({ channel }: { channel: DemoChannel }) {
  return (
    <Panel className={styles.card}>
      <Avatar name={channel.name} src={channel.avatar} />
      <div className={styles.content}>
        <Link to={`/channels/${channel.handle}`}>
          <h3>{channel.name}</h3>
        </Link>
        <p className="muted">@{channel.handle}</p>
      </div>
      <Badge tone={channel.status === 'LIVE' ? 'live' : 'neutral'} dot>
        {channel.status === 'LIVE' ? 'EN VIVO' : 'OFFLINE'}
      </Badge>
    </Panel>
  );
}
