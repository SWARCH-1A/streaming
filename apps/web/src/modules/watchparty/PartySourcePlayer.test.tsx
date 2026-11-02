import type { PublicSession } from '@contracts/p1';
import p1 from '@contracts/p1.schema.json';
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import type * as Http from '@/src/shared/api/http';
import { HttpError, request } from '@/src/shared/api/http';

import { PartySourcePlayer } from './PartySourcePlayer';

vi.mock('@/src/shared/api/http', async (importOriginal) => ({
  ...(await importOriginal<typeof Http>()),
  request: vi.fn(),
}));
vi.mock('@/src/modules/streaming/entry', () => ({
  HlsPlayer: ({ session, title }: { session: PublicSession; title: string }) => (
    <p>
      {title} · {session.sessionId} · {session.status}
    </p>
  ),
}));

const session = p1.$defs.PublicSession.examples[0] as PublicSession;

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  vi.resetAllMocks();
});
async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

it('lee la sesión pública de la fuente y se la entrega al reproductor existente', async () => {
  vi.mocked(request).mockResolvedValue(session);
  render(
    <PartySourcePlayer sessionId={session.sessionId} streamId={session.streamId} title="Directo" />,
  );
  expect(screen.getByRole('status')).toHaveTextContent('Cargando reproductor');
  await tick(0);
  expect(request).toHaveBeenCalledWith(
    `/api/streams/sessions/${session.sessionId}`,
    expect.objectContaining({ signal: expect.any(AbortSignal) as AbortSignal }),
  );
  expect(screen.getByText(`Directo · ${session.sessionId} · LIVE`)).toBeInTheDocument();
});

it('no entrega al reproductor una sesión que no es la de la fuente', async () => {
  vi.mocked(request).mockResolvedValue({ ...session, streamId: 'str_ajena' });
  render(
    <PartySourcePlayer sessionId={session.sessionId} streamId={session.streamId} title="Directo" />,
  );
  await tick(0);
  expect(screen.getByText(/cambió/)).toBeInTheDocument();
  expect(screen.queryByText(/Directo ·/)).not.toBeInTheDocument();
});

it('si la sesión falla al abrir lo dice; si falla después conserva la última y avisa', async () => {
  vi.mocked(request).mockRejectedValue(
    new HttpError(404, { code: 'SESSION_NOT_FOUND', message: 'x', requestId: 'req_4' }),
  );
  const first = render(
    <PartySourcePlayer sessionId={session.sessionId} streamId={session.streamId} title="Directo" />,
  );
  await tick(0);
  expect(screen.getByRole('alert')).toHaveTextContent('No se pudo cargar la transmisión');
  first.unmount();

  vi.mocked(request)
    .mockResolvedValueOnce(session)
    .mockRejectedValueOnce(
      new HttpError(503, { code: 'STREAMING_UNAVAILABLE', message: 'x', requestId: 'req_5' }),
    );
  render(
    <PartySourcePlayer sessionId={session.sessionId} streamId={session.streamId} title="Directo" />,
  );
  await tick(0);
  await tick(4000);
  expect(screen.getByText(`Directo · ${session.sessionId} · LIVE`)).toBeInTheDocument();
  expect(screen.getByText(/se mantiene la última conocida/)).toBeInTheDocument();
});
