import type { Publisher } from './broker.ts';
import { type Db, transaction } from './db.ts';
import { DELIVERY_EVENT } from './live.ts';
import type { Log } from './log.ts';

/**
 * Leases pending jobs in SQL and publishes them outside any transaction. A job is marked published only
 * after the broker confirmed it; a crash in between republishes the same eventId, and the delivery row
 * (not the job) decides whether anything is sent again.
 */
export async function drainOutbox(db: Db, publisher: Publisher, log: Log, limit = 50): Promise<number> {
  const leased = await transaction(db, (tx) => tx.query<{ event_id: string; routing_key: string; payload: string; attempts: number }>(
    `WITH pending AS (
       SELECT event_id FROM outbox
       WHERE published_at_utc IS NULL AND next_attempt_at_utc<=now() AND (lease_until_utc IS NULL OR lease_until_utc<=now())
       ORDER BY created_at_utc LIMIT $1 FOR UPDATE SKIP LOCKED)
     UPDATE outbox o SET lease_until_utc=now()+interval '60 seconds', attempts=o.attempts+1
     FROM pending WHERE o.event_id=pending.event_id RETURNING o.event_id, o.routing_key, o.payload, o.attempts`, [limit]));
  let published = 0;
  for (const job of leased.rows) {
    try {
      await publisher.publish(job.routing_key, job.payload, job.event_id, DELIVERY_EVENT);
      await db.query('UPDATE outbox SET published_at_utc=now(), lease_until_utc=NULL WHERE event_id=$1', [job.event_id]);
      published++;
    } catch {
      const delay = [1, 2, 5, 10, 30][Math.min(Math.max(job.attempts - 1, 0), 4)];
      await db.query(`UPDATE outbox SET lease_until_utc=NULL, next_attempt_at_utc=now()+make_interval(secs => $2) WHERE event_id=$1`,
        [job.event_id, delay]);
      log.warn('outbox reintento', { eventId: job.event_id, attempt: job.attempts });
    }
  }
  return published;
}

export function startOutbox(db: Db, publisher: Publisher, log: Log): () => void {
  let running = false;
  const timer = setInterval(() => {
    if (running) return;
    running = true;
    drainOutbox(db, publisher, log).catch(() => log.warn('outbox no disponible', {})).finally(() => (running = false));
  }, 1000);
  return () => clearInterval(timer);
}
