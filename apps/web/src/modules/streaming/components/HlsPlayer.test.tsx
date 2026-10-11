import type { PublicSession } from '@contracts/p1';
import schema from '@contracts/p1.schema.json';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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

const hls = vi.hoisted(() => ({
  error: undefined as
    | undefined
    | ((
        event: string,
        data: { fatal: boolean; type: string; response?: { code: number } },
      ) => void),
  pauseBuffering: vi.fn(),
  resumeBuffering: vi.fn(),
  startLoad: vi.fn(),
  stopLoad: vi.fn(),
  destroy: vi.fn(),
  attachMedia: vi.fn(),
}));
vi.mock('hls.js', () => ({
  default: class {
    static isSupported() {
      return true;
    }
    static Events = { ERROR: 'error' };
    static ErrorTypes = { NETWORK_ERROR: 'networkError' };
    liveSyncPosition = 40;
    levels = [{}];
    pauseBuffering = hls.pauseBuffering;
    resumeBuffering = hls.resumeBuffering;
    startLoad = hls.startLoad;
    stopLoad = hls.stopLoad;
    destroy = hls.destroy;
    attachMedia = hls.attachMedia;
    loadSource = vi.fn();
    on(_event: string, handler: typeof hls.error) {
      hls.error = handler;
    }
  },
}));

function buffer(video: HTMLVideoElement, ranges: [number, number][]) {
  Object.defineProperty(video, 'buffered', {
    configurable: true,
    value: {
      length: ranges.length,
      start: (i: number) => ranges[i]![0],
      end: (i: number) => ranges[i]![1],
    },
  });
}

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
  vi.useRealTimers();
  vi.unstubAllGlobals();
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
    Object.defineProperty(video, 'paused', { configurable: true, value: false });
    fireEvent.error(video);
    fireEvent.playing(video);
    expect(screen.getByText(failure)).toBeInTheDocument();
    expect(ViewerLeaseController).not.toHaveBeenCalled();
  });
});

describe('pausa y finalización', () => {
  it('usa MediaSource aunque el navegador anuncie HLS nativo', async () => {
    vi.stubGlobal('MediaSource', class {});
    render(<HlsPlayer session={session} title="Directo" />);
    const video = screen.getByLabelText<HTMLVideoElement>('Reproductor de Directo');
    await waitFor(() => expect(hls.attachMedia).toHaveBeenCalledWith(video));
    expect(video.src).toBe('');
  });

  it('reanuda tras cinco segundos sin destruir el búfer ni mostrar un fallo', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('');
    render(<HlsPlayer session={session} title="Directo" />);
    const video = screen.getByLabelText<HTMLVideoElement>('Reproductor de Directo');
    await waitFor(() => expect(hls.attachMedia).toHaveBeenCalled());
    buffer(video, [[10, 17]]);
    video.currentTime = 12;
    fireEvent.pause(video);
    expect(hls.pauseBuffering).toHaveBeenCalledOnce();
    vi.useFakeTimers();
    act(() => {
      hls.error?.('error', { fatal: true, type: 'networkError' });
      vi.advanceTimersByTime(5000);
    });
    expect(screen.queryByText(failure)).not.toBeInTheDocument();
    expect(hls.destroy).not.toHaveBeenCalled();
    fireEvent.play(video);
    expect(video.currentTime).toBe(12);
    expect(hls.startLoad).toHaveBeenCalledWith(12);
    expect(hls.resumeBuffering).toHaveBeenCalledOnce();
  });

  it('retoma el siguiente segmento local o la señal actual si ya no hay segmentos', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('');
    render(<HlsPlayer session={session} title="Directo" />);
    const video = screen.getByLabelText<HTMLVideoElement>('Reproductor de Directo');
    await waitFor(() => expect(hls.attachMedia).toHaveBeenCalled());
    video.currentTime = 3;
    buffer(video, [[10, 15]]);
    fireEvent.play(video);
    expect(video.currentTime).toBe(10);
    buffer(video, []);
    fireEvent.play(video);
    expect(video.currentTime).toBe(40);
    expect(screen.queryByText(failure)).not.toBeInTheDocument();
  });

  it.each([true, false])(
    'consume los segmentos antes de mostrar finalización (pausado: %s)',
    (paused) => {
      const { rerender } = render(<HlsPlayer session={session} title="Directo" />);
      const video = screen.getByLabelText<HTMLVideoElement>('Reproductor de Directo');
      Object.defineProperty(video, 'paused', { configurable: true, value: paused });
      video.currentTime = 10;
      buffer(video, [
        [5, 15],
        [17, 20],
      ]);
      const source = video.src;
      const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
      pause.mockClear();
      const load = vi.spyOn(HTMLMediaElement.prototype, 'load');
      rerender(
        <HlsPlayer
          session={{ ...session, status: 'ENDED', availability: 'OFFLINE', playbackUrl: null }}
          title="Directo"
        />,
      );
      expect(video.src).toBe(source);
      expect(load).not.toHaveBeenCalled();
      expect(pause).not.toHaveBeenCalled();
      expect(screen.queryByText('La emisión ha finalizado.')).not.toBeInTheDocument();
      Object.defineProperty(video, 'paused', { configurable: true, value: false });
      fireEvent.play(video);
      expect(video.currentTime).toBe(10);
      video.currentTime = 15;
      fireEvent.timeUpdate(video);
      expect(video.currentTime).toBe(17);
      video.currentTime = 20;
      fireEvent.waiting(video);
      expect(screen.getByText('La emisión ha finalizado.')).toBeInTheDocument();
      expect(pause).toHaveBeenCalledOnce();
      expect(screen.queryByText(failure)).not.toBeInTheDocument();
      expect(ViewerLeaseController).not.toHaveBeenCalled();
    },
  );

  it('un 410 no descarta vídeo ni muestra error antes de recibir ENDED', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('');
    const { rerender } = render(<HlsPlayer session={session} title="Directo" />);
    const video = screen.getByLabelText<HTMLVideoElement>('Reproductor de Directo');
    await waitFor(() => expect(hls.attachMedia).toHaveBeenCalled());
    buffer(video, [[1, 8]]);
    video.currentTime = 4;
    act(() => hls.error?.('error', { fatal: true, type: 'networkError', response: { code: 410 } }));
    expect(hls.stopLoad).toHaveBeenCalled();
    expect(hls.destroy).not.toHaveBeenCalled();
    expect(screen.queryByText(failure)).not.toBeInTheDocument();
    rerender(
      <HlsPlayer
        session={{ ...session, status: 'ENDED', availability: 'OFFLINE', playbackUrl: null }}
        title="Directo"
      />,
    );
    expect(hls.destroy).not.toHaveBeenCalled();
    expect(screen.queryByText('La emisión ha finalizado.')).not.toBeInTheDocument();
    video.currentTime = 8;
    fireEvent.timeUpdate(video);
    expect(screen.getByText('La emisión ha finalizado.')).toBeInTheDocument();
  });
});

it('cierra el conteo al finalizar y no abre leases durante los segmentos restantes', () => {
  const { rerender } = render(<HlsPlayer session={session} title="Directo" />);
  const video = screen.getByLabelText<HTMLVideoElement>('Reproductor de Directo');
  Object.defineProperties(video, {
    paused: { configurable: true, value: false },
    readyState: { configurable: true, value: 2 },
    videoWidth: { configurable: true, value: 1280 },
    requestVideoFrameCallback: { configurable: true, value: undefined },
  });
  buffer(video, [[1, 8]]);
  video.currentTime = 4;
  fireEvent.playing(video);
  const lease = vi.mocked(ViewerLeaseController).mock.results[0]?.value as {
    stop: ReturnType<typeof vi.fn>;
  };
  expect(ViewerLeaseController).toHaveBeenCalledOnce();
  rerender(
    <HlsPlayer
      session={{ ...session, status: 'ENDED', availability: 'OFFLINE', playbackUrl: null }}
      title="Directo"
    />,
  );
  expect(lease.stop).toHaveBeenCalledOnce();
  fireEvent.playing(video);
  expect(ViewerLeaseController).toHaveBeenCalledOnce();
  expect(screen.queryByText('La emisión ha finalizado.')).not.toBeInTheDocument();
});
