import { useCallback, useEffect, useRef, useState } from 'react';

import { isGone, partyError } from './api';

interface Polled<T> {
  key: string;
  data: T | null;
  error: string | null;
  loading: boolean;
}

/**
 * Polls `load` every `interval` ms. Unlike a plain resource it keeps the last good value across a
 * transient failure, so a failed read does not unmount a playing video (and stop its lease). Only a
 * definitive "gone" answer (401/403/404) clears it. `enabled=false` stops the loop, and `until` stops it
 * after a read that reached a terminal state (a closed room never changes again). A read that was already in
 * flight when `replace` applied a command's answer is discarded, so older data never overwrites newer data.
 * `initial` (the room the server already rendered) is shown at once and the first read waits one interval.
 */
export function usePolling<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
  interval: number,
  enabled = true,
  until?: (data: T) => boolean,
  initial?: T,
) {
  const loader = useRef(load);
  const finished = useRef(until);
  useEffect(() => {
    loader.current = load;
    finished.current = until;
  });
  const [state, setState] = useState<Polled<T>>({
    key,
    data: initial ?? null,
    error: null,
    loading: initial === undefined,
  });
  // The server already read this state: the first poll is one interval away, not immediate.
  const skipFirst = useRef(initial !== undefined);
  const [revision, setRevision] = useState(0);
  // Bumped by `replace`: a read started before it describes a state the command already moved past.
  const epoch = useRef(0);
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function read() {
      const started = epoch.current;
      try {
        const data = await loader.current(controller.signal);
        if (controller.signal.aborted) return;
        if (started === epoch.current) {
          setState({ key, data, error: null, loading: false });
          if (finished.current?.(data)) return;
        }
      } catch (error) {
        if (controller.signal.aborted) return;
        if (started !== epoch.current) {
          // Superseded by a command's answer: its failure says nothing about the newer state.
        } else
          setState((previous) => ({
            key,
            data: !isGone(error) && previous.key === key ? previous.data : null,
            error: partyError(error),
            loading: false,
          }));
      }
      if (!controller.signal.aborted)
        timer = setTimeout(() => {
          void read();
        }, interval);
    }
    if (skipFirst.current) {
      skipFirst.current = false;
      timer = setTimeout(() => {
        void read();
      }, interval);
    } else void read();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [key, interval, enabled, revision]);
  const refresh = useCallback(() => setRevision((value) => value + 1), []);
  /** Applies the answer of a command immediately instead of waiting for the next poll. */
  const replace = useCallback(
    (data: T) => {
      epoch.current += 1;
      setState({ key, data, error: null, loading: false });
    },
    [key],
  );
  const current = state.key === key ? state : { key, data: null, error: null, loading: true };
  return { ...current, refresh, replace };
}
