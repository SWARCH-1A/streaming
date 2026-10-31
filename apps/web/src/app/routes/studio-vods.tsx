import { type LoaderFunctionArgs, useLoaderData } from 'react-router';
import type { ChannelBootstrap, Profile } from '@contracts/p1';
import type { P2VodPage } from '@contracts/p2';

import { Library, RecordingSettings } from '@/src/modules/vod/entry';
import { serverGet, serverVodGet } from '@/src/server/api.server';

export async function loader({ request }: LoaderFunctionArgs) {
  const [initial, profile] = await Promise.all([
    serverVodGet<P2VodPage>(request, '/api/me/vods'),
    serverGet<Profile>(request, '/api/profile/me'),
  ]);
  const channel = await serverGet<ChannelBootstrap>(
    request,
    `/api/channels/by-owner/${encodeURIComponent(profile.userId)}`,
  );
  return { initial, channelId: channel.channel.channelId };
}
export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}
export default function StudioVods() {
  const { initial, channelId } = useLoaderData<typeof loader>();
  return (
    <main className="stack">
      <h1>Mis grabaciones</h1>
      <p>Revisa, edita y publica tus emisiones grabadas.</p>
      <RecordingSettings channelId={channelId} />
      <Library mine initial={initial} />
    </main>
  );
}
