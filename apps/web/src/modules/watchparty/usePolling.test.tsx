import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { HttpError } from '@/src/shared/api/http';

import { usePolling } from './usePolling';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const gone = new HttpError(404, { code: 'WATCH_PARTY_NOT_FOUND', message: 'x', requestId: 'r' });
const down = new HttpError(503, { code: 'CORE_UNAVAILABLE', message: 'x', requestId: 'r' });

async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

it('conserva el último dato ante un fallo transitorio y lo vacía ante un 404', async () => {
  const load = vi
    .fn<(signal: AbortSignal) => Promise<string>>()
    .mockResolvedValueOnce('uno')
    .mockRejectedValueOnce(down)
    .mockResolvedValueOnce('dos')
    .mockRejectedValueOnce(gone);
  const { result } = renderHook(() => usePolling('k', load, 5000));
  await tick(0);
  expect(result.current).toMatchObject({ data: 'uno', error: null, loading: false });
  await tick(5000);
  expect(result.current.data).toBe('uno');
  expect(result.current.error).toContain('no está disponible');
  await tick(5000);
  expect(result.current).toMatchObject({ data: 'dos', error: null });
  await tick(5000);
  expect(result.current.data).toBeNull();
  expect(result.current.error).toContain('No encontramos esa sala');
});

it('se detiene al llegar a un estado terminal y al desmontar', async () => {
  const load = vi.fn(() => Promise.resolve({ closed: true }));
  const { unmount } = renderHook(() => usePolling('k', load, 5000, true, (value) => value.closed));
  await tick(20000);
  expect(load).toHaveBeenCalledTimes(1);
  const live = vi.fn(() => Promise.resolve('x'));
  const view = renderHook(() => usePolling('k2', live, 5000));
  await tick(0);
  view.unmount();
  const calls = live.mock.calls.length;
  await tick(20000);
  expect(live).toHaveBeenCalledTimes(calls);
  unmount();
});

it('replace aplica la respuesta de un comando sin esperar al siguiente sondeo', async () => {
  const load = vi.fn(() => Promise.resolve('viejo'));
  const { result } = renderHook(() => usePolling('k', load, 5000));
  await tick(0);
  act(() => result.current.replace('nuevo'));
  expect(result.current.data).toBe('nuevo');
});

it('descarta un sondeo que ya estaba en vuelo cuando un comando aplicó su respuesta', async () => {
  let release: (value: string) => void = () => undefined;
  const load = vi
    .fn<(signal: AbortSignal) => Promise<string>>()
    .mockResolvedValueOnce('inicial')
    .mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          release = resolve;
        }),
    )
    .mockResolvedValue('despues');
  const { result } = renderHook(() => usePolling('k', load, 5000));
  await tick(0);
  await tick(5000); // el sondeo siguiente queda en vuelo
  act(() => result.current.replace('del comando'));
  await act(async () => {
    release('viejo'); // llega tarde y describe un estado anterior al comando
    await Promise.resolve();
  });
  expect(result.current.data).toBe('del comando');
  await tick(5000);
  expect(result.current.data).toBe('despues');
});

it('un fallo de un sondeo superado por un comando no marca error ni borra la sala', async () => {
  let fail: (reason: Error) => void = () => undefined;
  const load = vi
    .fn<(signal: AbortSignal) => Promise<string>>()
    .mockResolvedValueOnce('inicial')
    .mockImplementationOnce(
      () =>
        new Promise<string>((_resolve, reject) => {
          fail = reject;
        }),
    );
  const { result } = renderHook(() => usePolling('k', load, 5000));
  await tick(0);
  await tick(5000);
  act(() => result.current.replace('del comando'));
  await act(async () => {
    fail(gone);
    await Promise.resolve();
  });
  expect(result.current).toMatchObject({ data: 'del comando', error: null });
});

it('con datos iniciales del servidor los muestra al instante y espera un intervalo para el primer sondeo', async () => {
  const load = vi.fn(() => Promise.resolve('fresco'));
  const { result } = renderHook(() => usePolling('k', load, 5000, true, undefined, 'del servidor'));
  expect(result.current).toMatchObject({ data: 'del servidor', loading: false, error: null });
  await tick(4999);
  expect(load).not.toHaveBeenCalled();
  await tick(1);
  expect(result.current.data).toBe('fresco');
  expect(load).toHaveBeenCalledTimes(1);
});
