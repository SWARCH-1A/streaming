import { data, type LoaderFunctionArgs, useLoaderData } from 'react-router';
import type { ChannelBootstrap } from '@contracts/p1';
import type { P2Follow } from '@contracts/p2';

import { hasSession, serverGet, UpstreamError } from '@/src/server/api.server';
import { ChannelRoute } from '@/src/shell/routes/ChannelRoute';

/**
 * SPEC-18 CA-01: the HTML of /channels/{handle} already contains the channel. Until the Gateway screen
 * aggregate exists, the loader composes the channel bootstrap and, with a session, the follow state.
 * A failing follow read does not hide the channel (section-level error, like the Gateway's partial result).
 */
export async function loader({ request, params }: LoaderFunctionArgs) {
  const handle = params.handle ?? '';
  let channel: ChannelBootstrap;
  try {
    channel = await serverGet<ChannelBootstrap>(
      request,
      `/api/channels/by-handle/${encodeURIComponent(handle)}`,
    );
  } catch (error) {
    const status = error instanceof UpstreamError ? error.status : 503;
    // React Router renders the nearest ErrorBoundary with this status (real 404 HTML).
    // eslint-disable-next-line @typescript-eslint/only-throw-error
    if (status === 404 || status === 400) throw data(null, { status: 404 });
    return data({ channel: null, follow: null, handle }, { status: 503 });
  }
  let follow: P2Follow | null = null;
  if (hasSession(request)) {
    try {
      follow = await serverGet<P2Follow>(
        request,
        `/api/channels/${encodeURIComponent(channel.channel.channelId)}/followers/me`,
      );
    } catch {
      follow = null; // anonymous or unavailable: the button resolves it in the browser
    }
  }
  return { channel, follow, handle };
}

export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}

// React Router 8 passes the loader result as loaderData (the old `data` key no longer exists).
export function meta({ loaderData }: { loaderData?: { channel: ChannelBootstrap | null } }) {
  const name = loaderData?.channel?.profile.displayName;
  return [{ title: name ? `${name} · Vault tv` : 'Canal · Vault tv' }];
}

export default function Channel() {
  const { channel, follow, handle } = useLoaderData<typeof loader>();
  return (
    <ChannelRoute key={handle} {...(channel ? { initial: channel } : {})} initialFollow={follow} />
  );
}
