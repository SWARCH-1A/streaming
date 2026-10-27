import { hydrateRoot } from 'react-dom/client';
import { renderToString } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { ChannelBootstrap, PublicSession, PublicStream } from '@contracts/p1';
import schema from '@contracts/p1.schema.json';
import { act } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';

import { request } from '@/src/shared/api/http';

import { WatchRoute } from './WatchRoute';

vi.mock('@/src/shared/api/http', () => ({ request: vi.fn() }));
vi.mock('@/src/shell/session/useSession', () => ({ useSession: () => ({ user: null }) }));
vi.mock('@/src/modules/streaming/entry', () => ({
  HlsPlayer: ({ session }: { session: PublicSession }) => <p>Señal {session.status}</p>,
}));
vi.mock('@/src/modules/chat/entry', () => ({ LiveChatPanel: () => <aside>Chat</aside> }));

afterEach(() => {
  vi.useRealTimers();
  vi.resetAllMocks();
});

it('hydrates the live screen without repeating its reads and revalidates the session after 4 s', async () => {
  vi.useFakeTimers();
  const stream = schema.$defs.PublicStream.examples[0] as PublicStream;
  const session = schema.$defs.PublicSession.examples[0] as PublicSession;
  const channel = schema.$defs.ChannelBootstrap.examples[0] as ChannelBootstrap;
  vi.mocked(request).mockImplementation((path) =>
    Promise.resolve(
      path === `/api/streams/${stream.streamId}`
        ? { ...stream, availability: 'RECONNECTING' }
        : { ...session, status: 'RECONNECT_GRACE', availability: 'RECONNECTING' },
    ),
  );
  const view = (
    <MemoryRouter initialEntries={[`/watch/${stream.streamId}`]}>
      <Routes>
        <Route
          path="/watch/:streamId"
          element={<WatchRoute initial={{ stream, session }} initialChannel={channel} />}
        />
      </Routes>
    </MemoryRouter>
  );
  const container = document.createElement('div');
  container.innerHTML = renderToString(view);
  document.body.append(container);
  expect(container.querySelector('h1')?.textContent).toBe(stream.title);
  expect(container.textContent).toContain('Fixture');
  expect(request).not.toHaveBeenCalled();
  const recover = vi.fn();
  const root = hydrateRoot(container, view, { onRecoverableError: recover });
  try {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(recover).not.toHaveBeenCalled();
    expect(request).not.toHaveBeenCalled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    expect(vi.mocked(request).mock.calls.map(([path]) => path)).toEqual([
      `/api/streams/${stream.streamId}`,
      `/api/streams/sessions/${session.sessionId}`,
    ]);
    expect(container.textContent).toContain('Reconectando');
    expect(container.textContent).toContain('Señal RECONNECT_GRACE');
  } finally {
    act(() => root.unmount());
    container.remove();
  }
});
