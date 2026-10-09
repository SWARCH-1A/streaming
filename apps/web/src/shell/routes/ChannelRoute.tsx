import { useEffect } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import type { ChannelBootstrap } from '@contracts/p1';

import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';

export function ChannelRoute() {
  const { handle = '' } = useParams();
  const resource = useResource(
    `channel:${handle}`,
    (signal) =>
      request<ChannelBootstrap>(`/api/channels/by-handle/${encodeURIComponent(handle)}`, {
        signal,
      }),
    4000,
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
      {channel.bannerUri ? (
        <img
          src={channel.bannerUri}
          alt=""
          style={{ maxWidth: '100%', maxHeight: '18rem', objectFit: 'cover' }}
        />
      ) : null}
      <div className="row">
        <Avatar
          name={profile.displayName}
          {...(profile.avatarUri ? { src: profile.avatarUri } : {})}
          size="lg"
        />
        <div>
          <h1>{profile.displayName}</h1>
          <p>@{canonical}</p>
        </div>
        <Badge tone={availability === 'PLAYABLE' ? 'live' : 'neutral'}>{availability}</Badge>
      </div>
      {availability === 'UNKNOWN' ? (
        <Notice tone="warning">
          El estado de la emisión no está disponible. Los datos del canal siguen disponibles.
        </Notice>
      ) : null}
      {stream ? (
        <Panel className="stack">
          <h2>{stream.title}</h2>
          <p>
            {stream.category.name} · {stream.tags.map((tag) => tag.name).join(' · ')}
          </p>
          {stream.sessionId ? (
            <Link to={`/watch/${stream.streamId}`}>Ver transmisión y chat</Link>
          ) : (
            <p>El canal aún no ha iniciado su emisión.</p>
          )}
        </Panel>
      ) : (
        <p>Sin emisión confirmada.</p>
      )}
      <Panel>
        <h2>Acerca del canal</h2>
        <p>{channel.description}</p>
        <p>{profile.bio}</p>
      </Panel>
    </div>
  );
}
