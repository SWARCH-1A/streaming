import type { ViewerLease } from '@contracts/p1';

import { HttpError, request } from '@/src/shared/api/http';

// One controller per playing video. Unknown create/close results expire server-side after 30 s.
export class ViewerLeaseController {
  private stopped = false;
  private creating = false;
  private lease: ViewerLease | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private key = crypto.randomUUID();
  constructor(
    private readonly sessionId: string,
    private readonly report: (message: string) => void,
  ) {}
  start() {
    if (!this.stopped && !this.creating && !this.lease) void this.create();
  }
  private async create() {
    this.creating = true;
    try {
      const lease = await request<ViewerLease>(
        `/api/streams/sessions/${this.sessionId}/viewer-leases`,
        { method: 'POST', headers: { 'Idempotency-Key': this.key } },
      );
      if (this.stopped) {
        await this.close(lease);
        return;
      }
      this.lease = lease;
      this.report('');
      this.schedule();
    } catch (error) {
      if (this.stopped) return;
      if (error instanceof HttpError && [404, 409, 410].includes(error.status)) {
        this.report('La sesión ya no acepta espectadores.');
        return;
      }
      this.report('No se pudo actualizar el conteo de espectadores.');
      this.timer = setTimeout(() => {
        void this.create();
      }, 10000);
    } finally {
      this.creating = false;
    }
  }
  private schedule() {
    this.timer = setTimeout(() => {
      void this.heartbeat();
    }, 10000);
  }
  private async heartbeat() {
    const lease = this.lease;
    if (!lease || this.stopped) return;
    try {
      await request(`/api/streams/viewer-leases/${lease.leaseId}/heartbeat`, {
        method: 'PUT',
        headers: { Authorization: `ViewerLease ${lease.leaseToken}` },
      });
      if (!this.stopped) this.report('');
    } catch (error) {
      if (this.stopped) return;
      this.report('No se pudo actualizar el conteo de espectadores.');
      if (error instanceof HttpError && [404, 410].includes(error.status)) {
        this.lease = null;
        this.key = crypto.randomUUID();
        this.start();
        return;
      }
    }
    if (!this.stopped) this.schedule();
  }
  private async close(lease: ViewerLease) {
    try {
      await request(`/api/streams/viewer-leases/${lease.leaseId}`, {
        method: 'DELETE',
        headers: { Authorization: `ViewerLease ${lease.leaseToken}` },
        keepalive: true,
      });
    } catch {
      /* Server expiry bounds an unknown close result. */
    }
  }
  stop() {
    if (this.stopped) return;
    this.stopped = true;
    clearTimeout(this.timer);
    const lease = this.lease;
    this.lease = null;
    if (lease) void this.close(lease);
  }
}
