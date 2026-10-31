import { data, type LoaderFunctionArgs, useLoaderData } from 'react-router';
import type { ChannelBootstrap } from '@contracts/p1';
import type { P2ChannelScreen, P2Follow } from '@contracts/p2';

import { hasSession, serverGatewayGet, serverGet, UpstreamError } from '@/src/server/api.server';
import { ChannelRoute } from '@/src/shell/routes/ChannelRoute';

/** SSR uses the Gateway aggregate when deployed; standalone Core retains its existing loader. */
export async function loader({ request, params }: LoaderFunctionArgs) {
  const handle = params.handle ?? '';
  if (process.env.WEB_GATEWAY_UPSTREAM) {
    try {
      const screen = await serverGatewayGet<P2ChannelScreen>(
        request,
        `/api/screens/channels/${encodeURIComponent(handle)}`,
      );
      return {
        channel: screen.channel,
        follow: 'data' in screen.follow ? (screen.follow.data ?? null) : null,
        handle,
        ...('data' in screen.vods && screen.vods.data ? { initialVods: screen.vods.data } : {}),
        ...(screen.vods.status === 'UNAVAILABLE'
          ? { vodError: 'Las grabaciones no están disponibles. Puedes reintentar la consulta.' }
          : {}),
      };
    } catch (error) {
      const status = error instanceof UpstreamError ? error.status : 503;
      if ([400, 401, 403, 404].includes(status)) {
        // eslint-disable-next-line @typescript-eslint/only-throw-error
        throw data(null, { status: status === 400 ? 404 : status });
      }
      return data({ channel: null, follow: null, handle }, { status: 503 });
    }
  }
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
  const result = useLoaderData<typeof loader>();
  const { channel, follow, handle } = result;
  return (
    <ChannelRoute
      key={handle}
      {...(channel ? { initial: channel } : {})}
      initialFollow={follow}
      {...('initialVods' in result && result.initialVods
        ? { initialVods: result.initialVods }
        : {})}
      {...('vodError' in result && result.vodError ? { vodError: result.vodError } : {})}
    />
  );
}
