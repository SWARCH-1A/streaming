import { useEffect } from 'react';
import { useLocation } from 'react-router';

export function RouteFocus() {
  const { pathname, hash } = useLocation();
  useEffect(() => {
    const heading = document.querySelector<HTMLElement>('#main-content h1');
    const anchor = hash ? document.getElementById(hash.slice(1)) : null;
    const target = anchor ?? heading;
    target?.setAttribute('tabindex', '-1');
    target?.focus({ preventScroll: true });
    if (anchor) anchor.scrollIntoView({ block: 'start' });
    else window.scrollTo({ top: 0 });
    document.title = `${heading?.textContent ?? 'Explorar'} · STREAMING`;
  }, [pathname, hash]);
  return null;
}
