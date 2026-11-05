import type { ReactNode } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Profile, PublicIdentity } from '@contracts/p1';

import { errorMessage, HttpError, request } from '@/src/shared/api/http';

import type { SessionUser } from './Session.types';
import { SessionContext } from './SessionContext';

/**
 * `initial` is the session resolved by the SSR loader for this request. When present the browser
 * hydrates with it and skips the first probe; without it (tests, SPA fallback) the probe runs as before.
 */
export function SessionProvider({
  children,
  initial,
}: {
  children: ReactNode;
  initial?: SessionUser | null;
}) {
  const [user, setUser] = useState<SessionUser | null>(initial ?? null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>(
    initial === undefined ? 'loading' : 'ready',
  );
  const resolvedOnServer = useRef(initial !== undefined);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef({ version: 0 });
  const refresh = useCallback(async (signal?: AbortSignal, requireSession = false) => {
    const version = ++generation.current.version;
    try {
      // This probe belongs to this refresh version. An anonymous response from before
      // login must not emit a global expiration for the new session.
      const profile = await request<Profile>('/api/profile/me', {
        ...(signal ? { signal } : {}),
        expireSessionOnUnauthorized: false,
      });
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
      if (resolvedOnServer.current) {
        resolvedOnServer.current = false;
        return;
      }
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
    generation.current.version++;
    await request('/api/identity/sessions', {
      method: 'POST',
      body: { login, password },
      coreMutation: true,
      expireSessionOnUnauthorized: false,
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
