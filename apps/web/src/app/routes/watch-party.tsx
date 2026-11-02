import { data, type LoaderFunctionArgs, useLoaderData } from 'react-router';

import { type Party, PARTY_ID, WatchPartyRoom } from '@/src/modules/watchparty/entry';
import { serverGet, UpstreamError } from '@/src/server/api.server';

export function meta() {
  return [{ title: 'Sala de visualización conjunta · Vault tv' }];
}

// The room is read on the server with the visitor's own session, so the HTML already has its title, channels
// and source states. Playback, leases and later polling stay in the browser.
export async function loader({ request, params }: LoaderFunctionArgs) {
  const partyId = params.partyId ?? '';
  if (!PARTY_ID.test(partyId)) return { party: null };
  try {
    const party = await serverGet<Party>(
      request,
      `/api/watch-parties/${encodeURIComponent(partyId)}`,
    );
    return { party: party.partyId === partyId ? party : null };
  } catch (error) {
    // Core answers 404 alike for a missing room and for one the visitor cannot see.
    // eslint-disable-next-line @typescript-eslint/only-throw-error
    if (error instanceof UpstreamError && error.status === 404) throw data(null, { status: 404 });
    // Any other failure leaves the shell to the browser, which retries on its own.
    return { party: null };
  }
}

export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}

export default function WatchPartyRoute() {
  const { party } = useLoaderData<typeof loader>();
  return <WatchPartyRoom initial={party} />;
}
