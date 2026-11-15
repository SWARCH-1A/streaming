import { useEffect, useState } from 'react';
import type { P2AccountAdminPage } from '@contracts/p2';

import { request } from '@/src/shared/api/http';

import { useSession } from './useSession';

const administratorChecks = new Map<string, Promise<boolean>>();

function checkAdministrator(userId: string): Promise<boolean> {
  const existing = administratorChecks.get(userId);
  if (existing) return existing;

  const check = request<P2AccountAdminPage>('/api/admin/users?limit=1', {
    expireSessionOnUnauthorized: false,
  })
    .then(() => true)
    .catch(() => false);
  administratorChecks.set(userId, check);
  return check;
}

/** Resolves the UI capability without exposing an administration link to ordinary users. */
export function useAdministrator() {
  const { user } = useSession();
  const [result, setResult] = useState<{ userId: string; administrator: boolean } | null>(null);

  useEffect(() => {
    let mounted = true;
    if (!user) return () => undefined;

    void checkAdministrator(user.userId).then((administrator) => {
      if (mounted) setResult({ userId: user.userId, administrator });
    });
    return () => {
      mounted = false;
    };
  }, [user]);

  return !!user && result?.userId === user.userId && result.administrator;
}
