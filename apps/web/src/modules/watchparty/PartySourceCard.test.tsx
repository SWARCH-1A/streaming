import { useEffect } from 'react';
import { MemoryRouter } from 'react-router';
import type { P2WatchParty } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { PartySourceCard } from './PartySourceCard';

const released = vi.hoisted(() => vi.fn<(sessionId: string) => void>());
const mounted = vi.hoisted(() => vi.fn<(sessionId: string) => void>());
vi.mock('./PartySourcePlayer', () => ({
  PartySourcePlayer: ({ sessionId }: { sessionId: string }) => {
    useEffect(() => {
      mounted(sessionId);
      return () => {
        released(sessionId);
      };
    }, [sessionId]);
    return <p>Reproductor {sessionId}</p>;
  },
}));

const [live, suspended] = (schema.$defs.P2WatchParty.examples[0] as unknown as P2WatchParty)
  .sources as [P2WatchParty['sources'][number], P2WatchParty['sources'][number]];

function card(source = live, props: Partial<Parameters<typeof PartySourceCard>[0]> = {}) {
  return (
    <MemoryRouter>
      <PartySourceCard
        source={source}
        roomOpen
        canRemove={false}
        busy={false}
        onRemove={() => undefined}
        {...props}
      />
    </MemoryRouter>
  );
}

beforeEach(() => {
  released.mockClear();
  mounted.mockClear();
});

describe('fuente de una sala', () => {
  it('reproduce una fuente PLAYABLE y muestra su canal, categoría y espectadores', () => {
    render(card());
    expect(screen.getByText(`Reproductor ${live.sessionId}`)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Canal de prueba (@fixture)' })).toHaveAttribute(
      'href',
      '/channels/fixture',
    );
    expect(screen.getByText('EN VIVO')).toBeInTheDocument();
    expect(screen.getByText(/Conversación · 3 espectadores/)).toBeInTheDocument();
  });

  it.each(['RECONNECTING', 'OFFLINE', 'ENDED', 'UNKNOWN'] as const)(
    '%s no monta reproductor ni simula señal',
    (state) => {
      render(card({ ...live, state, sessionId: null }));
      expect(screen.queryByText(/Reproductor/)).not.toBeInTheDocument();
      expect(screen.queryByText('EN VIVO')).not.toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument();
    },
  );

  it('una fuente suspendida solo explica que no está disponible y no revela canal ni metadatos', () => {
    render(card(suspended));
    expect(screen.getByRole('heading', { name: 'Fuente no disponible' })).toBeInTheDocument();
    expect(screen.getByText('NO DISPONIBLE')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText(/Reproductor/)).not.toBeInTheDocument();
  });

  it('una sala cerrada no reproduce nada aunque la fuente siga marcada PLAYABLE', () => {
    render(card(live, { roomOpen: false }));
    expect(screen.queryByText(/Reproductor/)).not.toBeInTheDocument();
    expect(screen.getByText('SALA CERRADA')).toBeInTheDocument();
  });

  it('libera el reproductor al dejar de ser PLAYABLE, al cambiar de sesión y al desmontar', () => {
    const view = render(card());
    expect(mounted).toHaveBeenCalledWith(live.sessionId);
    view.rerender(card({ ...live, sessionId: 'ses_nueva' }));
    expect(released).toHaveBeenCalledWith(live.sessionId);
    expect(mounted).toHaveBeenLastCalledWith('ses_nueva');
    view.rerender(card({ ...live, state: 'ENDED', sessionId: null }));
    expect(released).toHaveBeenLastCalledWith('ses_nueva');
    expect(screen.queryByText(/Reproductor/)).not.toBeInTheDocument();
    view.rerender(card());
    view.rerender(card(live, { roomOpen: false }));
    expect(released).toHaveBeenCalledTimes(3);
    view.rerender(card());
    view.unmount();
    expect(released).toHaveBeenCalledTimes(4);
  });

  it('solo ofrece quitar a quien puede y avisa con la fuente elegida', async () => {
    const onRemove = vi.fn();
    const view = render(card(live, { canRemove: true, onRemove }));
    await userEvent.click(screen.getByRole('button', { name: 'Quitar Conversación de la sala' }));
    expect(onRemove).toHaveBeenCalledWith(live);
    view.rerender(card(live, { canRemove: false, onRemove }));
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
