import { useEffect, useRef, useState } from 'react';

/** Focus only after a failed submission, once disabled fields become operable again. */
export function useErrorFocus(pending = false) {
  const [request, setRequest] = useState<{ id: string } | null>(null);
  const handled = useRef<typeof request>(null);
  useEffect(() => {
    if (!pending && request && request !== handled.current) {
      handled.current = request;
      document.getElementById(request.id)?.focus();
    }
  }, [pending, request]);
  return (id: string) => setRequest({ id });
}
