import schema from '@contracts/p1.schema.json';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { HttpError, request } from './http';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
afterEach(() => vi.unstubAllGlobals());
describe('same-origin HTTP contract', () => {
  it('sends cookies and a fresh Core CSRF before the mutation without storing credentials', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(json(schema.$defs.Csrf.examples[0]))
      .mockResolvedValueOnce(json(schema.$defs.Profile.examples[0]));
    vi.stubGlobal('fetch', fetcher);
    await request('/api/profile/me', {
      method: 'PATCH',
      body: { displayName: 'Prueba' },
      coreMutation: true,
    });
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/identity/csrf');
    const options = fetcher.mock.calls[1]?.[1] as RequestInit;
    expect(options.credentials).toBe('include');
    expect(new Headers(options.headers).get('X-XSRF-TOKEN')).toBe('fictitious-csrf-token');
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
  it('rejects incompatible success payloads and HTML API fallbacks', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(json({ userId: 'missing-profile' }))
        .mockResolvedValueOnce(
          new Response('<html>', { headers: { 'Content-Type': 'text/html' } }),
        ),
    );
    await expect(request('/api/profile/me')).rejects.toThrow('incompatible');
    await expect(request('/api/profile/me')).rejects.toThrow('inesperada');
  });
  it('preserves correlated server errors and never retries mutations automatically', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        json({ code: 'CORE_UNAVAILABLE', message: 'No disponible', requestId: 'req_test' }, 503),
      );
    vi.stubGlobal('fetch', fetcher);
    await expect(
      request('/api/streams/str_test', { method: 'PATCH', body: { title: 'Ejemplo' } }),
    ).rejects.toBeInstanceOf(HttpError);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('passes cancellation to fetch and rejects cross-origin paths', async () => {
    const controller = new AbortController();
    controller.abort();
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: unknown, init: RequestInit) => {
        init.signal?.throwIfAborted();
        return Promise.resolve(json({}));
      }),
    );
    await expect(request('/api/profile/me', { signal: controller.signal })).rejects.toBeDefined();
    await expect(request('https://example.test/api/profile/me')).rejects.toThrow('mismo origen');
  });
  it('does not expire the user session when a viewer lease credential expires', async () => {
    const expired = vi.fn();
    window.addEventListener('session-expired', expired);
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          json({ code: 'LEASE_INVALID', message: 'Lease inválido', requestId: 'req_lease' }, 401),
        ),
    );
    await expect(
      request('/api/streams/viewer-leases/lease_test/heartbeat', { method: 'PUT' }),
    ).rejects.toBeInstanceOf(HttpError);
    expect(expired).not.toHaveBeenCalled();
    window.removeEventListener('session-expired', expired);
  });
});
