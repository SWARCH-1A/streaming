import { data, type LoaderFunctionArgs, useLoaderData } from 'react-router';
import type { ChannelBootstrap, PublicSession, PublicStream } from '@contracts/p1';

import { serverGet, serverStreamGet, UpstreamError } from '@/src/server/api.server';
import { WatchRoute } from '@/src/shell/routes/WatchRoute';

export async function loader({ request, params }: LoaderFunctionArgs) {
  // Bound the complete screen read, including dependent session/channel reads.
  const bounded = new Request(request, {
    signal: AbortSignal.any([request.signal, AbortSignal.timeout(3000)]),
  });
  try {
    const stream = await serverStreamGet<PublicStream>(
      bounded,
      `/api/streams/${encodeURIComponent(params.streamId ?? '')}`,
    );
    if (stream.streamId !== params.streamId) throw new UpstreamError(503);
    const [session, channel] = await Promise.all([
      stream.sessionId
        ? serverStreamGet<PublicSession>(
            bounded,
            `/api/streams/sessions/${encodeURIComponent(stream.sessionId)}`,
          ).catch(() => {
            throw new UpstreamError(503);
          })
        : Promise.resolve(null),
      serverGet<ChannelBootstrap>(
        bounded,
        `/api/channels/${encodeURIComponent(stream.channelId)}`,
      ).catch(() => null),
    ]);
    if (
      session &&
      (session.streamId !== stream.streamId ||
        session.sessionId !== stream.sessionId ||
        session.channelId !== stream.channelId ||
        session.streamGeneration !== stream.streamGeneration)
    )
      throw new UpstreamError(503);
    return { initial: { stream, session }, channel };
  } catch (error) {
    const status = error instanceof UpstreamError && error.status === 404 ? 404 : 503;
    // eslint-disable-next-line @typescript-eslint/only-throw-error
    throw data(null, { status });
  }
}

export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}

// The initial HTML contains live metadata. HLS, quality discovery, WS and leases start in effects.
export default function Watch() {
  const { initial, channel } = useLoaderData<typeof loader>();
  return <WatchRoute key={initial.stream.streamId} initial={initial} initialChannel={channel} />;
}
