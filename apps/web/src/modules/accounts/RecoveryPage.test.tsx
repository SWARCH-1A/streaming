import { StrictMode } from 'react';
import { Link, MemoryRouter, useLocation } from 'react-router';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RecoveryPage } from './RecoveryPage';

const token = 'a'.repeat(43);
const replacementToken = 'b'.repeat(43);
const message =
  'Si existe una cuenta asociada, recibirás un correo para restablecer la contraseña.';

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function Location({ replacementFragment }: { replacementFragment: string }) {
  const location = useLocation();
  return (
    <>
      <span data-testid="location">{location.pathname + location.hash}</span>
      <Link to={`/reset-password${replacementFragment}`}>Abrir otro enlace</Link>
    </>
  );
}

function setup(
  mode: 'request' | 'confirm',
  responder?: (path: string) => Promise<Response>,
  fragment = `#token=${token}`,
  replacementFragment = `#token=${replacementToken}`,
) {
  const fetch = vi.fn((path: string, options?: RequestInit) => {
    void options;
    if (path === '/api/identity/csrf')
      return Promise.resolve(json({ headerName: 'X-XSRF-TOKEN', token: 'fixture-csrf' }));
    return responder
      ? responder(path)
      : Promise.resolve(
          path.endsWith('/confirm') ? new Response(null, { status: 204 }) : json({ message }, 202),
        );
  });
  vi.stubGlobal('fetch', fetch);
  const view = render(
    <StrictMode>
      <MemoryRouter
        initialEntries={[mode === 'confirm' ? `/reset-password${fragment}` : '/forgot-password']}
      >
        <RecoveryPage mode={mode} />
        <Location replacementFragment={replacementFragment} />
      </MemoryRouter>
    </StrictMode>,
  );
  return { fetch, ...view };
}

afterEach(() => vi.unstubAllGlobals());

describe('solicitud de recuperación', () => {
  it('asocia la validación al correo y lleva el foco al campo', async () => {
    const { fetch } = setup('request');
    await userEvent.click(screen.getByRole('button', { name: 'Enviar correo de recuperación' }));
    expect(screen.getByLabelText('Correo electrónico')).toHaveFocus();
    expect(screen.getByLabelText('Correo electrónico')).toHaveAttribute(
      'aria-describedby',
      'email-error',
    );
    expect(fetch).not.toHaveBeenCalled();
  });

  it('usa CSRF, idempotencia y la respuesta uniforme sin confirmar la existencia del correo', async () => {
    const { fetch } = setup('request');
    await userEvent.type(screen.getByLabelText('Correo electrónico'), 'fixture@example.test');
    await userEvent.click(screen.getByRole('button', { name: 'Enviar correo de recuperación' }));
    await act(async () => {
      await vi.dynamicImportSettled();
    });
    expect(await screen.findByRole('status')).toHaveTextContent(message);
    const options = fetch.mock.calls.find(([path]) => path.endsWith('/recovery-requests'))?.[1];
    expect(options).toBeDefined();
    const request = options as RequestInit;
    expect(JSON.parse(request.body as string)).toEqual({ email: 'fixture@example.test' });
    const headers = new Headers(request.headers);
    expect(headers.get('X-XSRF-TOKEN')).toBe('fixture-csrf');
    expect(headers.get('Idempotency-Key')).toMatch(/^[a-f0-9-]{36}$/);
    expect(screen.queryByLabelText('Correo electrónico')).not.toBeInTheDocument();
  });

  it('conserva la clave al reintentar una respuesta incierta', async () => {
    let attempts = 0;
    const { fetch } = setup('request', () =>
      Promise.resolve(
        attempts++ === 0
          ? json(
              { code: 'CORE_UNAVAILABLE', message: 'Intenta otra vez.', requestId: 'req_fixture' },
              503,
            )
          : json({ message }, 202),
      ),
    );
    await userEvent.type(screen.getByLabelText('Correo electrónico'), 'fixture@example.test');
    const send = () =>
      userEvent.click(screen.getByRole('button', { name: 'Enviar correo de recuperación' }));
    await send();
    expect(await screen.findByRole('alert')).toHaveTextContent('Intenta otra vez.');
    await send();
    expect(await screen.findByRole('status')).toHaveTextContent(message);
    const calls = fetch.mock.calls.filter(([path]) => path.endsWith('/recovery-requests'));
    expect(calls).toHaveLength(2);
    const first = calls[0]?.[1] as RequestInit;
    const second = calls[1]?.[1] as RequestInit;
    expect(new Headers(first.headers).get('Idempotency-Key')).toBe(
      new Headers(second.headers).get('Idempotency-Key'),
    );
  });
});

describe('confirmación de recuperación', () => {
  it('rechaza enlaces sin token y ofrece pedir otro', () => {
    const { fetch } = setup('confirm', undefined, '');
    expect(screen.getByRole('alert')).toHaveTextContent('El enlace no es válido.');
    expect(screen.queryByLabelText('Nueva contraseña')).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Solicitar otro enlace de recuperación' }),
    ).toHaveAttribute('href', '/forgot-password');
    expect(fetch).not.toHaveBeenCalled();
  });

  it('retira el token de la URL, conserva la contraseña exacta y limpia los secretos al terminar', async () => {
    const { fetch } = setup('confirm');
    expect(screen.getByTestId('location')).toHaveTextContent('/reset-password');
    expect(screen.getByTestId('location')).not.toHaveTextContent(token);
    const password = '  contraseña nueva 🛰️  ';
    await userEvent.type(screen.getByLabelText('Nueva contraseña'), password);
    await userEvent.type(screen.getByLabelText('Repite la contraseña'), password);
    const expired = vi.fn();
    window.addEventListener('session-expired', expired);
    try {
      await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
      expect(await screen.findByRole('status')).toHaveTextContent('Contraseña actualizada.');
      const options = fetch.mock.calls.find(([path]) =>
        path.endsWith('/confirm'),
      )?.[1] as RequestInit;
      expect(JSON.parse(options.body as string)).toEqual({
        recoveryToken: token,
        newPassword: password,
      });
      expect(expired).toHaveBeenCalledOnce();
      expect(screen.queryByLabelText('Nueva contraseña')).not.toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Ir a iniciar sesión' })).toHaveAttribute(
        'href',
        '/login',
      );
    } finally {
      window.removeEventListener('session-expired', expired);
    }
  });

  it('pide confirmación coincidente y enfoca el error', async () => {
    setup('confirm');
    await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture password 12');
    await userEvent.type(screen.getByLabelText('Repite la contraseña'), 'different password 12');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
    expect(screen.getByLabelText('Repite la contraseña')).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Las contraseñas deben coincidir.');
  });

  it('captura un enlace nuevo desde el estado sin token en la misma pestaña', async () => {
    setup('confirm', undefined, '');
    expect(screen.getByRole('alert')).toHaveTextContent('El enlace no es válido.');
    await userEvent.click(screen.getByRole('link', { name: 'Abrir otro enlace' }));
    expect(screen.getByLabelText('Nueva contraseña')).toHaveValue('');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/reset-password');
    expect(screen.getByTestId('location')).not.toHaveTextContent('#');
  });

  it('reemplaza el token, limpia errores y campos y usa otra clave de idempotencia', async () => {
    let attempts = 0;
    const { fetch } = setup('confirm', () =>
      Promise.resolve(
        attempts++ === 0
          ? json(
              {
                code: 'RECOVERY_INVALID',
                message: 'El enlace ha vencido.',
                requestId: 'req_fixture',
              },
              422,
            )
          : new Response(null, { status: 204 }),
      ),
    );
    await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture password 12');
    await userEvent.type(screen.getByLabelText('Repite la contraseña'), 'fixture password 12');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('El enlace ha vencido.');
    await userEvent.clear(screen.getByLabelText('Repite la contraseña'));
    await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
    expect(screen.getByLabelText('Repite la contraseña')).toHaveAttribute('aria-invalid', 'true');

    await userEvent.click(screen.getByRole('link', { name: 'Abrir otro enlace' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Nueva contraseña')).toHaveValue('');
    expect(screen.getByLabelText('Repite la contraseña')).toHaveValue('');
    expect(screen.getByLabelText('Repite la contraseña')).toHaveAttribute('aria-invalid', 'false');
    expect(screen.getByTestId('location')).not.toHaveTextContent('#');
    await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture replacement 12');
    await userEvent.type(screen.getByLabelText('Repite la contraseña'), 'fixture replacement 12');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Contraseña actualizada.');
    const calls = fetch.mock.calls.filter(([path]) => path.endsWith('/confirm'));
    expect(calls).toHaveLength(2);
    const first = calls[0]?.[1] as RequestInit;
    const second = calls[1]?.[1] as RequestInit;
    expect(JSON.parse(first.body as string)).toEqual({
      recoveryToken: token,
      newPassword: 'fixture password 12',
    });
    expect(JSON.parse(second.body as string)).toEqual({
      recoveryToken: replacementToken,
      newPassword: 'fixture replacement 12',
    });
    expect(new Headers(second.headers).get('Idempotency-Key')).not.toBe(
      new Headers(first.headers).get('Idempotency-Key'),
    );
  });

  it('reabre el formulario al recibir otro enlace después de confirmar', async () => {
    setup('confirm');
    await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture password 12');
    await userEvent.type(screen.getByLabelText('Repite la contraseña'), 'fixture password 12');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Contraseña actualizada.');
    await userEvent.click(screen.getByRole('link', { name: 'Abrir otro enlace' }));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Nueva contraseña')).toHaveValue('');
    expect(screen.getByLabelText('Repite la contraseña')).toHaveValue('');
  });

  it('descarta el token anterior si el nuevo fragmento es inválido', async () => {
    const { fetch } = setup('confirm', undefined, `#token=${token}`, '#token=invalid');
    await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture password 12');
    await userEvent.click(screen.getByRole('link', { name: 'Abrir otro enlace' }));
    expect(screen.getByRole('alert')).toHaveTextContent('El enlace no es válido.');
    expect(screen.queryByLabelText('Nueva contraseña')).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).not.toHaveTextContent('#');
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each(['success', 'failure'])(
    'descarta una respuesta tardía de %s del enlace anterior',
    async (outcome) => {
      let complete!: (response: Response) => void;
      const response = new Promise<Response>((resolve) => {
        complete = resolve;
      });
      let attempts = 0;
      const { fetch } = setup('confirm', () =>
        attempts++ === 0 ? response : Promise.resolve(new Response(null, { status: 204 })),
      );
      await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture password 12');
      await userEvent.type(screen.getByLabelText('Repite la contraseña'), 'fixture password 12');
      await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
      await waitFor(() =>
        expect(fetch.mock.calls.some(([path]) => path.endsWith('/confirm'))).toBe(true),
      );
      const previous = fetch.mock.calls.find(([path]) =>
        path.endsWith('/confirm'),
      )?.[1] as RequestInit;
      const expired = vi.fn();
      window.addEventListener('session-expired', expired);
      try {
        await userEvent.click(screen.getByRole('link', { name: 'Abrir otro enlace' }));
        expect(previous.signal?.aborted).toBe(true);
        expect(screen.getByRole('button', { name: 'Guardar nueva contraseña' })).toBeEnabled();
        expect(screen.getByLabelText('Nueva contraseña')).toHaveValue('');
        await act(async () => {
          complete(
            outcome === 'success'
              ? new Response(null, { status: 204 })
              : json(
                  {
                    code: 'RECOVERY_INVALID',
                    message: 'Respuesta del enlace anterior.',
                    requestId: 'req_fixture',
                  },
                  422,
                ),
          );
          await response;
        });
        expect(expired).not.toHaveBeenCalled();
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture replacement 12');
        await userEvent.type(
          screen.getByLabelText('Repite la contraseña'),
          'fixture replacement 12',
        );
        await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
        expect(await screen.findByRole('status')).toHaveTextContent('Contraseña actualizada.');
        const options = fetch.mock.calls
          .filter(([path]) => path.endsWith('/confirm'))
          .at(-1)?.[1] as RequestInit;
        expect(JSON.parse(options.body as string)).toEqual({
          recoveryToken: replacementToken,
          newPassword: 'fixture replacement 12',
        });
        expect(expired).toHaveBeenCalledOnce();
      } finally {
        window.removeEventListener('session-expired', expired);
      }
    },
  );

  it('muestra un token vencido como error y mantiene acceso a pedir otro', async () => {
    setup('confirm', () =>
      Promise.resolve(
        json(
          { code: 'RECOVERY_INVALID', message: 'El enlace ha vencido.', requestId: 'req_fixture' },
          422,
        ),
      ),
    );
    await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture password 12');
    await userEvent.type(screen.getByLabelText('Repite la contraseña'), 'fixture password 12');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('El enlace ha vencido.');
    expect(screen.getByRole('button', { name: 'Guardar nueva contraseña' })).toBeEnabled();
  });

  it('descarta respuestas tardías al abandonar la pantalla', async () => {
    let complete!: (response: Response) => void;
    const response = new Promise<Response>((resolve) => {
      complete = resolve;
    });
    const { unmount, fetch } = setup('confirm', () => response);
    await userEvent.type(screen.getByLabelText('Nueva contraseña'), 'fixture password 12');
    await userEvent.type(screen.getByLabelText('Repite la contraseña'), 'fixture password 12');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar nueva contraseña' }));
    await waitFor(() =>
      expect(fetch.mock.calls.some(([path]) => path.endsWith('/confirm'))).toBe(true),
    );
    const expired = vi.fn();
    window.addEventListener('session-expired', expired);
    try {
      unmount();
      await act(async () => {
        complete(new Response(null, { status: 204 }));
        await response;
      });
      expect(expired).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('session-expired', expired);
    }
  });
});
