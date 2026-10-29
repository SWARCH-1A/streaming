import { StrictMode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { useResource } from './useResource';

describe('resource lifecycle', () => {
  it('keeps a non-polling SSR resource through hydration and StrictMode replay', async () => {
    const load = vi.fn(() => Promise.resolve('fresh'));
    const { result, rerender, unmount } = renderHook(
      ({ id }) => useResource(id, load, 0, 'from-server'),
      { initialProps: { id: 'channel' }, wrapper: StrictMode },
    );
    expect(result.current.data).toBe('from-server');
    expect(load).not.toHaveBeenCalled();
    rerender({ id: 'other-channel' });
    await waitFor(() => expect(result.current.data).toBe('fresh'));
    expect(load).toHaveBeenCalledOnce();
    unmount();
  });
  it('cancels the old resource and discards its late response', async () => {
    const pending: Record<string, { resolve: (value: string) => void; signal: AbortSignal }> = {};
    const loader = (key: string, signal: AbortSignal) =>
      new Promise<string>((resolve) => {
        pending[key] = { resolve, signal };
      });
    const { result, rerender, unmount } = renderHook(
      ({ id }) => useResource(id, (signal) => loader(id, signal)),
      { initialProps: { id: 'first' } },
    );
    rerender({ id: 'second' });
    expect(result.current.data).toBeNull();
    expect(pending.first?.signal.aborted).toBe(true);
    await act(async () => {
      pending.second?.resolve('second');
      await Promise.resolve();
    });
    await waitFor(() => expect(result.current.data).toBe('second'));
    await act(async () => {
      pending.first?.resolve('first');
      await Promise.resolve();
    });
    expect(result.current.data).toBe('second');
    unmount();
    expect(pending.second?.signal.aborted).toBe(true);
  });
  it('hydrates with server data without repeating the first request', async () => {
    const load = vi.fn(() => Promise.resolve('fresh'));
    const { result, unmount } = renderHook(() => useResource('ssr', load, 20, 'from-server'));
    expect(result.current).toEqual({
      key: 'ssr',
      data: 'from-server',
      error: null,
      loading: false,
    });
    expect(load).not.toHaveBeenCalled();
    // Revalidation keeps running on its interval after hydration.
    await waitFor(() => expect(result.current.data).toBe('fresh'));
    expect(load.mock.calls.length).toBeGreaterThanOrEqual(1);
    unmount();
  });
  it('does not overlap polls when a provider is slow', async () => {
    const load = vi.fn(() => new Promise<string>(() => {}));
    const { unmount } = renderHook(() => useResource('slow', load, 1));
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(load).toHaveBeenCalledTimes(1);
    unmount();
  });
});
