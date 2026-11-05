import { type LoaderFunctionArgs, useLoaderData } from 'react-router';
import type { P2VodPage } from '@contracts/p2';

import { Library } from '@/src/modules/vod/entry';
import { serverVodGet } from '@/src/server/api.server';

export async function loader({ request }: LoaderFunctionArgs) {
  return serverVodGet<P2VodPage>(request, '/api/vods');
}
export function headers() {
  return { 'Cache-Control': 'no-store' };
}
export default function Vods() {
  const initial = useLoaderData<typeof loader>();
  return (
    <main className="stack">
      <h1>Grabaciones</h1>
      <Library initial={initial} />
    </main>
  );
}
