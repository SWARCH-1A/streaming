import { data, type LoaderFunctionArgs, redirect } from 'react-router';
import type { Profile } from '@contracts/p1';

import { hasSession, serverGet, UpstreamError } from '@/src/server/api.server';
import { RequireSession } from '@/src/shell/session/RequireSession';

// Protect direct HTML requests before rendering; a client-only Navigate cannot redirect SSR.
export async function loader({ request }: LoaderFunctionArgs) {
  if (!hasSession(request)) return redirect('/login');
  try {
    await serverGet<Profile>(request, '/api/profile/me');
    return null;
  } catch (error) {
    if (error instanceof UpstreamError && error.status === 401) return redirect('/login');
    // eslint-disable-next-line @typescript-eslint/only-throw-error
    throw data(null, { status: 503 });
  }
}

export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}

export default RequireSession;
