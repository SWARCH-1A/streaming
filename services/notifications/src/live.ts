import { randomUUID } from 'node:crypto';

import { HandlerError, type IncomingMessage } from './broker.ts';
import { CoreError, type CorePort } from './core.ts';
import { type Db, transaction, type Tx } from './db.ts';
import { ID, newId, sha256 } from './ids.ts';

export const DELIVERY_EVENT = 'notification.delivery.requested.v1';
const CONSUMER = 'notifications.live';

interface LiveEvent {
  eventId: string;
  sessionId: string;
  channelId: string;
  firstLiveAtUtc: string;
  correlationId: string;
}

/** Validates the envelope and keeps only the first playable transition (initialLive=true). */
export function parseLiveEvent(message: IncomingMessage): LiveEvent {
  if (message.contentType !== 'application/json') throw new HandlerError('INVALID_EVENT', true);
  let event: Record<string, unknown>;
  try {
    event = JSON.parse(message.body) as Record<string, unknown>;
  } catch {
    throw new HandlerError('INVALID_EVENT', true);
  }
  const payload = event.payload as Record<string, unknown> | undefined;
  const valid =
    event.eventType === 'stream.session.changed.v1' && event.producer === 'streaming' && event.schemaVersion === 1 &&
    typeof event.eventId === 'string' && payload !== undefined && payload.initialLive === true &&
    payload.status === 'LIVE' && typeof payload.sessionId === 'string' && ID.test(payload.sessionId) &&
    typeof payload.channelId === 'string' && ID.test(payload.channelId) && typeof payload.firstLiveAtUtc === 'string' &&
    !Number.isNaN(Date.parse(payload.firstLiveAtUtc));
  if (!valid) throw new HandlerError('INVALID_EVENT', true);
  return {
    eventId: event.eventId as string,
    sessionId: payload.sessionId as string,
    channelId: payload.channelId as string,
    firstLiveAtUtc: payload.firstLiveAtUtc as string,
    correlationId: typeof event.correlationId === 'string' ? event.correlationId : (event.eventId as string),
  };
}

/** Returns 'DUPLICATE', 'CONFLICT' or null when the event id is new for this consumer. */
export async function inboxState(db: Db | Tx, consumer: string, eventId: string, fingerprint: string): Promise<string | null> {
  const row = await db.query<{ fingerprint: string }>('SELECT fingerprint FROM inbox_events WHERE consumer=$1 AND event_id=$2',
    [consumer, eventId]);
  if (!row.rowCount) return null;
  return row.rows[0]!.fingerprint === fingerprint ? 'DUPLICATE' : 'CONFLICT';
}

export async function remember(tx: Tx, consumer: string, eventId: string, fingerprint: string, outcome: string): Promise<void> {
  await tx.query('INSERT INTO inbox_events (consumer, event_id, fingerprint, outcome) VALUES ($1,$2,$3,$4) ON CONFLICT DO NOTHING',
    [consumer, eventId, fingerprint, outcome]);
}

/**
 * A session is recorded durably before any recipient is selected. Each Core page is applied in one
 * transaction (notices, deliveries and their jobs) and the cursor advances with it, so a restart
 * resumes; unique keys make a replayed page harmless. A SELECTED session is never selected again.
 */
export function liveHandler(db: Db, core: CorePort) {
  return async (message: IncomingMessage): Promise<string> => {
    const event = parseLiveEvent(message);
    const fingerprint = sha256(message.body);
    const seen = await inboxState(db, CONSUMER, event.eventId, fingerprint);
    if (seen === 'DUPLICATE') return 'DUPLICATE';
    if (seen === 'CONFLICT') throw new HandlerError('EVENT_ID_CONFLICT', true);

    await db.query(
      `INSERT INTO live_sessions (session_id, channel_id, first_live_at_utc, state) VALUES ($1,$2,$3,'SELECTING')
       ON CONFLICT (session_id) DO NOTHING`, [event.sessionId, event.channelId, event.firstLiveAtUtc]);
    const session = (await db.query<{ channel_id: string; state: string; title: string | null; recipients_cursor: string | null }>(
      'SELECT channel_id, state, title, recipients_cursor FROM live_sessions WHERE session_id=$1', [event.sessionId])).rows[0]!;
    if (session.channel_id !== event.channelId) throw new HandlerError('SESSION_CHANNEL_CONFLICT', true);
    if (session.state === 'SELECTED') {
      await transaction(db, (tx) => remember(tx, CONSUMER, event.eventId, fingerprint, 'ALREADY_SELECTED'));
      return 'ALREADY_SELECTED';
    }

    if (session.title === null) await describe(db, core, event);
    let cursor = session.recipients_cursor;
    for (;;) {
      let page;
      try {
        page = await core.recipients(event.channelId, event.sessionId, cursor);
      } catch (error) {
        if (error instanceof CoreError && error.status === 410) {
          cursor = null; // Expired cut: start again; already created notices are kept by their unique keys.
          continue;
        }
        if (error instanceof CoreError && error.status === 404) throw new HandlerError('CHANNEL_NOT_FOUND', true);
        throw new HandlerError('CORE_UNAVAILABLE', false);
      }
      await transaction(db, async (tx) => {
        for (const item of page.items) await notify(tx, event, item.userId);
        await tx.query(
          `UPDATE live_sessions SET recipients_cursor=$2, selected_count=selected_count+$3, updated_at_utc=now()
           WHERE session_id=$1`, [event.sessionId, page.nextCursor, page.items.length]);
      });
      if (!page.nextCursor) break;
      cursor = page.nextCursor;
    }
    await transaction(db, async (tx) => {
      await tx.query("UPDATE live_sessions SET state='SELECTED', updated_at_utc=now() WHERE session_id=$1", [event.sessionId]);
      await remember(tx, CONSUMER, event.eventId, fingerprint, 'SELECTED');
    });
    return 'SELECTED';
  };
}

async function describe(db: Db, core: CorePort, event: LiveEvent): Promise<void> {
  // Best effort: a Core read failure keeps a generic title instead of blocking the notices.
  let title = 'Un canal que sigues está en vivo';
  let link = '/';
  try {
    const channel = await core.channel(event.channelId);
    if (channel.handle) link = `/channels/${encodeURIComponent(channel.handle)}`;
    const name = channel.displayName || channel.handle;
    if (name) title = `${name} está en vivo`;
  } catch {
    /* keep generic text */
  }
  await db.query('UPDATE live_sessions SET title=$2, link_path=$3 WHERE session_id=$1 AND title IS NULL', [event.sessionId, title, link]);
}

/** Creates the notice and, from the preferences read in this same transaction, its deliveries and jobs. */
async function notify(tx: Tx, event: LiveEvent, userId: string): Promise<void> {
  const session = (await tx.query<{ title: string; link_path: string }>(
    'SELECT title, link_path FROM live_sessions WHERE session_id=$1', [event.sessionId])).rows[0]!;
  await tx.query(
    `INSERT INTO notifications (notification_id, kind, user_id, channel_id, session_id, title, link_path, created_at_utc)
     VALUES ($1,'LIVE_STARTED',$2,$3,$4,$5,$6,$7) ON CONFLICT (kind, user_id, session_id) DO NOTHING`,
    [newId('ntf'), userId, event.channelId, event.sessionId, session.title, session.link_path, event.firstLiveAtUtc]);
  const notificationId = (await tx.query<{ notification_id: string }>(
    "SELECT notification_id FROM notifications WHERE kind='LIVE_STARTED' AND user_id=$1 AND session_id=$2",
    [userId, event.sessionId])).rows[0]!.notification_id;

  const prefs = (await tx.query<{ email: boolean; push: boolean }>(
    `SELECT COALESCE(m.email_enabled,false) AND COALESCE(c.email_enabled,false) AS email,
            COALESCE(m.push_enabled,false) AND COALESCE(c.push_enabled,false) AS push
     FROM (SELECT $1::text AS user_id) u
     LEFT JOIN medium_preferences m ON m.user_id=u.user_id
     LEFT JOIN channel_preferences c ON c.user_id=u.user_id AND c.channel_id=$2`, [userId, event.channelId])).rows[0]!;

  const targets: { medium: string; target: string }[] = [{ medium: 'INBOX', target: userId }];
  if (prefs.email) targets.push({ medium: 'EMAIL', target: userId });
  if (prefs.push) {
    const devices = await tx.query<{ installation_id: string }>(
      'SELECT installation_id FROM devices WHERE user_id=$1 AND enabled ORDER BY installation_id', [userId]);
    for (const device of devices.rows) targets.push({ medium: 'PUSH', target: device.installation_id });
  }
  for (const { medium, target } of targets) {
    const inserted = await tx.query<{ delivery_id: string }>(
      `INSERT INTO deliveries (delivery_id, notification_id, medium, target_id, state) VALUES ($1,$2,$3,$4,'PENDING')
       ON CONFLICT (notification_id, medium, target_id) DO NOTHING RETURNING delivery_id`,
      [newId('dlv'), notificationId, medium, target]);
    // Only a newly created delivery gets a job; replaying a page cannot enqueue it twice.
    if (inserted.rowCount) await enqueueDelivery(tx, inserted.rows[0]!.delivery_id, notificationId, medium, target, event.correlationId);
  }
}

export async function enqueueDelivery(tx: Tx, deliveryId: string, notificationId: string, medium: string, targetId: string,
    correlationId: string): Promise<void> {
  const eventId = randomUUID();
  const envelope = {
    eventId,
    eventType: DELIVERY_EVENT,
    schemaVersion: 1,
    producer: 'notifications',
    aggregateId: `delivery:${deliveryId}`,
    sequence: 1,
    occurredAtUtc: new Date().toISOString(),
    correlationId: /^[A-Za-z0-9_-]{1,128}$/.test(correlationId) ? correlationId : eventId,
    payload: { deliveryId, notificationId, medium, targetId },
  };
  await tx.query('INSERT INTO outbox (event_id, routing_key, payload) VALUES ($1,$2,$3)',
    [eventId, `${DELIVERY_EVENT}.${medium.toLowerCase()}`, JSON.stringify(envelope)]);
}
