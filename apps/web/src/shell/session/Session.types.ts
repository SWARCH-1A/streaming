import type { Profile } from '@contracts/p1';

export type SessionUser = Profile & { handle: string };
export type DemoUser = SessionUser;
export interface SessionState {
  user: SessionUser | null;
  status: 'loading' | 'ready' | 'error';
  error: string | null;
  refresh: () => Promise<void>;
  signIn: (login: string, password?: string) => Promise<void>;
  signOut: () => Promise<void>;
  updateProfile: (profile: Pick<SessionUser, 'displayName' | 'bio'>) => Promise<void>;
}
