import { HandlerError } from './broker.ts';
import type { Db, Tx } from './db.ts';

/**
 * Session ownership spans durable autocommitted phases and external calls. A transaction lock would
 * roll back SENDING after a crash and permit duplicate SMTP. All queries use this one connection;
 * duplicates retry through the broker without waiting while occupying the rest of the pool.
 */
export async function withDeliveryLock<T>(db: Db, deliveryId: string, work: (client: Tx) => Promise<T>): Promise<T> {
  const client = await db.connect();
  const key = `notifications.delivery|${deliveryId}`;
  let locked = false;
  let discard = false;
  try {
    locked = (await client.query<{ locked: boolean }>(
      'SELECT pg_try_advisory_lock(hashtextextended($1, 0)) AS locked', [key])).rows[0]!.locked;
    if (!locked) throw new HandlerError('DELIVERY_BUSY', false);
    return await work(client);
  } finally {
    if (locked) {
      try {
        await client.query('SELECT pg_advisory_unlock(hashtextextended($1, 0))', [key]);
      } catch {
        discard = true; // Never return a possibly locked session to the pool.
      }
    }
    client.release(discard);
  }
}
