import { useLocation } from 'react-router';

import { WatchRoute } from '@/src/shell/routes/WatchRoute';

// Player (hls.js) and chat WebSocket mount in effects, after hydration; the server renders the frame only.
export default function Watch() {
  const { pathname } = useLocation();
  return <WatchRoute key={pathname} />;
}
