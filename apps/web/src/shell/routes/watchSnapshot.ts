import type { ChannelBootstrap, PublicSession, PublicStream } from '@contracts/p1';
import type { P2WatchScreen } from '@contracts/p2';

export interface WatchSnapshot {
  stream: PublicStream;
  session: PublicSession | null;
  channel?: ChannelBootstrap | null;
  /** Gateway only: the channel section came back UNAVAILABLE or DENIED (not merely still loading). */
  channelUnavailable?: boolean;
}

/** A rotation between the stream and session reads must not start HLS for another session. */
export function sessionMatches(stream: PublicStream, session: PublicSession | null) {
  return (
    !session ||
    (session.streamId === stream.streamId &&
      session.sessionId === stream.sessionId &&
      session.channelId === stream.channelId &&
      session.streamGeneration === stream.streamGeneration)
  );
}

/** Maps the Gateway watch aggregate to the snapshot both the SSR and client loaders render. */
export function snapshotFromScreen(screen: P2WatchScreen): WatchSnapshot {
  const channel = screen.channel;
  return {
    stream: screen.stream,
    session: screen.session,
    channel: 'data' in channel ? (channel.data ?? null) : null,
    channelUnavailable: channel.status === 'UNAVAILABLE' || channel.status === 'DENIED',
  };
}
