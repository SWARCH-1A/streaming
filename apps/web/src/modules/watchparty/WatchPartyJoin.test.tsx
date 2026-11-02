import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { P2WatchParty } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as Http from '@/src/shared/api/http';
import { HttpError, request } from '@/src/shared/api/http';

import { WatchPartyJoin } from './WatchPartyJoin';

vi.mock('@/src/shared/api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof Http>()),
  request: vi.fn(),
}));
interface SessionFixture {
  user: { userId: string } | null;
  status: string;
}
const session = vi.hoisted((): { current: SessionFixture } => ({
  current: { user: { userId: 'usr_fixture' }, status: 'ready' },
}));
vi.mock('@/src/shell/session/useSession', () => ({ useSession: () => session.current }));

const party = schema.$defs.P2WatchParty.examples[0] as unknown as P2WatchParty;
const code = 'q'.repeat(43);

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}
function mount() {
  return render(
    <MemoryRouter initialEntries={['/watch-parties/join']}>
      <Routes>
        <Route path="/watch-parties/join" element={<WatchPartyJoin />} />
        <Route path="/watch-parties/:partyId" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  session.current = { user: { userId: 'usr_fixture' }, status: 'ready' };
});
afterEach(() => {
  window.location.hash = '';
  vi.resetAllMocks();
});

describe('unirse con un código', () => {
  it('sin sesión explica cómo continuar y no muestra el formulario ni llama al servicio', () => {
    window.location.hash = `#${code}`;
    session.current = { user: null, status: 'ready' };
    mount();
    expect(
      screen.getByRole('heading', { level: 1, name: 'Unirme a una sala' }),
    ).toBeInTheDocument();
    expect(screen.getByText(/Necesitas una cuenta para entrar a una sala/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Inicia sesión' })).toHaveAttribute('href', '/login');
    expect(screen.getByRole('link', { name: 'crea una cuenta' })).toHaveAttribute(
      'href',
      '/register',
    );
    expect(screen.queryByLabelText('Código de acceso')).not.toBeInTheDocument();
    expect(request).not.toHaveBeenCalled();
  });

  it('mientras se comprueba la sesión no ofrece el formulario', () => {
    session.current = { user: null, status: 'loading' };
    mount();
    expect(screen.getByRole('status')).toHaveTextContent('Comprobando sesión');
    expect(screen.queryByLabelText('Código de acceso')).not.toBeInTheDocument();
  });

  it('rechaza un código mal formado sin llamar al servicio', async () => {
    mount();
    await userEvent.type(screen.getByLabelText('Código de acceso'), 'corto');
    await userEvent.click(screen.getByRole('button', { name: 'Unirme a la sala' }));
    expect(screen.getByRole('alert')).toHaveTextContent('43 caracteres');
    expect(screen.getByLabelText('Código de acceso')).toHaveFocus();
    expect(request).not.toHaveBeenCalled();
  });

  it('lee el código del fragmento del enlace y entra sin que viaje en la URL de la API', async () => {
    window.location.hash = `#${code}`;
    vi.mocked(request).mockResolvedValue(party);
    mount();
    expect(await screen.findByLabelText('Código de acceso')).toHaveValue(code);
    await userEvent.click(screen.getByRole('button', { name: 'Unirme a la sala' }));
    await waitFor(() =>
      expect(screen.getByTestId('where')).toHaveTextContent(`/watch-parties/${party.partyId}`),
    );
    const [path, options] = vi.mocked(request).mock.calls[0] ?? [];
    expect(path).toBe('/api/watch-parties/join');
    expect(path).not.toContain(code);
    expect(options).toMatchObject({
      method: 'POST',
      coreMutation: true,
      body: { accessCode: code },
    });
    expect(options?.headers?.['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('un código desconocido se explica sin distinguirlo de una sala inexistente', async () => {
    vi.mocked(request).mockRejectedValue(
      new HttpError(404, { code: 'WATCH_PARTY_NOT_FOUND', message: 'x', requestId: 'req_2' }),
    );
    mount();
    await userEvent.type(screen.getByLabelText('Código de acceso'), code);
    await userEvent.click(screen.getByRole('button', { name: 'Unirme a la sala' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No encontramos esa sala');
    expect(screen.getByLabelText('Código de acceso')).toHaveValue(code);
  });

  it('una sala cerrada muestra su motivo', async () => {
    vi.mocked(request).mockRejectedValue(
      new HttpError(409, { code: 'WATCH_PARTY_CLOSED', message: 'x', requestId: 'req_3' }),
    );
    mount();
    await userEvent.type(screen.getByLabelText('Código de acceso'), code);
    await userEvent.click(screen.getByRole('button', { name: 'Unirme a la sala' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('está cerrada');
  });
});
