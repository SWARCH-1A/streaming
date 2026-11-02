import { useRef, useState } from 'react';

import { HttpError } from '@/src/shared/api/http';

import { partyError } from './api';

/** A retry after an uncertain failure only makes sense soon: later, the room has probably moved on. */
const RETRY_WINDOW_MS = 60_000;

/** True when Core may have applied the command although the answer was lost (network, timeout or 5xx). */
function uncertain(failure: unknown) {
  return !(failure instanceof HttpError) || failure.status >= 500 || failure.status === 408;
}

/**
 * Runs one command at a time (a second click while one is in flight is ignored). Each distinct action
 * gets one Idempotency-Key. Repeating the same action right after an uncertain failure (lost answer,
 * timeout, 5xx) reuses it, so Core replays its result instead of applying the command twice. The key is
 * dropped after a definitive answer (a 4xx did not apply anything), after one minute, and after any
 * success: once the room changed, an old key must never turn a new, intentional action into a replay.
 */
export function useCommand() {
  const keys = useRef(new Map<string, { key: string; at: number }>());
  const busy = useRef(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function run<T>(action: string, execute: (key: string) => Promise<T>) {
    if (busy.current) return undefined;
    busy.current = true;
    setPending(true);
    setError('');
    const retained = keys.current.get(action);
    const key =
      retained && Date.now() - retained.at < RETRY_WINDOW_MS ? retained.key : crypto.randomUUID();
    try {
      const result = await execute(key);
      keys.current.clear();
      return result;
    } catch (failure) {
      if (uncertain(failure)) keys.current.set(action, { key, at: Date.now() });
      else keys.current.delete(action);
      setError(partyError(failure));
      return undefined;
    } finally {
      busy.current = false;
      setPending(false);
    }
  }
  return { run, pending, error, clearError: () => setError('') };
}
