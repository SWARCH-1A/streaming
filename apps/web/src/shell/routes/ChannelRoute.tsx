import { useEffect } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import type { ChannelBootstrap } from '@contracts/p1';
import type { P2Follow } from '@contracts/p2';

import artwork from '@/public/images/signal-grid.svg';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { FollowButton } from '@/src/modules/channels/entry';
import { request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';

import styles from './ChannelRoute.module.css';

interface ChannelRouteProps {
  /** Bootstrap rendered by the SSR loader; the browser only revalidates it every 4 s. */
  initial?: ChannelBootstrap;
  initialFollow?: P2Follow | null;
}

export function ChannelRoute({ initial, initialFollow = null }: ChannelRouteProps = {}) {
  const { handle = '' } = useParams();
  const resource = useResource(
    `channel:${handle}`,
    (signal) =>
      request<ChannelBootstrap>(`/api/channels/by-handle/${encodeURIComponent(handle)}`, {
        signal,
      }),
    4000,
    initial,
  );
  const navigate = useNavigate();
  const canonical = resource.data?.handle;
  useEffect(() => {
    if (canonical && handle !== canonical)
      void navigate(`/channels/${canonical}`, { replace: true });
  }, [canonical, handle, navigate]);
  if (resource.loading) return <p role="status">Cargando canal…</p>;
  if (!resource.data) return <Notice tone="error">{resource.error}</Notice>;
  const { channel, profile, stream, availability } = resource.data;
  return (
    <div className="stack">
      <header className={styles.banner}>
        <img src={channel.bannerUri || artwork} alt="" width="1280" height="480" />
        <div className={styles.identity}>
          <Avatar
            name={profile.displayName}
            {...(profile.avatarUri ? { src: profile.avatarUri } : {})}
            size="lg"
          />
          <div>
            <p className="eyebrow">Canal</p>
            <h1>{profile.displayName}</h1>
            <p>@{canonical}</p>
          </div>
          <FollowButton
            channelId={channel.channelId}
            ownerUserId={channel.ownerUserId}
            initial={initialFollow}
          />
          <Badge tone={availability === 'PLAYABLE' ? 'live' : 'neutral'}>
            {availability === 'PLAYABLE'
              ? 'En vivo'
              : availability === 'RECONNECTING'
                ? 'Reconectando'
                : availability === 'UNKNOWN'
                  ? 'Estado no disponible'
                  : 'Offline'}
          </Badge>
        </div>
      </header>
      {availability === 'UNKNOWN' ? (
        <Notice tone="warning">
          El estado de la emisión no está disponible. Los datos del canal siguen disponibles.
        </Notice>
      ) : null}
      {stream ? (
        <Panel className="stack">
          <p className="eyebrow">
            {availability === 'PLAYABLE' ? 'Ahora en directo' : 'Emisión del canal'}
          </p>
          <h2>{stream.title}</h2>
          <p>
            {stream.category.name}
            {stream.tags.length ? ` · ${stream.tags.map((tag) => tag.name).join(' · ')}` : ''}
          </p>
          {stream.sessionId ? (
            <Link to={`/watch/${stream.streamId}`}>Ver transmisión y chat</Link>
          ) : (
            <p>El canal aún no ha iniciado su emisión.</p>
          )}
        </Panel>
      ) : (
        <Panel className="stack">
          <h2>
            {availability === 'UNKNOWN' ? 'Emisión no disponible' : 'Este canal está offline'}
          </h2>
          <p>
            {availability === 'UNKNOWN'
              ? 'Puedes consultar la información del canal mientras se recupera su estado.'
              : 'Vuelve para su próxima emisión o descubre otros canales en vivo.'}
          </p>
          <ActionLink to="/" variant="secondary">
            Explorar directos
          </ActionLink>
        </Panel>
      )}
      <Panel>
        <h2>Acerca del canal</h2>
        <p>{channel.description || 'Este canal aún no ha añadido una descripción.'}</p>
        {profile.bio ? <p>{profile.bio}</p> : null}
      </Panel>
    </div>
  );
}
