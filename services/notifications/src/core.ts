// Private Core client. The local CA reaches fetch through NODE_EXTRA_CA_CERTS.

export class CoreError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string) {
    super(`Core ${status} ${code}`);
    this.status = status;
    this.code = code;
  }
  /** Network failures, timeouts and 5xx are transient; other statuses are a definitive answer. */
  get retryable(): boolean {
    return this.status === 0 || this.status >= 500 || this.status === 429;
  }
}

export interface RecipientsPage {
  snapshotId: string;
  items: { userId: string }[];
  nextCursor: string | null;
}

export interface ChannelSummary {
  handle: string | null;
  displayName: string | null;
}

export interface RecoveryDelivery {
  recoveryId: string;
  deliveryId: string;
  attempt: number;
  recipient: string;
  resetUrl: string;
  expiresAtUtc: string;
}

export interface CorePort {
  sessionUser(credential: string): Promise<string | null>;
  recipients(channelId: string, sessionId: string, cursor: string | null): Promise<RecipientsPage>;
  emailTarget(userId: string): Promise<string>;
  channel(channelId: string): Promise<ChannelSummary>;
  claimRecovery(recoveryId: string, deliveryId: string, attempt: number, key: string): Promise<RecoveryDelivery>;
  reportRecovery(recoveryId: string, deliveryId: string, attempt: number, status: string, key: string): Promise<void>;
}

export interface CoreOptions {
  internalUrl: string;
  publicUrl: string;
  serviceToken: string;
  recoveryToken: string | null;
  timeoutMs?: number;
}

export function httpCore(options: CoreOptions): CorePort {
  const timeout = options.timeoutMs ?? 3000;

  async function call(url: string, init: RequestInit): Promise<Response> {
    let response: Response;
    try {
      response = await fetch(url, { ...init, signal: AbortSignal.timeout(timeout), redirect: 'error' });
    } catch {
      // Transport messages can include hosts and headers; keep only the classification.
      throw new CoreError(0, 'CORE_UNREACHABLE');
    }
    if (!response.ok) {
      let code = 'CORE_ERROR';
      try {
        const body = (await response.json()) as { code?: unknown };
        if (typeof body.code === 'string') code = body.code;
      } catch {
        /* non-JSON error */
      }
      throw new CoreError(response.status, code);
    }
    return response;
  }

  function privateHeaders(name: string, token: string, extra: Record<string, string> = {}): Record<string, string> {
    return { 'X-Service-Name': name, 'X-Service-Token': token, Accept: 'application/json', ...extra };
  }
  const notifications = (extra?: Record<string, string>) => privateHeaders('notifications', options.serviceToken, extra);
  function recovery(extra: Record<string, string>) {
    if (!options.recoveryToken) throw new CoreError(503, 'RECOVERY_NOT_CONFIGURED');
    return privateHeaders('recovery-email', options.recoveryToken, extra);
  }
  const json = { 'Content-Type': 'application/json' };

  return {
    async sessionUser(credential) {
      try {
        const response = await call(`${options.internalUrl}/internal/core/notifications/session`, {
          method: 'POST',
          headers: notifications({ 'X-Session-Credential': credential }),
        });
        const body = (await response.json()) as { userId?: unknown };
        return typeof body.userId === 'string' ? body.userId : null;
      } catch (error) {
        if (error instanceof CoreError && error.status === 401) return null;
        throw error;
      }
    },
    async recipients(channelId, sessionId, cursor) {
      const response = await call(`${options.internalUrl}/internal/core/notification-recipients`, {
        method: 'POST',
        headers: notifications(json),
        body: JSON.stringify({ channelId, sessionId, limit: 50, ...(cursor ? { cursor } : {}) }),
      });
      return (await response.json()) as RecipientsPage;
    },
    async emailTarget(userId) {
      const response = await call(`${options.internalUrl}/internal/core/notifications/email-target`, {
        method: 'POST',
        headers: notifications(json),
        body: JSON.stringify({ userId }),
      });
      const body = (await response.json()) as { email?: unknown };
      if (typeof body.email !== 'string') throw new CoreError(502, 'INVALID_EMAIL_TARGET');
      return body.email;
    },
    async channel(channelId) {
      const response = await call(`${options.publicUrl}/api/channels/${encodeURIComponent(channelId)}`, {
        headers: { Accept: 'application/json' },
      });
      const body = (await response.json()) as { handle?: unknown; profile?: { displayName?: unknown } };
      return {
        handle: typeof body.handle === 'string' ? body.handle : null,
        displayName: typeof body.profile?.displayName === 'string' ? body.profile.displayName : null,
      };
    },
    async claimRecovery(recoveryId, deliveryId, attempt, key) {
      const response = await call(
        `${options.internalUrl}/internal/core/recovery-deliveries/${encodeURIComponent(recoveryId)}/claim`,
        {
          method: 'POST',
          headers: recovery({ ...json, 'Idempotency-Key': key }),
          body: JSON.stringify({ deliveryId, attempt }),
        },
      );
      return (await response.json()) as RecoveryDelivery;
    },
    async reportRecovery(recoveryId, deliveryId, attempt, status, key) {
      await call(`${options.internalUrl}/internal/core/recovery-deliveries/${encodeURIComponent(recoveryId)}/result`, {
        method: 'PUT',
        headers: recovery({ ...json, 'Idempotency-Key': key }),
        body: JSON.stringify({ deliveryId, attempt, status }),
      });
    },
  };
}
