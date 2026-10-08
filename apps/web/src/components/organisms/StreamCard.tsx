import { Link } from 'react-router';

import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Icon } from '@/src/components/atoms/Icon';
import { TagList } from '@/src/components/molecules/TagList';
import { formatViewers } from '@/src/shared/format';

import styles from './StreamCard.module.css';
import type { StreamCardData } from './StreamCard.types';

interface StreamCardProps {
  stream: StreamCardData;
}
export function StreamCard({ stream }: StreamCardProps) {
  return (
    <article className={styles.card}>
      <Link
        to={`/watch/${stream.id}`}
        className={styles.preview}
        aria-label={`Ver ${stream.title}`}
      >
        <img src={stream.image} alt="" loading="lazy" width="640" height="360" />
        <span className={styles.live}>
          <Badge tone="live" dot>
            EN VIVO
          </Badge>
        </span>
        <span className={styles.viewers}>
          <Icon name="eye" size={14} />
          {formatViewers(stream.viewers)}
          <span className="sr-only"> espectadores</span>
        </span>
      </Link>
      <div className={styles.metadata}>
        <Avatar name={stream.channel.name} src={stream.channel.avatar} size="sm" />
        <div className={styles.details}>
          <Link to={`/watch/${stream.id}`}>
            <h3 title={stream.title}>{stream.title}</h3>
          </Link>
          <Link className={styles.channel} to={`/channels/${stream.channel.handle}`}>
            {stream.channel.name}
          </Link>
          <TagList tags={[stream.category, ...stream.tags.slice(0, 2)]} />
        </div>
      </div>
    </article>
  );
}
