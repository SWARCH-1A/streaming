import type { ReactNode } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Profile, PublicIdentity } from '@contracts/p1';

import { errorMessage, HttpError, request } from '@/src/shared/api/http';

import type { SessionUser } from './Session.types';
import { SessionContext } from './SessionContext';

export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState<string | null>(null);
  const generation = useRef({ version: 0 });
  const refresh = useCallback(async (signal?: AbortSignal, requireSession = false) => {
    const version = ++generation.current.version;
    try {
      const profile = await request<Profile>('/api/profile/me', signal ? { signal } : {});
      const identity = await request<PublicIdentity>(
        `/api/identity/public/users/${encodeURIComponent(profile.userId)}`,
        signal ? { signal } : {},
      );
      if (version !== generation.current.version) {
        if (requireSession)
          throw new Error('La sesión cambió durante el inicio de sesión. Inténtalo de nuevo.');
        return;
      }
      setUser({ ...profile, handle: identity.handle });
      setError(null);
      setStatus('ready');
    } catch (error) {
      if (version === generation.current.version) {
        setUser(null);
        if (error instanceof HttpError && error.status === 401) {
          setStatus('ready');
          setError(null);
        } else {
          setStatus('error');
          setError(errorMessage(error));
        }
      }
      if (requireSession) throw error;
    }
  }, []);
  useEffect(() => {
    const lifecycle = generation.current;
    const abort = new AbortController();
    queueMicrotask(() => {
      if (!abort.signal.aborted) void refresh(abort.signal);
    });
    function expire() {
      generation.current.version++;
      setUser(null);
      setStatus('ready');
      setError(null);
    }
    window.addEventListener('session-expired', expire);
    return () => {
      abort.abort();
      lifecycle.version++;
      window.removeEventListener('session-expired', expire);
    };
  }, [refresh]);
  async function signIn(login: string, password = '') {
    await request('/api/identity/sessions', {
      method: 'POST',
      body: { login, password },
      coreMutation: true,
    });
    await refresh(undefined, true);
  }
  async function signOut() {
    await request('/api/identity/sessions/current', { method: 'DELETE', coreMutation: true });
    generation.current.version++;
    setUser(null);
    setStatus('ready');
    setError(null);
  }
  async function updateProfile(profile: Pick<SessionUser, 'displayName' | 'bio'>) {
    await request<Profile>('/api/profile/me', {
      method: 'PATCH',
      body: profile,
      coreMutation: true,
    });
    await refresh();
  }
  return (
    <SessionContext value={{ user, status, error, refresh, signIn, signOut, updateProfile }}>
      {children}
    </SessionContext>
  );
}
