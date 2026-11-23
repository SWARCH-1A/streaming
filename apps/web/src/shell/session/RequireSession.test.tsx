import { useState } from 'react';
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router';
import { act, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';

import { RequireSession } from './RequireSession';
import type { SessionState } from './Session.types';
import { SessionContext } from './SessionContext';

const anonymous: Omit<SessionState, 'user'> = {
  status: 'ready',
  error: null,
  refresh: () => Promise.resolve(),
  signIn: () => Promise.resolve(),
  signOut: () => Promise.resolve(),
  updateProfile: () => Promise.resolve(),
};

it('redirects an anonymous visitor to /login with a single navigation across re-renders', async () => {
  // The /login data stays pending, as a slow network would keep it, while the session context
  // re-renders the protected route (SessionProvider builds a new value object on every render).
  const loginLoader = vi.fn(() => new Promise<null>(() => undefined));
  let rerender: () => void = () => undefined;
  function Shell() {
    const [, setTick] = useState(0);
    rerender = () => {
      setTick((tick) => tick + 1);
    };
    return (
      <SessionContext value={{ ...anonymous, user: null }}>
        <Outlet />
      </SessionContext>
    );
  }
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          { path: '/studio', element: <RequireSession /> },
          { path: '/login', loader: loginLoader, element: <p>Login</p> },
        ],
      },
    ],
    { initialEntries: ['/studio'] },
  );
  render(<RouterProvider router={router} />);
  await act(async () => {
    await Promise.resolve();
  });
  for (let i = 0; i < 5; i++) {
    await act(async () => {
      rerender();
      await Promise.resolve();
    });
  }
  expect(loginLoader).toHaveBeenCalledTimes(1);
  expect(router.state.navigation.location?.pathname).toBe('/login');
  expect(router.state.navigation.location?.state).toEqual({ from: '/studio' });
  expect(screen.queryByText('Login')).toBeNull();
});
