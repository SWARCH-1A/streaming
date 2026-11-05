import { useState } from 'react';
import { Link, useParams } from 'react-router';
import type { ChannelBootstrap, PublicSession, PublicStream } from '@contracts/p1';
import type { P2WatchScreen } from '@contracts/p2';

import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
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
import { sessionMatches, snapshotFromScreen, type WatchSnapshot } from './watchSnapshot';

function playbackLabel(stream: PublicStream) {
  return stream.availability === 'PLAYABLE'
    ? 'En vivo'
    : stream.availability === 'RECONNECTING'
      ? 'Reconectando'
      : stream.status === 'PREPARING'
        ? 'Preparando señal'
        : 'Offline';
}

function playbackTone(stream: PublicStream): 'neutral' | 'live' | 'warning' {
  return stream.availability === 'PLAYABLE'
    ? 'live'
    : stream.availability === 'RECONNECTING'
      ? 'warning'
      : 'neutral';
}

export function WatchRoute({
  initial,
  initialChannel,
  gateway = false,
}: {
  initial?: WatchSnapshot;
  initialChannel?: ChannelBootstrap | null;
  gateway?: boolean;
} = {}) {
  const { streamId = '' } = useParams();
  const [chatVisible, setChatVisible] = useState(true);
  const { user } = useSession();
  const resource = useResource(
    `watch:${gateway}:${streamId}`,
    async (signal) => {
      const bounded = AbortSignal.any([signal, AbortSignal.timeout(2500)]);
      if (gateway) {
        const screen = await request<P2WatchScreen>(
          `/api/screens/watch/${encodeURIComponent(streamId)}`,
          { signal: bounded },
        );
        if (screen.stream.streamId !== streamId || !sessionMatches(screen.stream, screen.session))
          throw new Error('La sesión de reproducción cambió. Reintentando…');
        return snapshotFromScreen(screen);
      }
      const stream = await request<PublicStream>(`/api/streams/${encodeURIComponent(streamId)}`, {
        signal: bounded,
      });
      const session = stream.sessionId
        ? await request<PublicSession>(`/api/streams/sessions/${stream.sessionId}`, {
            signal: bounded,
          })
        : null;
      if (!sessionMatches(stream, session))
        throw new Error('La sesión de reproducción cambió. Reintentando…');
      return { stream, session };
    },
    2000,
    initial,
  );
  const channel = useResource(
    `watch-channel:${resource.data?.stream.channelId ?? ''}`,
    async (signal) => {
      const channelId = resource.data?.stream.channelId;
      if (gateway || !channelId) return null;
      return request<ChannelBootstrap>(`/api/channels/${channelId}`, { signal });
    },
    0,
    initialChannel ?? undefined,
  );
  const channelData = gateway ? resource.data?.channel : channel.data;
  if (resource.loading) return <p role="status">Cargando transmisión…</p>;
  if (!resource.data) return <Notice tone="error">{resource.error}</Notice>;
  const { stream, session } = resource.data;
  const viewerCount = session?.viewerCount ?? stream.viewerCount ?? 0;
  const status = playbackLabel(stream);
  return (
    <div className={styles.page}>
      <div className={styles.routeBar}>
        <div className={styles.routeMeta}>
          <span className={styles.routeLabel}>Ruta actual</span>
          <span className={styles.routePath}>
            <span className={styles.routeDot} aria-hidden="true" />
            /watch/{streamId}
          </span>
        </div>
        <div className={styles.routeActions}>
          <Badge tone="primary" dot>
            Reproductor en vivo
          </Badge>
          <Button
            variant="secondary"
            size="sm"
            className={styles.chatToggle}
            aria-expanded={chatVisible}
            aria-controls="chat"
            onClick={() => setChatVisible((previous) => !previous)}
          >
            {chatVisible ? 'Ocultar chat' : 'Mostrar chat'}
          </Button>
        </div>
      </div>
      <div className={`${styles.layout} ${!chatVisible ? styles.wide : ''}`}>
        <section className={styles.primaryColumn} aria-labelledby="stream-title">
          <ViewErrorBoundary name="El reproductor">
            {session ? (
              <HlsPlayer
                key={session.sessionId}
                session={session}
                title={stream.title}
                poster={channelData?.channel.bannerUri}
                viewerCount={viewerCount}
              />
            ) : (
              <BroadcastPlaceholder
                title="El canal está offline."
                description="Aquí aparecerá su próxima emisión."
                image={channelData?.channel.bannerUri}
              />
            )}
          </ViewErrorBoundary>
          <Panel className={styles.streamInfo}>
            <div className={styles.metaRow}>
              <Badge tone={playbackTone(stream)} dot>
                {status}
              </Badge>
              <Badge tone="neutral">{stream.category.name}</Badge>
              {stream.tags.map((tag) => (
                <span key={tag.id} className={styles.tag}>
                  #{tag.name}
                </span>
              ))}
            </div>
            <h1 id="stream-title">{stream.title}</h1>
            <div className={styles.channelRow}>
              {channelData ? (
                <Link className={styles.channelIdentity} to={`/channels/${channelData.handle}`}>
                  <Avatar
                    name={channelData.profile.displayName}
                    {...(channelData.profile.avatarUri
                      ? { src: channelData.profile.avatarUri }
                      : {})}
                    size="lg"
                    online={stream.availability === 'PLAYABLE'}
                  />
                  <span className={styles.channelCopy}>
                    <span className={styles.channelName}>{channelData.profile.displayName}</span>
                    <span className={styles.channelHandle}>
                      @{channelData.handle} · {viewerCount.toLocaleString('es-CO')} espectadores
                    </span>
                  </span>
                </Link>
              ) : (
                <p className={styles.channelFallback}>
                  {channel.error ??
                    (gateway && resource.data.channelUnavailable
                      ? 'No pudimos cargar la información del canal.'
                      : 'Cargando canal…')}
                </p>
              )}
              <div className={styles.sessionState}>
                <span>Estado de sesión</span>
                <strong role="status">{status}</strong>
              </div>
            </div>
            {channelData?.channel.description || channelData?.profile.bio ? (
              <p className={styles.description}>
                {channelData.channel.description || channelData.profile.bio}
              </p>
            ) : null}
          </Panel>
        </section>
        {chatVisible && session ? (
          <ViewErrorBoundary name="El chat">
            <LiveChatPanel
              key={`${session.sessionId}:${user?.userId ?? 'anonymous'}`}
              sessionId={session.sessionId}
              readOnly={session.status === 'ENDED' || session.status === 'PREPARING'}
              channelId={stream.channelId}
              moderator={!!user && user.userId === channelData?.channel.ownerUserId}
            />
          </ViewErrorBoundary>
        ) : null}
      </div>
    </div>
  );
}
