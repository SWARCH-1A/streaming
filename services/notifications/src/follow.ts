import { HandlerError, type IncomingMessage } from './broker.ts';
import { type Db, transaction } from './db.ts';
import { ID, sha256 } from './ids.ts';
import { inboxState, remember } from './live.ts';

const CONSUMER = 'notifications.follow';

/**
 * Local projection of channel.follow.changed.v1. A version not higher than the stored one is ignored,
 * so a late "follow" cannot revive a cancelled relation. Unfollowing turns off that channel's email
 * and push switches. Core stays the authority: recipients always come from its snapshot.
 */
export function followHandler(db: Db) {
  return async (message: IncomingMessage): Promise<string> => {
    if (message.contentType !== 'application/json') throw new HandlerError('INVALID_EVENT', true);
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(message.body) as Record<string, unknown>;
    } catch {
      throw new HandlerError('INVALID_EVENT', true);
    }
    const p = event.payload as Record<string, unknown> | undefined;
    const valid = event.eventType === 'channel.follow.changed.v1' && event.producer === 'core' && typeof event.eventId === 'string' &&
      p !== undefined && typeof p.channelId === 'string' && ID.test(p.channelId) && typeof p.followerUserId === 'string' &&
      ID.test(p.followerUserId) && typeof p.following === 'boolean' && Number.isSafeInteger(p.followVersion) &&
      (p.followVersion as number) >= 1 && event.sequence === p.followVersion &&
      event.aggregateId === `follow:${p.channelId}:${p.followerUserId}`;
    if (!valid) throw new HandlerError('INVALID_EVENT', true);
    const fingerprint = sha256(message.body);
    const seen = await inboxState(db, CONSUMER, event.eventId as string, fingerprint);
    if (seen === 'DUPLICATE') return 'DUPLICATE';
    if (seen === 'CONFLICT') throw new HandlerError('EVENT_ID_CONFLICT', true);
    return transaction(db, async (tx) => {
      const applied = await tx.query(
        `INSERT INTO follow_projection (user_id, channel_id, following, follow_version) VALUES ($1,$2,$3,$4)
         ON CONFLICT (user_id, channel_id) DO UPDATE SET following=EXCLUDED.following, follow_version=EXCLUDED.follow_version
         WHERE follow_projection.follow_version < EXCLUDED.follow_version`,
        [p.followerUserId, p.channelId, p.following, p.followVersion]);
      const outcome = applied.rowCount ? 'APPLIED' : 'STALE';
      if (applied.rowCount && !p.following) {
        await tx.query('DELETE FROM channel_preferences WHERE user_id=$1 AND channel_id=$2', [p.followerUserId, p.channelId]);
      }
      await remember(tx, CONSUMER, event.eventId as string, fingerprint, outcome);
      return outcome;
    });
  };
}
