import type { P2ReplayMessage, P2Vod } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';

import { Button } from '@/src/components/atoms/Button';
import { request } from '@/src/shared/api/http';

import { VodPage } from './VodPage';

vi.mock('./VodPlayer', () => ({
  VodPlayer: ({ onPosition }: { onPosition: (ms: number, grant: string) => void }) => (
    <>
      <Button onClick={() => onPosition(1000, 'grant-1')}>posición inicial</Button>
      <Button onClick={() => onPosition(1200, 'grant-2')}>permiso renovado</Button>
    </>
  ),
}));
vi.mock('@/src/shared/api/http', () => ({
  request: vi.fn(),
  errorMessage: (reason: unknown) => String(reason),
}));

const vod = {
  ...(schema.$defs.P2Vod.examples[0] as P2Vod),
  processingState: 'READY',
  durationMs: 60000,
} as P2Vod;

function item(sequence: number, text: string): P2ReplayMessage {
  return {
    messageId: `msg_${sequence}`,
    sessionId: vod.sessionId,
    sequence,
    author: { userId: 'usr_ana', handle: 'ana_01', displayName: 'Ana', avatarUri: null },
    text,
    serverCreatedAtUtc: '2026-10-31T20:00:00Z',
    streamOffsetMs: sequence * 100,
    streamGeneration: 1,
    mediaOffsetMs: sequence * 100,
    moderationVersion: 1,
  };
}

function page(items: P2ReplayMessage[]) {
  return {
    vodId: vod.vodId,
    mappingVersion: vod.mappingVersion,
    moderationVersion: 2,
    items,
    nextCursor: null,
  };
}

it('al refrescar una ventana deja de mostrar un mensaje retirado después de cargarlo', async () => {
  vi.mocked(request)
    .mockResolvedValueOnce(page([item(1, 'será retirado'), item(2, 'se queda')]))
    .mockResolvedValueOnce(page([item(2, 'se queda')]));
  render(<VodPage vod={vod} />);
  fireEvent.click(screen.getByText('posición inicial'));
  expect(await screen.findByText('será retirado', { exact: false })).toBeInTheDocument();
  const log = screen.getByRole('log', { name: 'Mensajes del chat' });
  // VodPlayer renueva el permiso dentro de la misma ventana de 10 s: se pide de nuevo la primera página.
  fireEvent.click(screen.getByText('permiso renovado'));
  await waitFor(() => expect(log).not.toHaveTextContent('será retirado'));
  expect(request).toHaveBeenCalledTimes(2);
  expect(log).toHaveTextContent('se queda');
});
