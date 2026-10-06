import { useParams } from 'react-router';

import { ChannelPage } from '@/src/modules/channels/entry';
import { demoChannels } from '@/src/modules/discovery/entry';
import { NotFoundPage } from '@/src/shell/routes/NotFoundPage';

export function ChannelRoute() {
  const { handle } = useParams();
  const channel = demoChannels.find((item) => item.handle.toLowerCase() === handle?.toLowerCase());
  return channel ? <ChannelPage channel={channel} /> : <NotFoundPage />;
}
