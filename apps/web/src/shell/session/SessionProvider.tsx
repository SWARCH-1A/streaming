import type { ReactNode } from 'react';
import { useState } from 'react';

import type { DemoUser } from './Session.types';
import { SessionContext } from './SessionContext';

export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<DemoUser | null>(null);
  function signIn(handle: string) {
    setUser({ handle, displayName: handle, bio: '' });
  }
  function signOut() {
    setUser(null);
  }
  function updateProfile(profile: Pick<DemoUser, 'displayName' | 'bio'>) {
    setUser((previous) => (previous ? { ...previous, ...profile } : null));
  }
  return (
    <SessionContext value={{ user, signIn, signOut, updateProfile }}>{children}</SessionContext>
  );
}
