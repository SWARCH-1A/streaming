import type { ViewerLease } from '@contracts/p1';
import schema from '@contracts/p1.schema.json';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { request } from '@/src/shared/api/http';

import { ViewerLeaseController } from './ViewerLease';

vi.mock('@/src/shared/api/http', () => ({ request: vi.fn(), HttpError: class extends Error {} }));
const lease = schema.$defs.ViewerLease.examples[0] as ViewerLease;
afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});
describe('viewer lease lifecycle', () => {
  it('does not create before a frame trigger, deduplicates triggers and heartbeats every ten seconds', async () => {
    vi.useFakeTimers();
    vi.mocked(request).mockResolvedValue(lease);
    const controller = new ViewerLeaseController(lease.sessionId, vi.fn());
    expect(request).not.toHaveBeenCalled();
    controller.start();
    controller.start();
    await Promise.resolve();
    expect(request).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(10000);
    expect(vi.mocked(request).mock.calls[1]?.[0]).toContain('/heartbeat');
    controller.stop();
    await Promise.resolve();
    expect(vi.mocked(request).mock.calls[2]?.[1]?.method).toBe('DELETE');
    await vi.advanceTimersByTimeAsync(30000);
    expect(request).toHaveBeenCalledTimes(3);
  });
  it('closes a lease whose creation finishes after route cleanup', async () => {
    let resolve!: (value: ViewerLease) => void;
    vi.mocked(request).mockImplementationOnce(
      () =>
        new Promise<ViewerLease>((done) => {
          resolve = done;
        }),
    );
    vi.mocked(request).mockResolvedValueOnce(undefined);
    const report = vi.fn(),
      controller = new ViewerLeaseController(lease.sessionId, report);
    controller.start();
    controller.stop();
    resolve(lease);
    await Promise.resolve();
    expect(vi.mocked(request).mock.calls[1]?.[1]?.method).toBe('DELETE');
    expect(report).not.toHaveBeenCalled();
  });
});
