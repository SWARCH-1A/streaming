import { lazy, Suspense } from 'react';
import { BrowserRouter, Route, Routes, useLocation } from 'react-router';

import { AuthPage, ProfilePage, RecoveryPage } from '@/src/modules/accounts/entry';
import { AdministrationPage } from '@/src/modules/administration/entry';
import { ChannelEditorPage } from '@/src/modules/channels/entry';
import { DiscoveryPage } from '@/src/modules/discovery/entry';
import { StudioPage } from '@/src/modules/streaming/entry';
import { AppLayout } from '@/src/shell/AppLayout';
import { ChannelRoute } from '@/src/shell/routes/ChannelRoute';
import { NotFoundPage } from '@/src/shell/routes/NotFoundPage';
import { WatchRoute } from '@/src/shell/routes/WatchRoute';
import { RequireSession } from '@/src/shell/session/RequireSession';
import { SessionProvider } from '@/src/shell/session/SessionProvider';

const DevelopmentPages = import.meta.env.DEV
  ? lazy(() => import('@/src/shell/design-system/DevelopmentPages'))
  : null;

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
        <Route
          path="forgot-password"
          element={<RecoveryPage key="recovery-request" mode="request" />}
        />
        <Route
          path="reset-password"
          element={<RecoveryPage key="recovery-confirm" mode="confirm" />}
        />
        <Route element={<RequireSession />}>
          <Route path="admin" element={<AdministrationPage />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route path="studio" element={<StudioPage />} />
          <Route path="studio/channel" element={<ChannelEditorPage />} />
        </Route>
        <Route
          path="*"
          element={
            DevelopmentPages ? (
              <Suspense fallback={<p role="status">Cargando biblioteca de desarrollo…</p>}>
                <DevelopmentPages />
              </Suspense>
            ) : (
              <NotFoundPage />
            )
          }
        />
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
