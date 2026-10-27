import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';

import type * as Http from '@/src/shared/api/http';
import { request } from '@/src/shared/api/http';

import { AdministrationPage } from './AdministrationPage';

vi.mock('@/src/shared/api/http', async (original) => ({
  ...(await original<typeof Http>()),
  request: vi.fn(),
}));
const account = { userId: 'usr_fixture', handle: 'fixture', status: 'ACTIVE', accountVersion: 0 };
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(request).mockImplementation((path, options) => {
    if (options?.method === 'PUT')
      return Promise.resolve({ ...account, status: 'SUSPENDED', accountVersion: 1 });
    return Promise.resolve({
      items: path.startsWith('/api/admin/users') ? [account] : [],
      nextCursor: null,
    });
  });
});
it('focuses the reason and associates validation without sending an empty command', async () => {
  const user = userEvent.setup();
  render(<AdministrationPage />);
  await user.click(await screen.findByRole('button', { name: 'Suspender fixture' }));
  expect(screen.getByLabelText('Motivo')).toHaveFocus();
  await user.click(screen.getByRole('button', { name: 'Confirmar cambio' }));
  expect(screen.getByLabelText('Motivo')).toHaveAttribute('aria-invalid', 'true');
  expect(screen.getByLabelText('Motivo')).toHaveAccessibleDescription(/Indica el motivo/);
  expect(vi.mocked(request).mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(
    false,
  );
});
it('keeps the idempotency key after uncertain failure and separates operation errors from field errors', async () => {
  let attempts = 0;
  vi.mocked(request).mockImplementation((path, options) => {
    if (options?.method === 'PUT') {
      if (++attempts === 1) return Promise.reject(new Error('Servicio no disponible'));
      return Promise.resolve({ ...account, status: 'SUSPENDED', accountVersion: 1 });
    }
    return Promise.resolve({
      items: path.startsWith('/api/admin/users') ? [account] : [],
      nextCursor: null,
    });
  });
  const user = userEvent.setup();
  render(<AdministrationPage />);
  await user.click(await screen.findByRole('button', { name: 'Suspender fixture' }));
  await user.type(screen.getByLabelText('Motivo'), 'Revisión de prueba');
  await user.click(screen.getByRole('button', { name: 'Confirmar cambio' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Servicio no disponible');
  expect(screen.getByLabelText('Motivo')).toHaveAttribute('aria-invalid', 'false');
  await user.click(screen.getByRole('button', { name: 'Confirmar cambio' }));
  await waitFor(() =>
    expect(screen.getByRole('heading', { name: 'Administración' })).toHaveFocus(),
  );
  const calls = vi.mocked(request).mock.calls.filter(([, options]) => options?.method === 'PUT');
  expect(calls).toHaveLength(2);
  expect(calls[0]?.[1]?.headers?.['Idempotency-Key']).toBe(
    calls[1]?.[1]?.headers?.['Idempotency-Key'],
  );
  expect(calls[0]?.[1]?.coreMutation).toBe(true);
});
it('shows denied access without rendering a mutation control', async () => {
  vi.mocked(request).mockRejectedValue(new Error('ADMIN_REQUIRED'));
  render(<AdministrationPage />);
  expect(await screen.findAllByRole('alert')).toHaveLength(2);
  expect(screen.queryByRole('button', { name: /Suspender/ })).not.toBeInTheDocument();
});
