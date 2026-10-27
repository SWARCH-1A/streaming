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
  levels: [] as { uri: string; height: number; bitrate: number }[],
  selectedLevel: -1,
  setLoadLevel: vi.fn(),
  parsed: undefined as (() => void) | undefined,
  updated: undefined as (() => void) | undefined,
  switched: undefined as ((event: string, data: { level: number }) => void) | undefined,
}));
vi.mock('hls.js', () => ({
  default: class {
    static isSupported() {
      return true;
    }
    static Events = {
      ERROR: 'error',
      MANIFEST_PARSED: 'parsed',
      LEVELS_UPDATED: 'updated',
      LEVEL_SWITCHED: 'switched',
    };
    static ErrorTypes = { NETWORK_ERROR: 'networkError' };
    liveSyncPosition = 40;
    get levels() {
      return hls.levels;
    }
    currentLevel = -1;
    set loadLevel(index: number) {
      hls.selectedLevel = index;
      hls.setLoadLevel(index);
    }
    pauseBuffering = hls.pauseBuffering;
    resumeBuffering = hls.resumeBuffering;
    startLoad = hls.startLoad;
    stopLoad = hls.stopLoad;
    destroy = hls.destroy;
    attachMedia = hls.attachMedia;
    loadSource = vi.fn();
    on(event: string, handler: typeof hls.error | typeof hls.parsed | typeof hls.switched) {
      if (event === 'error') hls.error = handler as typeof hls.error;
      if (event === 'parsed') hls.parsed = handler as typeof hls.parsed;
      if (event === 'updated') hls.updated = handler as typeof hls.updated;
      if (event === 'switched') hls.switched = handler as typeof hls.switched;
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
  hls.levels = [{ uri: '720p.m3u8', height: 720, bitrate: 2_500_000 }];
  hls.selectedLevel = -1;
  hls.parsed = undefined;
  hls.updated = undefined;
  hls.switched = undefined;
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably');
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
});

describe('calidades reales de la emisión', () => {
  async function start() {
    vi.stubGlobal('MediaSource', class {});
    render(<HlsPlayer session={session} title="Directo" />);
    await waitFor(() => expect(hls.attachMedia).toHaveBeenCalled());
    act(() => hls.parsed?.());
    return screen.getByRole('combobox', { name: 'Calidad' });
  }

  it('solo ofrece la fuente 720p recibida y arranca en automático', async () => {
    expect(screen.queryByRole('combobox', { name: 'Calidad' })).not.toBeInTheDocument();
    const selector = await start();
    expect(selector).toHaveValue('');
    expect(screen.getAllByRole('option')).toHaveLength(2);
    expect(screen.getByRole('option', { name: /720p/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /1080p/ })).not.toBeInTheDocument();
    expect(hls.selectedLevel).toBe(-1);
  });

  it('conmuta entre cuatro calidades y automático sin reiniciar medio, posición ni lease', async () => {
    hls.levels = [360, 480, 720, 1080].map((height) => ({
      uri: `${height}p.m3u8`,
      height,
      bitrate: height * 4000,
    }));
    const selector = await start();
    const video = screen.getByLabelText<HTMLVideoElement>('Reproductor de Directo');
    Object.defineProperties(video, {
      paused: { configurable: true, value: false },
      readyState: { configurable: true, value: 2 },
      videoWidth: { configurable: true, value: 1280 },
      requestVideoFrameCallback: { configurable: true, value: undefined },
    });
    video.currentTime = 25;
    fireEvent.playing(video);
    expect(ViewerLeaseController).toHaveBeenCalledOnce();
    const lease = vi.mocked(ViewerLeaseController).mock.results[0]?.value as {
      stop: ReturnType<typeof vi.fn>;
    };
    const load = vi.spyOn(HTMLMediaElement.prototype, 'load');
    const loads = load.mock.calls.length;
    fireEvent.change(selector, { target: { value: '1080p.m3u8' } });
    expect(hls.selectedLevel).toBe(3);
    act(() => hls.switched?.('switched', { level: 3 }));
    expect(screen.getByRole('status')).toHaveTextContent('Reproduciendo 1080p');
    fireEvent.change(selector, { target: { value: '' } });
    expect(hls.selectedLevel).toBe(-1);
    expect(video.currentTime).toBe(25);
    expect(hls.destroy).not.toHaveBeenCalled();
    expect(hls.attachMedia).toHaveBeenCalledOnce();
    expect(load).toHaveBeenCalledTimes(loads);
    expect(ViewerLeaseController).toHaveBeenCalledOnce();
    expect(lease.stop).not.toHaveBeenCalled();
  });

  it('conserva la variante al reordenar y vuelve a automático si desaparece', async () => {
    hls.levels.push({ uri: '360p.m3u8', height: 360, bitrate: 800_000 });
    const selector = await start();
    fireEvent.change(selector, { target: { value: '720p.m3u8' } });
    hls.levels.reverse();
    act(() => hls.updated?.());
    expect(selector).toHaveValue('720p.m3u8');
    expect(hls.selectedLevel).toBe(1);
    hls.levels.pop();
    act(() => hls.updated?.());
    expect(selector).toHaveValue('');
    expect(hls.selectedLevel).toBe(-1);
    expect(screen.queryByRole('option', { name: /720p/ })).not.toBeInTheDocument();
  });

  it('delega selección automática al navegador con HLS nativo', async () => {
    render(<HlsPlayer session={session} title="Directo" />);
    expect(
      await screen.findByText('Calidad automática gestionada por el navegador.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Calidad' })).not.toBeInTheDocument();
  });

  it('descarta eventos del motor anterior y reinicia selección al cambiar de sesión', async () => {
    vi.stubGlobal('MediaSource', class {});
    const { rerender } = render(<HlsPlayer session={session} title="Directo" />);
    await waitFor(() => expect(hls.attachMedia).toHaveBeenCalledOnce());
    act(() => hls.parsed?.());
    fireEvent.change(screen.getByRole('combobox', { name: 'Calidad' }), {
      target: { value: '720p.m3u8' },
    });
    const previousParsed = hls.parsed;
    const previousSwitched = hls.switched;
    const sessionId = 'ses_550e8400-e29b-41d4-a716-446655440001';
    rerender(
      <HlsPlayer
        session={{
          ...session,
          sessionId,
          playbackUrl: `/hls/${sessionId}/index.m3u8`,
        }}
        title="Otro directo"
      />,
    );
    await waitFor(() => expect(hls.attachMedia).toHaveBeenCalledTimes(2));
    expect(hls.destroy).toHaveBeenCalledOnce();
    act(() => {
      previousParsed?.();
      previousSwitched?.('switched', { level: 0 });
    });
    expect(screen.queryByRole('combobox', { name: 'Calidad' })).not.toBeInTheDocument();
    act(() => hls.parsed?.());
    expect(screen.getByRole('combobox', { name: 'Calidad' })).toHaveValue('');
    expect(hls.selectedLevel).toBe(-1);
  });
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
