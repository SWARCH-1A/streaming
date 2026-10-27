import type { ReactNode } from 'react';
import {
  isRouteErrorResponse,
  Links,
  type LoaderFunctionArgs,
  Meta,
  Outlet,
  Scripts,
  ScrollRestoration,
  useLoaderData,
  useRouteError,
} from 'react-router';
import type { Profile, PublicIdentity } from '@contracts/p1';

import { hasSession, serverGet, UpstreamError } from '@/src/server/api.server';
import type { SessionUser } from '@/src/shell/session/Session.types';
import { SessionProvider } from '@/src/shell/session/SessionProvider';

import '@fontsource-variable/inter';
import '@fontsource-variable/plus-jakarta-sans';
import '@/src/styles/global.css';

/**
 * Resolves the session of *this* request on the server. `null` = anonymous (confirmed);
 * `undefined` = could not be checked, so the browser probes again after hydrating.
 */
export async function loader({ request }: LoaderFunctionArgs) {
  if (!hasSession(request)) return { user: null };
  try {
    const profile = await serverGet<Profile>(request, '/api/profile/me');
    const identity = await serverGet<PublicIdentity>(
      request,
      `/api/identity/public/users/${encodeURIComponent(profile.userId)}`,
    );
    return { user: { ...profile, handle: identity.handle } satisfies SessionUser };
  } catch (error) {
    if (error instanceof UpstreamError && error.status === 401) return { user: null };
    return { user: undefined };
  }
}

// Identity-dependent HTML must never be cached or shared between users.
export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}

export function meta() {
  return [
    { title: 'Vault tv · En directo, contigo' },
    {
      name: 'description',
      content: 'Descubre transmisiones y comparte con tu comunidad en Vault tv.',
    },
  ];
}

export function Layout({ children }: { children: ReactNode }) {
  return (
    <html lang="es">
      <head>
        <meta charSet="UTF-8" />
        <style>{'@layer tokens, base, components;'}</style>
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <meta name="theme-color" content="#0d0e12" />
        <Meta />
        <Links />
      </head>
      <body>
        <div id="root">{children}</div>
        <ScrollRestoration />
        <Scripts />
      </body>
    </html>
  );
}

export default function Root() {
  const { user } = useLoaderData<typeof loader>();
  return (
    <SessionProvider {...(user === undefined ? {} : { initial: user })}>
      <Outlet />
    </SessionProvider>
  );
}

export function ErrorBoundary() {
  const error = useRouteError();
  const status = isRouteErrorResponse(error) ? error.status : 500;
  return (
    <main id="main-content" className="stack" style={{ padding: '2rem' }}>
      <h1>{status === 404 ? 'Página no encontrada' : 'No se pudo cargar Vault tv'}</h1>
      <p>{status === 404 ? 'La dirección no existe.' : 'Inténtalo de nuevo en unos segundos.'}</p>
      <a href="/">Volver al inicio</a>
    </main>
  );
}
