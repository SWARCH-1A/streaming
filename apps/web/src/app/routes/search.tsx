import { type LoaderFunctionArgs, useLoaderData } from 'react-router';
import type { P2ExploreScreen } from '@contracts/p2';

import { DiscoveryPage, explorePath } from '@/src/modules/discovery/entry';
import { serverGatewayGet } from '@/src/server/api.server';

export async function loader({ request }: LoaderFunctionArgs) {
  try {
    return {
      gateway: true,
      screen: await serverGatewayGet<P2ExploreScreen>(
        request,
        explorePath(new URL(request.url).searchParams, true),
      ),
    };
  } catch {
    return { gateway: Boolean(process.env.WEB_GATEWAY_UPSTREAM), screen: null }; // Each section retries through the same public aggregate after hydration.
  }
}
export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}
export default function Route() {
  const { screen, gateway } = useLoaderData<typeof loader>();
  return <DiscoveryPage gateway={gateway} search {...(screen ? { initial: screen } : {})} />;
}
