import type { P2ChatReplayItem } from '@contracts/p2';
import schema from '@contracts/p2/schema.json';
import { describe, expect, it } from 'vitest';

import { REPLAY_WINDOW_MS, visibleAt, windowsFor, windowStart } from './replayWindow';

const sample = schema.$defs.P2ChatReplayItem.examples[0] as P2ChatReplayItem;
const item = (sequence: number, vodOffsetMs: number): P2ChatReplayItem => ({
  ...sample,
  messageId: `msg_${sequence}`,
  sequence,
  vodOffsetMs,
});

describe('replay windows', () => {
  it('aligns windows and prefetches the next one near its end', () => {
    expect(windowStart(0)).toBe(0);
    expect(windowStart(31_000)).toBe(REPLAY_WINDOW_MS);
    expect(windowsFor(10_000)).toEqual([0]);
    expect(windowsFor(26_000)).toEqual([0, REPLAY_WINDOW_MS]);
  });
  it('shows only what already happened at the player position, following seeks both ways', () => {
    const windows = new Map([
      [0, [item(1, 1_000), item(2, 20_000)]],
      [30_000, [item(3, 35_000)]],
    ]);
    expect(visibleAt(windows, 10_000).map((m) => m.sequence)).toEqual([1]);
    expect(visibleAt(windows, 40_000).map((m) => m.sequence)).toEqual([1, 2, 3]);
    // Retroceder oculta lo que todavía no ocurre en el video.
    expect(visibleAt(windows, 5_000).map((m) => m.sequence)).toEqual([1]);
  });
  it('orders by video time then sequence and keeps the latest fifty', () => {
    const many = Array.from({ length: 60 }, (_, index) => item(index + 1, 100));
    expect(visibleAt(new Map([[0, many]]), 1_000)[0]?.sequence).toBe(11);
    expect(
      visibleAt(new Map([[0, [item(5, 300), item(4, 300), item(1, 200)]]]), 1_000).map(
        (m) => m.sequence,
      ),
    ).toEqual([1, 4, 5]);
  });
});
