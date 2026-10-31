import { useState } from 'react';
import { Link, useParams } from 'react-router';
import type { ChannelBootstrap, PublicSession, PublicStream } from '@contracts/p1';

import { Button } from '@/src/components/atoms/Button';
import { BroadcastPlaceholder } from '@/src/components/molecules/BroadcastPlaceholder';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { LiveChatPanel } from '@/src/modules/chat/entry';
import { HlsPlayer } from '@/src/modules/streaming/entry';
import { request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';
import { ViewErrorBoundary } from '@/src/shell/components/ViewErrorBoundary';
import { useSession } from '@/src/shell/session/useSession';

import styles from './WatchRoute.module.css';

export interface WatchSnapshot {
  stream: PublicStream;
  session: PublicSession | null;
}

export function WatchRoute({
  initial,
  initialChannel,
}: {
  initial?: WatchSnapshot;
  initialChannel?: ChannelBootstrap | null;
} = {}) {
  const { streamId = '' } = useParams();
  const [chatVisible, setChatVisible] = useState(true);
  const { user } = useSession();
  const resource = useResource(
    `watch:${streamId}`,
    async (signal) => {
      const stream = await request<PublicStream>(`/api/streams/${encodeURIComponent(streamId)}`, {
        signal,
      });
      const session = stream.sessionId
        ? await request<PublicSession>(`/api/streams/sessions/${stream.sessionId}`, { signal })
        : null;
      if (
        session &&
        (session.streamId !== stream.streamId ||
          session.sessionId !== stream.sessionId ||
          session.channelId !== stream.channelId ||
          session.streamGeneration !== stream.streamGeneration)
      )
        throw new Error('La sesión de reproducción cambió. Reintentando…');
      return { stream, session };
    },
    4000,
    initial,
  );
  const channel = useResource(
    `watch-channel:${resource.data?.stream.channelId ?? ''}`,
    async (signal) => {
      const channelId = resource.data?.stream.channelId;
      if (!channelId) return null;
      return request<ChannelBootstrap>(`/api/channels/${channelId}`, { signal });
    },
    0,
    initialChannel ?? undefined,
  );
  if (resource.loading) return <p role="status">Cargando transmisión…</p>;
  if (!resource.data) return <Notice tone="error">{resource.error}</Notice>;
  const { stream, session } = resource.data;
  return (
    <div className="stack">
      <div className={styles.heading}>
        <h1>{stream.title}</h1>
        <Button
          variant="secondary"
          aria-expanded={chatVisible}
          aria-controls="chat"
          onClick={() => setChatVisible((previous) => !previous)}
        >
          {chatVisible ? 'Ocultar chat' : 'Mostrar chat'}
        </Button>
      </div>
      <div className={`${styles.layout} ${!chatVisible ? styles.wide : ''}`}>
        <div className="stack">
          <ViewErrorBoundary name="El reproductor">
            {session ? (
              <HlsPlayer
                key={session.sessionId}
                session={session}
                title={stream.title}
                poster={channel.data?.channel.bannerUri}
              />
            ) : (
              <BroadcastPlaceholder
                title="El canal está offline."
                description="Aquí aparecerá su próxima emisión."
                image={channel.data?.channel.bannerUri}
              />
            )}
          </ViewErrorBoundary>
          <Panel className="stack">
            {channel.data ? (
              <Link to={`/channels/${channel.data.handle}`}>
                {channel.data.profile.displayName} (@{channel.data.handle})
              </Link>
            ) : (
              <p>{channel.error ?? 'Cargando canal…'}</p>
            )}
            <p>
              {stream.category.name}
              {stream.tags.length ? ` · ${stream.tags.map((tag) => tag.name).join(' · ')}` : ''}
            </p>
            <p role="status">
              {stream.availability === 'PLAYABLE'
                ? 'En vivo'
                : stream.availability === 'RECONNECTING'
                  ? 'Reconectando'
                  : stream.status === 'PREPARING'
                    ? 'Preparando señal'
                    : 'Offline'}{' '}
              · {session?.viewerCount ?? 0} espectadores
            </p>
          </Panel>
        </div>
        {chatVisible && session ? (
          <ViewErrorBoundary name="El chat">
            <LiveChatPanel
              key={`${session.sessionId}:${user?.userId ?? 'anonymous'}`}
              sessionId={session.sessionId}
              readOnly={session.status === 'ENDED' || session.status === 'PREPARING'}
              channelId={stream.channelId}
              moderator={!!user && user.userId === channel.data?.channel.ownerUserId}
            />
          </ViewErrorBoundary>
        ) : null}
      </div>
    </div>
  );
}
