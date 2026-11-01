import type { P2SubtitlePage, P2SubtitleTrack, P2Vod } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { request } from '@/src/shared/api/http';

import { VodPlayer } from './VodPlayer';

const hls = vi.hoisted(() => ({
  attach: vi.fn(),
  load: vi.fn(),
  stop: vi.fn(),
  destroy: vi.fn(),
  parsed: undefined as (() => void) | undefined,
  selected: -1,
}));
vi.mock('hls.js', () => ({
  default: class {
    static isSupported() {
      return true;
    }
    static Events = { MANIFEST_PARSED: 'parsed', ERROR: 'error' };
    levels = [360, 480, 720, 1080].map((height) => ({ height }));
    attachMedia = hls.attach;
    loadSource = hls.load;
    stopLoad = hls.stop;
    destroy = hls.destroy;
    set loadLevel(value: number) {
      hls.selected = value;
    }
    on(event: string, handler: () => void) {
      if (event === 'parsed') hls.parsed = handler;
    }
  },
}));
vi.mock('@/src/shared/api/http', () => ({
  request: vi.fn(() => Promise.resolve({ items: [], nextCursor: null })),
}));
const vod = schema.$defs.P2Vod.examples[0] as P2Vod;
const firstTrack = {
  ...(schema.$defs.P2SubtitleTrack.examples[0] as P2SubtitleTrack),
  vodId: vod.vodId,
  mappingVersion: vod.mappingVersion,
  trackId: 'trk_first',
  label: 'Español',
};
const secondTrack = { ...firstTrack, trackId: 'trk_second', label: 'English', language: 'en' };
const pause = vi.fn();
beforeEach(() => {
  vi.mocked(request).mockReset();
  vi.mocked(request).mockResolvedValue({ items: [], nextCursor: null });
  vi.useFakeTimers();
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(pause);
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response('#EXTM3U', { headers: { 'X-VOD-Playback-Grant': 'fixture-permission' } }),
      ),
    ),
  );
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  hls.parsed = undefined;
  vi.clearAllMocks();
});
it('renueva antes de 60 segundos sin reiniciar HLS ni perder seek o calidad', async () => {
  const position = vi.fn();
  render(<VodPlayer vod={vod} onPosition={position} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10);
  });
  expect(hls.attach).toHaveBeenCalledOnce();
  act(() => hls.parsed?.());
  const video = screen.getByLabelText<HTMLVideoElement>(vod.title);
  video.currentTime = 0.75;
  fireEvent.timeUpdate(video);
  expect(position).toHaveBeenCalledWith(750, 'fixture-permission');
  fireEvent.change(screen.getByRole('combobox', { name: 'Calidad' }), { target: { value: '3' } });
  expect(hls.selected).toBe(3);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(90000);
  });
  expect(fetch).toHaveBeenCalledTimes(3);
  expect(hls.attach).toHaveBeenCalledOnce();
  expect(hls.load).toHaveBeenCalledOnce();
  expect(video.currentTime).toBe(0.75);
  expect(hls.stop).not.toHaveBeenCalled();
});
it('interrumpe reproducción si no puede renovar el permiso', async () => {
  render(<VodPlayer vod={vod} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10);
  });
  vi.mocked(fetch).mockResolvedValueOnce(new Response('', { status: 503 }));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(45000);
  });
  expect(hls.stop).toHaveBeenCalledOnce();
  expect(pause).toHaveBeenCalledOnce();
  expect(screen.getByText('No se pudo renovar el acceso al video.')).toBeInTheDocument();
});
it('permite consultar las siguientes páginas de pistas sin reiniciar el video', async () => {
  const original = schema.$defs.P2SubtitleTrack.examples[0];
  const first = {
    ...original,
    mappingVersion: vod.mappingVersion,
    label: 'Español',
    trackId: 'trk_first',
  };
  const second = { ...first, label: 'English', language: 'en', trackId: 'trk_second' };
  vi.mocked(request)
    .mockResolvedValueOnce({ items: [first], nextCursor: 'fixture-cursor' })
    .mockRejectedValueOnce(new Error('fixture-unavailable'))
    .mockResolvedValueOnce({ items: [second], nextCursor: null });
  render(<VodPlayer vod={vod} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10);
  });
  expect(screen.getByRole('option', { name: 'Español · es' })).toBeInTheDocument();
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más pistas' }));
    await vi.advanceTimersByTimeAsync(1);
  });
  expect(screen.getByText('No se pudieron consultar los subtítulos.')).toBeInTheDocument();
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más pistas' }));
    await vi.advanceTimersByTimeAsync(1);
  });
  expect(screen.getByRole('option', { name: 'English · en' })).toBeInTheDocument();
  expect(screen.getByRole('option', { name: 'Español · es' })).toBeInTheDocument();
  expect(vi.mocked(request).mock.calls.at(-1)?.[0]).toBe(
    `/api/vods/${vod.vodId}/subtitles?cursor=fixture-cursor`,
  );
  expect(hls.attach).toHaveBeenCalledOnce();
  expect(screen.queryByRole('button', { name: 'Cargar más pistas' })).not.toBeInTheDocument();
});

it('selecciona, cambia y desactiva pistas sin perder posición, calidad o foco', async () => {
  vi.mocked(request).mockResolvedValue({ items: [firstTrack, secondTrack], nextCursor: null });
  render(<VodPlayer vod={vod} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10);
  });
  act(() => hls.parsed?.());
  const video = screen.getByLabelText<HTMLVideoElement>(vod.title);
  const textTracks = [firstTrack, secondTrack].map((item) => ({
    id: item.trackId,
    mode: 'disabled',
  }));
  Object.defineProperty(video, 'textTracks', { value: textTracks });
  const selector = screen.getByRole('combobox', { name: 'Subtítulos' });
  selector.focus();
  video.currentTime = 42;
  fireEvent.change(selector, { target: { value: firstTrack.trackId } });
  expect(textTracks.map((item) => item.mode)).toEqual(['showing', 'disabled']);
  fireEvent.change(screen.getByRole('combobox', { name: 'Calidad' }), { target: { value: '1' } });
  fireEvent.change(selector, { target: { value: secondTrack.trackId } });
  expect(textTracks.map((item) => item.mode)).toEqual(['disabled', 'showing']);
  fireEvent.timeUpdate(video);
  fireEvent.loadedData(video);
  expect(selector).toHaveValue(secondTrack.trackId);
  fireEvent.change(selector, { target: { value: '' } });
  expect(textTracks.map((item) => item.mode)).toEqual(['disabled', 'disabled']);
  expect(selector).toHaveFocus();
  expect(video.currentTime).toBe(42);
  expect(hls.selected).toBe(1);
  expect(hls.attach).toHaveBeenCalledOnce();
  expect(hls.load).toHaveBeenCalledOnce();
});

it('reintenta la página fallida aunque haya una pista seleccionada', async () => {
  vi.mocked(request)
    .mockResolvedValueOnce({ items: [firstTrack], nextCursor: 'next-page' })
    .mockRejectedValueOnce(new Error('fixture-unavailable'))
    .mockResolvedValueOnce({ items: [secondTrack], nextCursor: null });
  const { container } = render(<VodPlayer vod={vod} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10);
  });
  const selector = screen.getByRole('combobox', { name: 'Subtítulos' });
  fireEvent.change(selector, { target: { value: firstTrack.trackId } });
  const first = container.querySelector(`#${firstTrack.trackId}`);
  const video = screen.getByLabelText<HTMLVideoElement>(vod.title);
  video.currentTime = 42;
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más pistas' }));
    await vi.advanceTimersByTimeAsync(1);
  });
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar listado de subtítulos' }));
    await vi.advanceTimersByTimeAsync(1);
  });
  expect(request).toHaveBeenCalledTimes(3);
  expect(vi.mocked(request).mock.calls.at(-1)?.[0]).toBe(
    `/api/vods/${vod.vodId}/subtitles?cursor=next-page`,
  );
  expect(screen.getByRole('option', { name: 'English · en' })).toBeInTheDocument();
  expect(selector).toHaveValue(firstTrack.trackId);
  expect(container.querySelector(`#${firstTrack.trackId}`)).toBe(first);
  expect(video.currentTime).toBe(42);
  expect(hls.attach).toHaveBeenCalledOnce();
  expect(hls.load).toHaveBeenCalledOnce();
  expect(screen.queryByText('No se pudieron consultar los subtítulos.')).not.toBeInTheDocument();
});

it('permite reintentar una pista fallida al volver a seleccionarla', async () => {
  vi.mocked(request).mockResolvedValue({ items: [firstTrack, secondTrack], nextCursor: null });
  const { container } = render(<VodPlayer vod={vod} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10);
  });
  const selector = screen.getByRole('combobox', { name: 'Subtítulos' });
  fireEvent.change(selector, { target: { value: firstTrack.trackId } });
  const failed = container.querySelector(`#${firstTrack.trackId}`)!;
  fireEvent.error(failed);
  fireEvent.change(selector, { target: { value: secondTrack.trackId } });
  expect(screen.queryByText(/No se pudo cargar la pista/)).not.toBeInTheDocument();
  // A failed native track does not dispatch another error just because its mode changes.
  fireEvent.change(selector, { target: { value: firstTrack.trackId } });
  expect(screen.getByText(/No se pudo cargar la pista/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar subtítulos' }));
  const replacement = container.querySelector(`#${firstTrack.trackId}`)!;
  expect(replacement).not.toBe(failed);
  fireEvent.load(replacement);
  expect(selector).toHaveValue(firstTrack.trackId);
  expect(screen.queryByText(/No se pudo cargar la pista/)).not.toBeInTheDocument();
  expect(hls.attach).toHaveBeenCalledOnce();
  expect(hls.load).toHaveBeenCalledOnce();
  expect(hls.stop).not.toHaveBeenCalled();
});

it('ignora eventos tardíos de una pista deseleccionada y permite reintentar sin cortar HLS', async () => {
  vi.mocked(request).mockResolvedValue({ items: [firstTrack, secondTrack], nextCursor: null });
  const { container } = render(<VodPlayer vod={vod} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10);
  });
  const selector = screen.getByRole('combobox', { name: 'Subtítulos' });
  fireEvent.change(selector, { target: { value: firstTrack.trackId } });
  const first = container.querySelector(`#${firstTrack.trackId}`)!;
  fireEvent.change(selector, { target: { value: secondTrack.trackId } });
  fireEvent.error(first);
  fireEvent.load(first);
  expect(selector).toHaveValue(secondTrack.trackId);
  expect(screen.queryByText(/No se pudo cargar la pista/)).not.toBeInTheDocument();
  const failed = container.querySelector(`#${secondTrack.trackId}`)!;
  fireEvent.error(failed);
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar subtítulos' }));
  expect(container.querySelector(`#${secondTrack.trackId}`)).not.toBe(failed);
  expect(selector).toHaveValue(secondTrack.trackId);
  expect(hls.attach).toHaveBeenCalledOnce();
  expect(hls.load).toHaveBeenCalledOnce();
  expect(hls.stop).not.toHaveBeenCalled();
  expect(pause).not.toHaveBeenCalled();
  fireEvent.change(selector, { target: { value: '' } });
  fireEvent.error(container.querySelector(`#${secondTrack.trackId}`)!);
  expect(screen.queryByText(/No se pudo cargar la pista/)).not.toBeInTheDocument();
});

it.each(['vod', 'mapping'])(
  'descarta selección y respuestas tardías al cambiar %s',
  async (change) => {
    let oldResponse: (page: P2SubtitlePage) => void = () => {};
    vi.mocked(request)
      .mockResolvedValueOnce({ items: [firstTrack], nextCursor: 'old-cursor' })
      .mockImplementationOnce(
        () =>
          new Promise<P2SubtitlePage>((resolve) => {
            oldResponse = resolve;
          }),
      )
      .mockResolvedValueOnce({ items: [], nextCursor: null });
    const { rerender } = render(<VodPlayer vod={vod} />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10);
    });
    fireEvent.change(screen.getByRole('combobox', { name: 'Subtítulos' }), {
      target: { value: firstTrack.trackId },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Cargar más pistas' }));
    const next =
      change === 'vod'
        ? { ...vod, vodId: 'vod_next', playbackPath: '/vod-media/vod_next/master.m3u8' }
        : { ...vod, mappingVersion: vod.mappingVersion + 1 };
    rerender(<VodPlayer vod={next} />);
    await act(async () => {
      oldResponse({ items: [secondTrack], nextCursor: null });
      await vi.advanceTimersByTimeAsync(10);
    });
    expect(screen.getByRole('combobox', { name: 'Subtítulos' })).toHaveValue('');
    expect(screen.queryByRole('option', { name: 'Español · es' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'English · en' })).not.toBeInTheDocument();
    expect(hls.destroy).toHaveBeenCalledOnce();
    expect(hls.attach).toHaveBeenCalledTimes(2);
  },
);
