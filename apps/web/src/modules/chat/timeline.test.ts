import type { ChatMessage } from '@contracts/p1';
import schema from '@contracts/p1.schema.json';
import type { P2ChatRemovedMessage } from '@contracts/p2';
import { describe, expect, it } from 'vitest';

import { mergeRemovals, timeline, timelineHasGap } from './timeline';

const sample = schema.$defs.ChatMessage.examples[0] as ChatMessage;
const message = (sequence: number): ChatMessage => ({
  ...sample,
  messageId: `msg_${sequence}`,
  sequence,
});
const removal = (sequence: number, sessionId = sample.sessionId): P2ChatRemovedMessage => ({
  messageId: `msg_${sequence}`,
  sessionId,
  sequence,
  moderationVersion: 1,
  removedAtUtc: '2026-10-28T20:05:00Z',
});

describe('moderated chat timeline', () => {
  it('replaces a removed message with the moderator notice in its place', () => {
    const removed = mergeRemovals(sample.sessionId, new Map(), [removal(2)]);
    const entries = timeline([message(1), message(2), message(3)], removed);
    expect(entries.map((entry) => entry.kind)).toEqual(['message', 'removed', 'message']);
    expect(timelineHasGap(entries)).toBe(false);
  });
  it('keeps a removal terminal and ignores another session', () => {
    let removed = mergeRemovals(sample.sessionId, new Map(), [removal(2)]);
    removed = mergeRemovals(sample.sessionId, removed, [
      { ...removal(2), moderationVersion: 9 },
      removal(5, 'ses_other'),
    ]);
    expect(removed.size).toBe(1);
    expect(removed.get('msg_2')?.moderationVersion).toBe(1);
    // A message.created replayed after the removal never restores the text.
    expect(timeline([message(2)], removed)[0]?.kind).toBe('removed');
  });
  it('fills sequence gaps with notices so only real holes are flagged', () => {
    const removed = mergeRemovals(sample.sessionId, new Map(), [removal(2)]);
    expect(timelineHasGap(timeline([message(1), message(3)], removed))).toBe(false);
    expect(timelineHasGap(timeline([message(1), message(4)], removed))).toBe(true);
  });
});
