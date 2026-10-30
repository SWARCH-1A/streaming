import { data, type LoaderFunctionArgs, useLoaderData } from 'react-router';
import type { P2VodScreen } from '@contracts/p2';

import { VodPage } from '@/src/modules/vod/entry';
import { serverGatewayGet, UpstreamError } from '@/src/server/api.server';

export async function loader({ request, params }: LoaderFunctionArgs) {
  try {
    return await serverGatewayGet<P2VodScreen>(
      request,
      `/api/screens/vods/${encodeURIComponent(params.vodId ?? '')}`,
    );
  } catch (error) {
    // eslint-disable-next-line @typescript-eslint/only-throw-error
    throw data(null, {
      status:
        error instanceof UpstreamError && [401, 403, 404, 410].includes(error.status)
          ? error.status
          : 503,
    });
  }
}
export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}
export default function Vod() {
  const { vod } = useLoaderData<typeof loader>();
  return <VodPage key={vod.vodId} vod={vod} />;
}
