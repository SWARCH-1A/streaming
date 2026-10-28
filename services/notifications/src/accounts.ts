import { HandlerError, type IncomingMessage } from './broker.ts';
import { type Db, transaction } from './db.ts';
import { ID, sha256 } from './ids.ts';
import { inboxState, remember } from './live.ts';

const CONSUMER = 'notifications.account';

/**
 * account.status.changed.v1 (SPEC-23). Keeps the newest status per account; an older or equal version
 * never reverts a newer suspension/reactivation. Suspending an account cancels its pending email/push,
 * both as recipient and as the owner of the channel the notice points to. Notices stay as a record;
 * the inbox marks them unavailable while the owner is suspended (SPEC-20 CA-09).
 */
export function accountHandler(db: Db) {
  return async (message: IncomingMessage): Promise<string> => {
    if (message.contentType !== 'application/json') throw new HandlerError('INVALID_EVENT', true);
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(message.body) as Record<string, unknown>;
    } catch {
      throw new HandlerError('INVALID_EVENT', true);
    }
    if (event === null || typeof event !== 'object' || Array.isArray(event)) throw new HandlerError('INVALID_EVENT', true);
    const p = event.payload as Record<string, unknown> | undefined;
    const valid = event.eventType === 'account.status.changed.v1' && event.producer === 'core' && typeof event.eventId === 'string' &&
      p !== undefined && p !== null && typeof p.userId === 'string' && ID.test(p.userId) &&
      (p.status === 'ACTIVE' || p.status === 'SUSPENDED') && Number.isSafeInteger(p.accountVersion) &&
      (p.accountVersion as number) >= 0 && event.sequence === p.accountVersion && event.aggregateId === `account:${p.userId}`;
    if (!valid) throw new HandlerError('INVALID_EVENT', true);
    const fingerprint = sha256(message.body);
    return transaction(db, async (tx) => {
      await tx.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`${CONSUMER}|${p.userId as string}`]);
      const seen = await inboxState(tx, CONSUMER, event.eventId as string, fingerprint);
      if (seen === 'DUPLICATE') return 'DUPLICATE';
      if (seen === 'CONFLICT') throw new HandlerError('EVENT_ID_CONFLICT', true);
      const applied = await tx.query(
        `INSERT INTO account_status (user_id, status, account_version) VALUES ($1,$2,$3)
         ON CONFLICT (user_id) DO UPDATE SET status=EXCLUDED.status, account_version=EXCLUDED.account_version
         WHERE account_status.account_version < EXCLUDED.account_version`,
        [p.userId, p.status, p.accountVersion]);
      const outcome = applied.rowCount ? 'APPLIED' : 'STALE';
      if (applied.rowCount && p.status === 'SUSPENDED') {
        await tx.query(
          `UPDATE deliveries d SET state='PERMANENT_FAILURE', updated_at_utc=now(),
             last_cause=CASE WHEN n.user_id=$1 THEN 'ACCOUNT_SUSPENDED' ELSE 'CHANNEL_UNAVAILABLE' END
           FROM notifications n
           WHERE n.notification_id=d.notification_id AND (n.user_id=$1 OR n.owner_user_id=$1)
             AND d.medium<>'INBOX' AND d.state IN ('PENDING','RETRYABLE_FAILURE')`, [p.userId]);
      }
      await remember(tx, CONSUMER, event.eventId as string, fingerprint, outcome);
      return outcome;
    });
  };
}

/** Cause that blocks an external send, or null. Checked again right before SMTP/push to close the race. */
export async function suspensionCause(db: { query: Db['query'] }, recipient: string, owner: string | null): Promise<string | null> {
  const rows = await db.query<{ user_id: string }>(
    "SELECT user_id FROM account_status WHERE status='SUSPENDED' AND user_id = ANY($1::text[])", [[recipient, owner].filter(Boolean)]);
  if (rows.rows.some((row) => row.user_id === recipient)) return 'ACCOUNT_SUSPENDED';
  return rows.rowCount ? 'CHANNEL_UNAVAILABLE' : null;
}
