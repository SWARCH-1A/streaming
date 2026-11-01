import type { ChatMessage } from '@contracts/p1';
import type { P2ChatRemovedMessage } from '@contracts/p2';

export type TimelineEntry =
  | { kind: 'message'; sequence: number; message: ChatMessage }
  | { kind: 'removed'; sequence: number; removal: P2ChatRemovedMessage };

/** Añade marcas de retiro. Un retiro es terminal: ningún mensaje posterior repone su texto. */
export function mergeRemovals(
  sessionId: string,
  current: ReadonlyMap<string, P2ChatRemovedMessage>,
  incoming: readonly P2ChatRemovedMessage[],
): Map<string, P2ChatRemovedMessage> {
  const next = new Map(current);
  for (const removal of incoming)
    if (removal.sessionId === sessionId && !next.has(removal.messageId))
      next.set(removal.messageId, removal);
  return next;
}

/** Ordena mensajes vigentes y avisos de retiro por sequence; conserva los 50 más recientes. */
export function timeline(
  messages: readonly ChatMessage[],
  removed: ReadonlyMap<string, P2ChatRemovedMessage>,
): TimelineEntry[] {
  const bySequence = new Map<number, TimelineEntry>();
  for (const message of messages)
    if (!removed.has(message.messageId))
      bySequence.set(message.sequence, { kind: 'message', sequence: message.sequence, message });
  for (const removal of removed.values())
    bySequence.set(removal.sequence, { kind: 'removed', sequence: removal.sequence, removal });
  return [...bySequence.values()].sort((a, b) => a.sequence - b.sequence).slice(-50);
}

export function timelineHasGap(entries: readonly TimelineEntry[]) {
  return entries.some(
    (entry, index) => index > 0 && entry.sequence !== (entries[index - 1]?.sequence ?? 0) + 1,
  );
}
