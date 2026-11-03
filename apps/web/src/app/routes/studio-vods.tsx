import { type LoaderFunctionArgs, useLoaderData } from 'react-router';
import type { ChannelBootstrap, Profile } from '@contracts/p1';
import type { P2VodPage } from '@contracts/p2';

import { Library, RecordingSettings } from '@/src/modules/vod/entry';
import { serverGet, serverVodGet } from '@/src/server/api.server';
import { StudioNavigation } from '@/src/shell/components/StudioNavigation';

import styles from './studio-vods.module.css';

export async function loader({ request }: LoaderFunctionArgs) {
  const [initial, profile] = await Promise.all([
    serverVodGet<P2VodPage>(request, '/api/me/vods'),
    serverGet<Profile>(request, '/api/profile/me'),
  ]);
  const channel = await serverGet<ChannelBootstrap>(
    request,
    `/api/channels/by-owner/${encodeURIComponent(profile.userId)}`,
  );
  return { initial, channelId: channel.channel.channelId, channelHandle: channel.handle };
}
export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}
export default function StudioVods() {
  const { initial, channelId, channelHandle } = useLoaderData<typeof loader>();
  return (
    <main className={styles.page}>
      <header className={styles.hero}>
        <p className="eyebrow">ESTUDIO DE EMISIÓN</p>
        <h1>Mis grabaciones</h1>
        <p>
          Revisa tus emisiones pasadas, organiza los detalles y comparte las que quieras publicar.
        </p>
        <StudioNavigation channelHandle={channelHandle} />
      </header>
      <RecordingSettings channelId={channelId} />
      <Library mine initial={initial} />
    </main>
  );
}
