import { Navigate, Outlet, useLocation } from 'react-router';

import { Button } from '@/src/components/atoms/Button';
import { Notice } from '@/src/components/molecules/Notice';

import { useSession } from './useSession';

export function RequireSession() {
  const { user, status, error, refresh } = useSession();
  const location = useLocation();
  if (status === 'loading') return <p role="status">Comprobando sesión…</p>;
  if (status === 'error')
    return (
      <Notice tone="error">
        {error}
        <Button
          onClick={() => {
            void refresh();
          }}
        >
          Reintentar sesión
        </Button>
      </Notice>
    );
  return user ? (
    <Outlet key={user.userId} />
  ) : (
    <Navigate to="/login" replace state={{ from: location.pathname }} />
  );
}
