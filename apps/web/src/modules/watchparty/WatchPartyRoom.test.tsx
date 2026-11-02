import { useEffect } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { P2WatchParty } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as Http from '@/src/shared/api/http';
import { HttpError, request } from '@/src/shared/api/http';

import { hideCode } from './codeVault';
import { WatchPartyRoom } from './WatchPartyRoom';

vi.mock('@/src/shared/api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof Http>()),
  request: vi.fn(),
}));
vi.mock('@/src/shell/session/useSession', () => ({
  useSession: () => ({ user: { userId: 'usr_fixture' } }),
}));
const released = vi.hoisted(() => vi.fn<(sessionId: string) => void>());
vi.mock('./PartySourcePlayer', () => ({
  PartySourcePlayer: ({ sessionId }: { sessionId: string }) => {
    useEffect(
      () => () => {
        released(sessionId);
      },
      [sessionId],
    );
    return <p>Reproductor {sessionId}</p>;
  },
}));
vi.mock('./liveStreams', () => ({
  loadLiveStreams: () =>
    Promise.resolve([
      { streamId: 'str_otra', title: 'Otra emisión', channel: { displayName: 'Canal B' } },
      {
        streamId: 'str_fixture',
        title: 'Conversación',
        channel: { displayName: 'Canal de prueba' },
      },
    ]),
}));

const [member, created, closed] = schema.$defs.P2WatchParty
  .examples as unknown as P2WatchParty[] as [P2WatchParty, P2WatchParty, P2WatchParty];
const owner: P2WatchParty = { ...member, isOwner: true };
const id = member.partyId;

function error(status: number, code: string) {
  return new HttpError(status, { code, message: 'servidor', requestId: 'req_test' });
}
function mount() {
  return render(
    <MemoryRouter initialEntries={[`/watch-parties/${id}`]}>
      <Routes>
        <Route path="/watch-parties/:partyId" element={<WatchPartyRoom />} />
      </Routes>
    </MemoryRouter>,
  );
}
type Handler = (
  path: string,
  options: { method?: string; headers?: Record<string, string> },
) => unknown;
function serve(handler: Handler) {
  vi.mocked(request).mockImplementation((path: string, options: object = {}) => {
    try {
      return Promise.resolve(handler(path, options));
    } catch (failure) {
      return Promise.reject(failure instanceof Error ? failure : new Error(String(failure)));
    }
  });
}
function commands() {
  return vi
    .mocked(request)
    .mock.calls.filter(([, options]) => options && options.method && options.method !== 'GET');
}

beforeEach(() => {
  window.localStorage.clear();
  hideCode(id);
});
afterEach(() => {
  vi.useRealTimers();
  vi.resetAllMocks();
  released.mockClear();
});

describe('sala de visualización conjunta', () => {
  it('un miembro ve la sala y sus fuentes, pero no los controles del propietario', async () => {
    serve(() => member);
    mount();
    expect(
      await screen.findByRole('heading', { level: 1, name: member.title }),
    ).toBeInTheDocument();
    expect(screen.getByText(/2 de 4 transmisiones/)).toBeInTheDocument();
    expect(screen.getByText(/Creada por Cuenta de prueba/)).toBeInTheDocument();
    expect(screen.getByText(`Reproductor ${member.sources[0]?.sessionId}`)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Fuente no disponible' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Administrar la sala' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Quitar/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Eres miembro de esta sala/)).toBeInTheDocument();
  });

  it('el propietario añade una fuente con Idempotency-Key y CSRF, y la sala se actualiza', async () => {
    serve((_path, options) => {
      if (options.method === 'POST')
        return {
          ...owner,
          partyVersion: 3,
          sources: [
            ...owner.sources,
            { ...owner.sources[0], streamId: 'str_otra', title: 'Otra emisión' },
          ],
        };
      return owner;
    });
    mount();
    await screen.findByRole('heading', { name: 'Administrar la sala' });
    await screen.findByRole('option', { name: /Otra emisión/ });
    await userEvent.selectOptions(
      screen.getByLabelText('Añadir una transmisión en vivo'),
      'str_otra',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Añadir a la sala' }));
    await waitFor(() =>
      expect(screen.getAllByText(/3 de 4 transmisiones/).length).toBeGreaterThan(0),
    );
    const [path, options] = commands()[0] ?? [];
    expect(path).toBe(`/api/watch-parties/${id}/streams`);
    expect(options).toMatchObject({
      method: 'POST',
      coreMutation: true,
      body: { streamId: 'str_otra' },
    });
    expect((options as { headers: Record<string, string> }).headers['Idempotency-Key']).toMatch(
      /^[0-9a-f-]{36}$/,
    );
    expect(screen.queryByRole('option', { name: /Otra emisión/ })).not.toBeInTheDocument();
  });

  it('muestra en español el error del contrato y no deja pulsar dos veces mientras se envía', async () => {
    let finish: (value: unknown) => void = () => undefined;
    serve((_path, options) => {
      if (options.method === 'POST') return new Promise((resolve) => (finish = resolve));
      return owner;
    });
    mount();
    await screen.findByRole('option', { name: /Otra emisión/ });
    await userEvent.selectOptions(
      screen.getByLabelText('Añadir una transmisión en vivo'),
      'str_otra',
    );
    const add = screen.getByRole('button', { name: 'Añadir a la sala' });
    await userEvent.click(add);
    await userEvent.click(add);
    expect(commands()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Rotar código de acceso' })).toBeDisabled();
    await act(async () => {
      finish(Promise.reject(error(409, 'WATCH_PARTY_FULL')));
      await Promise.resolve();
    });
    expect(await screen.findByRole('alert')).toHaveTextContent('máximo de 4 transmisiones');
    expect(screen.getByRole('alert')).toHaveTextContent('WATCH_PARTY_FULL · req_test');
  });

  it('repetir la misma acción tras un fallo incierto reutiliza la clave; tras un éxito usa otra', async () => {
    let attempts = 0;
    serve((_path, options) => {
      if (options.method === 'POST' && ++attempts === 1) throw error(503, 'CORE_UNAVAILABLE');
      return options.method === 'POST' ? { ...owner, partyVersion: attempts } : owner;
    });
    mount();
    await screen.findByRole('option', { name: /Otra emisión/ });
    await userEvent.selectOptions(
      screen.getByLabelText('Añadir una transmisión en vivo'),
      'str_otra',
    );
    const add = screen.getByRole('button', { name: 'Añadir a la sala' });
    await userEvent.click(add);
    await screen.findByRole('alert');
    await userEvent.click(add);
    await waitFor(() => expect(commands()).toHaveLength(2));
    const keys = commands().map(
      ([, o]) => (o as { headers: Record<string, string> }).headers['Idempotency-Key'],
    );
    expect(keys[0]).toBe(keys[1]);
  });

  it('rotar muestra el código nuevo una sola vez y permite ocultarlo', async () => {
    const code = 'z'.repeat(43);
    serve((path, options) =>
      options.method === 'POST' && path.endsWith('/access-code/rotate')
        ? { ...owner, accessCode: code }
        : owner,
    );
    mount();
    await userEvent.click(await screen.findByRole('button', { name: 'Rotar código de acceso' }));
    const panel = await screen.findByRole('region', { name: 'Código de acceso' });
    expect(within(panel).getByLabelText('Código')).toHaveValue(code);
    expect(within(panel).getByLabelText('Enlace para compartir')).toHaveValue(
      `${window.location.origin}/watch-parties/join#${code}`,
    );
    expect(window.localStorage.length).toBe(1); // solo la sala reciente, sin el código
    expect(JSON.stringify({ ...window.localStorage })).not.toContain(code);
    await userEvent.click(within(panel).getByRole('button', { name: /ocultar/ }));
    expect(screen.queryByRole('region', { name: 'Código de acceso' })).not.toBeInTheDocument();
  });

  it('dos rotaciones distintas usan claves distintas aunque la primera haya salido bien', async () => {
    serve((_path, options) =>
      options.method === 'POST' ? { ...owner, accessCode: 'k'.repeat(43) } : owner,
    );
    mount();
    const rotate = await screen.findByRole('button', { name: 'Rotar código de acceso' });
    await userEvent.click(rotate);
    await screen.findByRole('region', { name: 'Código de acceso' });
    await userEvent.click(rotate);
    await waitFor(() => expect(commands()).toHaveLength(2));
    const [first, second] = commands().map(
      ([, o]) => (o as { headers: Record<string, string> }).headers['Idempotency-Key'],
    );
    expect(first).not.toBe(second);
  });

  it('una repetición idempotente de la rotación explica que el código no puede volver a mostrarse', async () => {
    serve((_path, options) => (options.method === 'POST' ? { ...owner, accessCode: null } : owner));
    mount();
    await userEvent.click(await screen.findByRole('button', { name: 'Rotar código de acceso' }));
    expect(await screen.findByText(/no se puede volver a mostrar/)).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Código de acceso' })).not.toBeInTheDocument();
  });

  it('cerrar pide confirmación y deja la sala sin controles ni reproductores', async () => {
    serve((path, options) =>
      options.method === 'POST' && path.endsWith('/close') ? closed : owner,
    );
    mount();
    await userEvent.click(await screen.findByRole('button', { name: 'Cerrar sala' }));
    expect(commands()).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(commands()).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar sala' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sí, cerrar la sala' }));
    expect(await screen.findByText(/La sala está cerrada/)).toBeInTheDocument();
    expect(screen.getByText('CERRADA')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Administrar la sala' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Reproductor/)).not.toBeInTheDocument();
    expect(released).toHaveBeenCalledWith(member.sources[0]?.sessionId);
  });

  it('conserva la última sala conocida si una lectura falla y suelta el player al cambiar el estado', async () => {
    vi.useFakeTimers();
    const ended = {
      ...member,
      partyVersion: 3,
      sources: [{ ...member.sources[0], state: 'ENDED', sessionId: null }, member.sources[1]],
    };
    const replies: unknown[] = [member, error(503, 'CORE_UNAVAILABLE'), ended];
    serve(() => {
      const next = replies.shift();
      if (next instanceof Error) throw next;
      return next;
    });
    mount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText(/Reproductor/)).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(screen.getByRole('heading', { level: 1, name: member.title })).toBeInTheDocument();
    expect(screen.getByText(/se muestra la última información conocida/)).toBeInTheDocument();
    expect(screen.getByText(/Reproductor/)).toBeInTheDocument();
    expect(released).not.toHaveBeenCalled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(screen.queryByText(/Reproductor/)).not.toBeInTheDocument();
    expect(released).toHaveBeenCalledTimes(1);
    expect(document.body).toHaveTextContent('finalizada');
  });

  it('una sala inexistente o ajena se explica y permite quitarla de las recientes', async () => {
    window.localStorage.setItem(
      'vault.watchparty.recent.v1:usr_fixture',
      JSON.stringify([{ partyId: id, title: 'Vieja' }]),
    );
    serve(() => {
      throw error(404, 'WATCH_PARTY_NOT_FOUND');
    });
    mount();
    expect(
      await screen.findByRole('heading', { name: 'No se pudo abrir la sala' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('No encontramos esa sala');
    await userEvent.click(screen.getByRole('button', { name: 'Quitar de salas recientes' }));
    expect(window.localStorage.getItem('vault.watchparty.recent.v1:usr_fixture')).toBe('[]');
  });

  it('un identificador mal formado no consulta nada', async () => {
    render(
      <MemoryRouter initialEntries={['/watch-parties/no-es-una-sala']}>
        <Routes>
          <Route path="/watch-parties/:partyId" element={<WatchPartyRoom />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText('Sala no encontrada')).toBeInTheDocument();
    expect(request).not.toHaveBeenCalled();
  });

  it('la sala recién creada muestra su código una vez y la sala vacía invita a añadir fuentes', async () => {
    const { revealCode } = await import('./codeVault');
    revealCode(id, created.accessCode ?? '');
    serve(() => ({ ...created, partyId: id }));
    mount();
    expect(await screen.findByRole('region', { name: 'Código de acceso' })).toBeInTheDocument();
    expect(screen.getByText('La sala aún no tiene transmisiones')).toBeInTheDocument();
  });
});
