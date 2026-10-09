import type { ChatMessage } from '@contracts/p1';
import schema from '@contracts/p1.schema.json';
import { describe, expect, it } from 'vitest';

import { hasGap, mergeMessages } from './mergeMessages';

const sample = schema.$defs.ChatMessage.examples[0] as ChatMessage;
const message = (sequence: number, sessionId = sample.sessionId) => ({
  ...sample,
  messageId: `msg_${sequence}`,
  sequence,
  sessionId,
});
describe('history and realtime merge', () => {
  it('deduplicates out-of-order frames and ignores another session', () => {
    const result = mergeMessages(
      sample.sessionId,
      [message(2)],
      [message(3), message(1), message(2), message(4, 'ses_other')],
    );
    expect(result.map((item) => item.sequence)).toEqual([1, 2, 3]);
    expect(hasGap(result)).toBe(false);
  });
  it('keeps fifty recent messages, flags holes and rejects a conflicting sequence', () => {
    expect(
      mergeMessages(
        sample.sessionId,
        [],
        Array.from({ length: 60 }, (_, i) => message(i + 1)),
      )[0]?.sequence,
    ).toBe(11);
    expect(hasGap([message(1), message(3)])).toBe(true);
    expect(() =>
      mergeMessages(sample.sessionId, [message(1)], [{ ...message(1), messageId: 'msg_conflict' }]),
    ).toThrow('inconsistente');
  });
});
