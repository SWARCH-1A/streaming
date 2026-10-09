import { useEffect, useRef } from 'react';

// StrictMode replays effects; each setup owns a fresh controller.
export function useLifetime() {
  const controller = useRef(new AbortController());
  useEffect(() => {
    controller.current = new AbortController();
    return () => {
      controller.current.abort();
    };
  }, []);
  return () => controller.current.signal;
}
