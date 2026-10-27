import { HandlerError, type IncomingMessage } from './broker.ts';
import { CoreError, type CorePort } from './core.ts';
import type { Db } from './db.ts';
import { ID } from './ids.ts';
import type { Mailer, SendOutcome } from './mail.ts';
import { DELIVERY_EVENT } from './live.ts';

export type Medium = 'INBOX' | 'EMAIL' | 'PUSH';

export interface PushProvider {
  /** INVALID_TOKEN disables the installation; the other outcomes follow the SMTP classification. */
  send(token: string, title: string, linkPath: string): Promise<SendOutcome | 'INVALID_TOKEN'>;
}

/** P2-13 pending: without a provisioned provider a push ends as an observable permanent failure. */
export const unconfiguredPush: PushProvider = { send: async () => 'PERMANENT_FAILURE' };

interface Job {
  deliveryId: string;
  notificationId: string;
  medium: Medium;
  targetId: string;
}

interface Claimed {
  delivery_id: string;
  target_id: string;
  user_id: string;
  title: string;
  link_path: string;
}

function parseJob(message: IncomingMessage, medium: Medium): Job {
  if (message.contentType !== 'application/json') throw new HandlerError('INVALID_EVENT', true);
  let event: Record<string, unknown>;
  try {
    event = JSON.parse(message.body) as Record<string, unknown>;
  } catch {
    throw new HandlerError('INVALID_EVENT', true);
  }
  const p = event.payload as Record<string, unknown> | undefined;
  const ok = event.eventType === DELIVERY_EVENT && event.producer === 'notifications' && p !== undefined &&
    p.medium === medium && typeof p.deliveryId === 'string' && ID.test(p.deliveryId) &&
    typeof p.notificationId === 'string' && ID.test(p.notificationId) && typeof p.targetId === 'string' && ID.test(p.targetId) &&
    event.aggregateId === `delivery:${p.deliveryId}`;
  if (!ok) throw new HandlerError('INVALID_EVENT', true);
  return { deliveryId: p.deliveryId as string, notificationId: p.notificationId as string, medium, targetId: p.targetId as string };
}

export interface DeliveryDeps {
  db: Db;
  core: CorePort;
  mailer: Mailer;
  push: PushProvider;
  webOrigin: string;
}

/**
 * The job is only a pointer; the deliveries row is the authority. A delivered, permanent or unknown
 * attempt is never resent. Finding IN_PROGRESS means a worker died mid-send: the outcome is UNKNOWN,
 * because SMTP/push cannot tell us whether the message left.
 */
export function deliveryHandler(medium: Medium, deps: DeliveryDeps) {
  return async (message: IncomingMessage): Promise<string> => {
    const job = parseJob(message, medium);
    const claimed = await deps.db.query<Claimed>(
      `UPDATE deliveries d SET state='IN_PROGRESS', attempts=d.attempts+1, updated_at_utc=now()
       FROM notifications n
       WHERE d.delivery_id=$1 AND d.notification_id=$2 AND d.medium=$3 AND d.target_id=$4
         AND n.notification_id=d.notification_id AND d.state IN ('PENDING','RETRYABLE_FAILURE')
       RETURNING d.delivery_id, d.target_id, n.user_id, n.title, n.link_path`,
      [job.deliveryId, job.notificationId, medium, job.targetId]);
    if (!claimed.rowCount) {
      const current = await deps.db.query<{ state: string }>('SELECT state FROM deliveries WHERE delivery_id=$1', [job.deliveryId]);
      if (!current.rowCount) throw new HandlerError('DELIVERY_NOT_FOUND', true);
      if (current.rows[0]!.state === 'IN_PROGRESS') {
        await finish(deps.db, job.deliveryId, 'UNKNOWN', 'INTERRUPTED');
        return 'UNKNOWN';
      }
      return `ALREADY_${current.rows[0]!.state}`;
    }
    const row = claimed.rows[0]!;
    let outcome: SendOutcome;
    let cause: string | null = null;
    if (medium === 'INBOX') {
      outcome = 'DELIVERED';
    } else if (medium === 'EMAIL') {
      ({ outcome, cause } = await sendEmail(deps, row));
    } else {
      ({ outcome, cause } = await sendPush(deps, row));
    }
    await finish(deps.db, job.deliveryId, outcome, cause);
    // Retryable: the broker schedules 1 s / 5 s / 30 s and then keeps the job in the DLQ for redrive.
    if (outcome === 'RETRYABLE_FAILURE') throw new HandlerError(cause ?? 'RETRYABLE_FAILURE', false);
    return outcome;
  };
}

async function sendEmail(deps: DeliveryDeps, row: Claimed): Promise<{ outcome: SendOutcome; cause: string | null }> {
  let address: string;
  try {
    address = await deps.core.emailTarget(row.user_id);
  } catch (error) {
    if (error instanceof CoreError && error.status === 404) return { outcome: 'PERMANENT_FAILURE', cause: 'ACCOUNT_NOT_FOUND' };
    return { outcome: 'RETRYABLE_FAILURE', cause: 'CORE_UNAVAILABLE' };
  }
  const outcome = await deps.mailer.send({
    to: address,
    subject: row.title,
    text: `${row.title}.\n\nMíralo aquí: ${deps.webOrigin}${row.link_path}\n\nRecibes este correo porque activaste los avisos por correo de este canal en Vault tv.`,
  });
  return { outcome, cause: outcome === 'DELIVERED' ? null : `SMTP_${outcome}` };
}

async function sendPush(deps: DeliveryDeps, row: Claimed): Promise<{ outcome: SendOutcome; cause: string | null }> {
  const device = await deps.db.query<{ provider_token: string }>(
    'SELECT provider_token FROM devices WHERE installation_id=$1 AND user_id=$2 AND enabled', [row.target_id, row.user_id]);
  if (!device.rowCount) return { outcome: 'PERMANENT_FAILURE', cause: 'DEVICE_NOT_REGISTERED' };
  const result = await deps.push.send(device.rows[0]!.provider_token, row.title, row.link_path);
  if (result === 'INVALID_TOKEN') {
    await deps.db.query('UPDATE devices SET enabled=false, updated_at_utc=now() WHERE installation_id=$1', [row.target_id]);
    return { outcome: 'PERMANENT_FAILURE', cause: 'INVALID_TOKEN' };
  }
  return { outcome: result, cause: result === 'DELIVERED' ? null : result === 'PERMANENT_FAILURE' && deps.push === unconfiguredPush
    ? 'PUSH_PROVIDER_NOT_CONFIGURED' : `PUSH_${result}` };
}

async function finish(db: Db, deliveryId: string, state: SendOutcome, cause: string | null): Promise<void> {
  await db.query('UPDATE deliveries SET state=$2, last_cause=$3, updated_at_utc=now() WHERE delivery_id=$1', [deliveryId, state, cause]);
}
