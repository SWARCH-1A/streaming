import type { PublicSession } from '@contracts/p1';
import schema from '@contracts/p1.schema.json';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ViewerLeaseController } from '@/src/modules/streaming/ViewerLease';

import { HlsPlayer } from './HlsPlayer';

vi.mock('@/src/modules/streaming/ViewerLease', () => ({
  ViewerLeaseController: vi.fn(
    class {
      start = vi.fn();
      stop = vi.fn();
    },
  ),
}));

const session = schema.$defs.PublicSession.examples[0] as PublicSession;
const failure = 'No se pudo reproducir la señal. Puedes reintentar.';

beforeEach(() => {
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably');
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe('recuperación de reproducción HLS', () => {
  it.each([true, false])('limpia el error tras un frame recuperado (callback: %s)', (frameApi) => {
    const { rerender } = render(<HlsPlayer session={session} title="Directo" />);
    const video = screen.getByLabelText<HTMLVideoElement>('Reproductor de Directo');
    Object.defineProperties(video, {
      paused: { configurable: true, value: false },
      readyState: { configurable: true, value: 2 },
      videoWidth: { configurable: true, value: 1280 },
    });
    let decoded: (() => void) | undefined;
    Object.defineProperties(video, {
      requestVideoFrameCallback: {
        configurable: true,
        value: frameApi
          ? vi.fn((callback: () => void) => {
              decoded = callback;
              return 1;
            })
          : undefined,
      },
      cancelVideoFrameCallback: { configurable: true, value: vi.fn() },
    });

    fireEvent.error(video);
    expect(screen.getByText(failure)).toBeInTheDocument();
    rerender(<HlsPlayer session={{ ...session, availability: 'RECONNECTING' }} title="Directo" />);
    rerender(<HlsPlayer session={session} title="Directo" />);
    expect(screen.getByText(failure)).toBeInTheDocument();
    expect(ViewerLeaseController).not.toHaveBeenCalled();

    fireEvent.playing(video);
    if (frameApi) {
      expect(screen.getByText(failure)).toBeInTheDocument();
      expect(ViewerLeaseController).not.toHaveBeenCalled();
      act(() => decoded?.());
    }
    expect(screen.queryByText(failure)).not.toBeInTheDocument();
    expect(ViewerLeaseController).toHaveBeenCalledExactlyOnceWith(
      session.sessionId,
      expect.any(Function),
    );
    const lease = vi.mocked(ViewerLeaseController).mock.results[0]?.value as {
      start: ReturnType<typeof vi.fn>;
    };
    expect(lease.start).toHaveBeenCalledOnce();

    fireEvent.playing(video);
    if (frameApi) act(() => decoded?.());
    expect(ViewerLeaseController).toHaveBeenCalledTimes(1);
  });

  it('conserva el error sin datos de vídeo decodificados', () => {
    render(<HlsPlayer session={session} title="Directo" />);
    const video = screen.getByLabelText('Reproductor de Directo');
    fireEvent.error(video);
    fireEvent.playing(video);
    expect(screen.getByText(failure)).toBeInTheDocument();
    expect(ViewerLeaseController).not.toHaveBeenCalled();
  });
});
