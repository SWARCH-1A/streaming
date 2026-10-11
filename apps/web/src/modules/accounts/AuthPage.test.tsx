import { MemoryRouter, Route, Routes } from 'react-router';
import schema from '@contracts/p1.schema.json';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { SessionProvider } from '@/src/shell/session/SessionProvider';
import { useSession } from '@/src/shell/session/useSession';

import { AuthPage } from './AuthPage';

function setup() {
  vi.stubGlobal(
    'fetch',
    vi.fn((path: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            path === '/api/identity/csrf'
              ? schema.$defs.Csrf.examples[0]
              : path === '/api/identity/registrations'
                ? schema.$defs.Registration.examples[0]
                : { code: 'AUTH_REQUIRED', message: 'Sesión requerida', requestId: 'req_test' },
          ),
          {
            status:
              path === '/api/profile/me' ? 401 : path === '/api/identity/registrations' ? 201 : 200,
            headers: { 'Content-Type': 'application/json' },
          },
        ),
      ),
    ),
  );
  render(
    <SessionProvider>
      <MemoryRouter>
        <AuthPage mode="register" />
      </MemoryRouter>
    </SessionProvider>,
  );
}
afterEach(() => vi.unstubAllGlobals());
describe('formulario de registro', () => {
  it('asocia errores a campos y lleva el foco al primero', async () => {
    setup();
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta' }));
    expect(screen.getByLabelText('Correo electrónico')).toHaveFocus();
    expect(screen.getByLabelText('Correo electrónico')).toHaveAttribute(
      'aria-describedby',
      'email-error',
    );
    expect(screen.getAllByRole('alert')).toHaveLength(3);
  });
  it('valida sin crear sesión y limpia la contraseña', async () => {
    setup();
    await userEvent.type(screen.getByLabelText('Correo electrónico'), 'demo@example.test');
    await userEvent.type(screen.getByLabelText('Handle'), 'demo_01');
    await userEvent.type(screen.getByLabelText('Contraseña'), 'demopassword12');
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta' }));
    expect(await screen.findByText('Cuenta creada. Ya puedes iniciar sesión.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Contraseña')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir a iniciar sesión →' })).toHaveAttribute(
      'href',
      '/login',
    );
  });
});

describe('confirmación del inicio de sesión', () => {
  function setupLogin(profileStatus: 200 | 401 | 503, expireDuringIdentity = false) {
    let loggedIn = false;
    const fetch = vi.fn((path: string) => {
      let status = 200;
      let value: unknown;
      if (path === '/api/identity/csrf') value = schema.$defs.Csrf.examples[0];
      else if (path === '/api/identity/sessions') {
        loggedIn = true;
        value = schema.$defs.Login.examples[0];
      } else if (path === '/api/profile/me') {
        status = loggedIn ? profileStatus : 401;
        value =
          status === 200
            ? schema.$defs.Profile.examples[0]
            : {
                code: status === 401 ? 'AUTH_REQUIRED' : 'CORE_UNAVAILABLE',
                message:
                  status === 401 ? 'Se requiere una sesión activa.' : 'Core no está disponible.',
                requestId: 'req_fixture',
              };
      } else {
        if (expireDuringIdentity) window.dispatchEvent(new Event('session-expired'));
        value = schema.$defs.PublicIdentity.examples[0];
      }
      return Promise.resolve(
        new Response(JSON.stringify(value), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    });
    vi.stubGlobal('fetch', fetch);
    render(
      <SessionProvider>
        <MemoryRouter initialEntries={['/login']}>
          <Routes>
            <Route path="/login" element={<AuthPage mode="login" />} />
            <Route path="/studio" element={<h1>Estudio confirmado</h1>} />
          </Routes>
        </MemoryRouter>
      </SessionProvider>,
    );
    return fetch;
  }
  async function submitLogin() {
    await userEvent.type(screen.getByLabelText('Handle o correo'), 'fixture_01');
    await userEvent.type(screen.getByLabelText('Contraseña'), 'fixture-only password');
    await userEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }));
  }
  it.each([401, 503] as const)(
    'muestra el error si perfil devuelve %s después de login 200',
    async (status) => {
      setupLogin(status);
      await submitLogin();
      expect(await screen.findByRole('alert')).toHaveTextContent(
        status === 401 ? 'Se requiere una sesión activa.' : 'Core no está disponible.',
      );
      expect(screen.queryByRole('heading', { name: 'Estudio confirmado' })).not.toBeInTheDocument();
      expect(screen.getByLabelText('Handle o correo')).toHaveValue('fixture_01');
      expect(screen.getByLabelText('Contraseña')).toHaveValue('fixture-only password');
      expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled();
    },
  );
  it('navega solo después de confirmar perfil e identidad', async () => {
    const fetch = setupLogin(200);
    await submitLogin();
    expect(await screen.findByRole('heading', { name: 'Estudio confirmado' })).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith('/api/identity/public/users/usr_fixture', expect.anything());
  });
  it('descarta la confirmación si la sesión expira mientras consulta identidad', async () => {
    setupLogin(200, true);
    await submitLogin();
    expect(await screen.findByRole('alert')).toHaveTextContent('La sesión cambió');
    expect(screen.queryByRole('heading', { name: 'Estudio confirmado' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Handle o correo')).toHaveValue('fixture_01');
    expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled();
  });

  it('conserva la sesión nueva cuando la consulta anónima inicial devuelve un 401 tardío', async () => {
    let completeAnonymous!: (response: Response) => void;
    const anonymous = new Promise<Response>((resolve) => {
      completeAnonymous = resolve;
    });
    let profileReads = 0;
    const fetcher = vi.fn((path: string) => {
      if (path === '/api/profile/me' && profileReads++ === 0) return anonymous;
      const value =
        path === '/api/identity/csrf'
          ? schema.$defs.Csrf.examples[0]
          : path === '/api/identity/sessions'
            ? schema.$defs.Login.examples[0]
            : path === '/api/profile/me'
              ? schema.$defs.Profile.examples[0]
              : schema.$defs.PublicIdentity.examples[0];
      return Promise.resolve(
        new Response(JSON.stringify(value), {
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    });
    vi.stubGlobal('fetch', fetcher);
    function CurrentUser() {
      const { user } = useSession();
      return <p data-testid="current-user">{user?.userId ?? 'anonymous'}</p>;
    }
    render(
      <SessionProvider>
        <MemoryRouter initialEntries={['/login']}>
          <CurrentUser />
          <Routes>
            <Route path="/login" element={<AuthPage mode="login" />} />
            <Route path="/studio" element={<h1>Estudio confirmado</h1>} />
          </Routes>
        </MemoryRouter>
      </SessionProvider>,
    );
    await submitLogin();
    expect(await screen.findByRole('heading', { name: 'Estudio confirmado' })).toBeInTheDocument();
    expect(screen.getByTestId('current-user')).toHaveTextContent('usr_fixture');
    await act(async () => {
      completeAnonymous(
        new Response(
          JSON.stringify({
            code: 'AUTH_REQUIRED',
            message: 'Se requiere una sesión activa.',
            requestId: 'req_before_login',
          }),
          { status: 401, headers: { 'Content-Type': 'application/json' } },
        ),
      );
      await anonymous;
    });
    expect(screen.getByTestId('current-user')).toHaveTextContent('usr_fixture');
  });
});
