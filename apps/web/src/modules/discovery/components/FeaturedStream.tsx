import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Icon } from '@/src/components/atoms/Icon';
import { TagList } from '@/src/components/molecules/TagList';
import type { StreamCardData } from '@/src/components/organisms/StreamCard.types';
import { formatViewers } from '@/src/shared/format';

import styles from './FeaturedStream.module.css';

export function FeaturedStream({ stream }: { stream: StreamCardData }) {
  return (
    <section className={styles.hero} aria-labelledby="featured-title">
      <img
        className={styles.image}
        src={stream.image}
        alt=""
        fetchPriority="high"
        width="1280"
        height="720"
      />
      <div className={styles.scrim} />
      <div className={styles.hud}>
        <div className="row">
          <Badge tone="live" dot>
            EN VIVO
          </Badge>
          <span className={styles.viewers}>
            <Icon name="eye" size={16} />
            {formatViewers(stream.viewers)} espectadores
          </span>
        </div>
        <Badge tone="primary">DESTACADO</Badge>
      </div>
      <div className={styles.content}>
        <TagList tags={[stream.category, ...stream.tags]} />
        <h1 id="featured-title">{stream.title}</h1>
        <div className={styles.footer}>
          <div className="row">
            <Avatar name={stream.channel.name} src={stream.channel.avatar} online />
            <div>
              <strong>{stream.channel.name}</strong>
              <p className="muted">@{stream.channel.handle}</p>
            </div>
          </div>
          <div className="row">
            <ActionLink to={`/watch/${stream.id}`} size="lg">
              <Icon name="play" />
              Ver ahora
            </ActionLink>
            <ActionLink to={`/watch/${stream.id}#chat`} variant="secondary">
              <Icon name="chat" />
              Entrar al chat
            </ActionLink>
          </div>
        </div>
      </div>
    </section>
  );
}
