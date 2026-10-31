import type { P2Vod } from '@contracts/p2';
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
