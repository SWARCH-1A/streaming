import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { P2WatchParty } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as Http from '@/src/shared/api/http';
import { HttpError, request } from '@/src/shared/api/http';

import { hideCode, peekCode } from './codeVault';
import { WatchPartyHome } from './WatchPartyHome';

vi.mock('@/src/shared/api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof Http>()),
  request: vi.fn(),
}));
vi.mock('@/src/shell/session/useSession', () => ({
  useSession: () => ({ user: { userId: 'usr_fixture' } }),
}));

const created = schema.$defs.P2WatchParty.examples[1] as unknown as P2WatchParty;

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}
function mount() {
  return render(
    <MemoryRouter initialEntries={['/watch-parties']}>
      <Routes>
        <Route path="/watch-parties" element={<WatchPartyHome />} />
        <Route path="/watch-parties/:partyId" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  hideCode(created.partyId);
});
afterEach(() => vi.resetAllMocks());

describe('crear una sala', () => {
  it('valida el título, lleva el foco al campo y no llama al servicio', async () => {
    mount();
    await userEvent.click(screen.getByRole('button', { name: 'Crear sala' }));
    expect(screen.getByLabelText('Título de la sala')).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Escribe un título');
    expect(screen.getByLabelText('Título de la sala')).toHaveAttribute('aria-invalid', 'true');
    expect(request).not.toHaveBeenCalled();
  });

  it('crea con Idempotency-Key, guarda el código solo en memoria y abre la sala', async () => {
    vi.mocked(request).mockResolvedValue(created);
    mount();
    await userEvent.type(screen.getByLabelText('Título de la sala'), '  Maratón de prueba  ');
    await userEvent.click(screen.getByRole('button', { name: 'Crear sala' }));
    await waitFor(() =>
      expect(screen.getByTestId('where')).toHaveTextContent(`/watch-parties/${created.partyId}`),
    );
    const [path, options] = vi.mocked(request).mock.calls[0] ?? [];
    expect(path).toBe('/api/watch-parties');
    expect(options).toMatchObject({
      method: 'POST',
      coreMutation: true,
      body: { title: 'Maratón de prueba' },
    });
    expect(options?.headers?.['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(peekCode(created.partyId)).toBe(created.accessCode);
    const stored = window.localStorage.getItem('vault.watchparty.recent.v1:usr_fixture') ?? '';
    expect(JSON.parse(stored)).toEqual([{ partyId: created.partyId, title: created.title }]);
    expect(stored).not.toContain(created.accessCode ?? 'x');
  });

  it('un error del servicio se muestra y devuelve el foco al título', async () => {
    vi.mocked(request).mockRejectedValue(
      new HttpError(503, { code: 'CORE_UNAVAILABLE', message: 'x', requestId: 'req_1' }),
    );
    mount();
    await userEvent.type(screen.getByLabelText('Título de la sala'), 'Sala');
    await userEvent.click(screen.getByRole('button', { name: 'Crear sala' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('CORE_UNAVAILABLE · req_1');
    await waitFor(() => expect(screen.getByLabelText('Título de la sala')).toHaveFocus());
  });
});

describe('salas recientes', () => {
  it('lista, enlaza y permite quitar las salas de este navegador', async () => {
    window.localStorage.setItem(
      'vault.watchparty.recent.v1:usr_fixture',
      JSON.stringify([{ partyId: created.partyId, title: 'Mi sala' }]),
    );
    mount();
    expect(await screen.findByRole('link', { name: 'Mi sala' })).toHaveAttribute(
      'href',
      `/watch-parties/${created.partyId}`,
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Quitar Mi sala de las salas recientes' }),
    );
    expect(screen.queryByRole('link', { name: 'Mi sala' })).not.toBeInTheDocument();
    expect(screen.getByText(/nunca el código de acceso/)).toBeInTheDocument();
  });

  it('la página funciona aunque el almacenamiento esté bloqueado', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.mocked(request).mockResolvedValue(created);
    mount();
    await userEvent.type(screen.getByLabelText('Título de la sala'), 'Sala');
    await userEvent.click(screen.getByRole('button', { name: 'Crear sala' }));
    await waitFor(() => expect(screen.getByTestId('where')).toBeInTheDocument());
    vi.restoreAllMocks();
  });
});
