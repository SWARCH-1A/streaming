import { BrowserRouter, Route, Routes, useLocation } from 'react-router';

import { AuthPage, ProfilePage } from '@/src/modules/accounts/entry';
import { ChannelEditorPage } from '@/src/modules/channels/entry';
import { DiscoveryPage } from '@/src/modules/discovery/entry';
import { StudioPage } from '@/src/modules/streaming/entry';
import { AppLayout } from '@/src/shell/AppLayout';
import { ComponentsPage } from '@/src/shell/design-system/ComponentsPage';
import { FoundationsPage } from '@/src/shell/design-system/FoundationsPage';
import { PrototypePage } from '@/src/shell/design-system/PrototypePage';
import { ChannelRoute } from '@/src/shell/routes/ChannelRoute';
import { NotFoundPage } from '@/src/shell/routes/NotFoundPage';
import { WatchRoute } from '@/src/shell/routes/WatchRoute';
import { SessionProvider } from '@/src/shell/session/SessionProvider';

function AppRoutes() {
  const { pathname } = useLocation();
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<DiscoveryPage />} />
        <Route path="search" element={<DiscoveryPage search />} />
        <Route path="watch/:streamId" element={<WatchRoute key={pathname} />} />
        <Route path="channels/:handle" element={<ChannelRoute />} />
        <Route path="login" element={<AuthPage key="login" mode="login" />} />
        <Route path="register" element={<AuthPage key="register" mode="register" />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="studio" element={<StudioPage />} />
        <Route path="studio/channel" element={<ChannelEditorPage />} />
        <Route path="design-system" element={<FoundationsPage />} />
        <Route path="design-system/components" element={<ComponentsPage />} />
        <Route path="prototype" element={<PrototypePage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

export function App() {
  return (
    <SessionProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </SessionProvider>
  );
}
