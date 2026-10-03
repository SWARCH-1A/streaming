export interface DemoUser {
  handle: string;
  displayName: string;
  bio: string;
}
export interface SessionState {
  user: DemoUser | null;
  signIn: (handle: string) => void;
  signOut: () => void;
  updateProfile: (profile: Pick<DemoUser, 'displayName' | 'bio'>) => void;
}
