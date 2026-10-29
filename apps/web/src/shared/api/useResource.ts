import { useEffect, useRef, useState } from 'react';

import { errorMessage } from './http';

export interface Resource<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
}
// A key change hides the old result immediately; cleanup prevents late writes across routes/users.
/**
 * `initial` is data already rendered by the server for this key: the hook starts with it and only
 * revalidates after `interval`, so hydration does not repeat the first request.
 */
export function useResource<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
  interval = 0,
  initial?: T,
): Resource<T> {
  const loader = useRef(load);
  useEffect(() => {
    loader.current = load;
  });
  const [state, setState] = useState<Resource<T> & { key: string }>(
    initial === undefined
      ? { key: '', data: null, error: null, loading: true }
      : { key, data: initial, error: null, loading: false },
  );
  const seeded = useRef(initial === undefined ? null : key);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function read() {
      try {
        const data = await loader.current(controller.signal);
        if (!controller.signal.aborted) setState({ key, data, error: null, loading: false });
      } catch (error) {
        if (!controller.signal.aborted)
          setState({ key, data: null, error: errorMessage(error), loading: false });
      } finally {
        if (interval && !controller.signal.aborted)
          timer = setTimeout(() => {
            void read();
          }, interval);
      }
    }
    if (seeded.current === key) {
      if (interval)
        timer = setTimeout(() => {
          void read();
        }, interval);
    } else {
      seeded.current = null;
      void read();
    }
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [key, interval]);
  return state.key === key ? state : { data: null, error: null, loading: true };
}
