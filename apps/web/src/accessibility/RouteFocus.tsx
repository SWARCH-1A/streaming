import { useEffect } from 'react';
import { useLocation } from 'react-router';

export function RouteFocus() {
  const { pathname, hash } = useLocation();
  useEffect(() => {
    const main = document.getElementById('main-content');
    let focused = false;
    main?.focus({ preventScroll: true });
    if (!hash) window.scrollTo({ top: 0 });
    function update() {
      const heading = main?.querySelector<HTMLElement>('h1');
      const anchor = hash ? document.getElementById(hash.slice(1)) : null;
      const target = anchor ?? heading;
      if (target && !focused) {
        // Slow network responses must not steal focus after the user starts interacting.
        if (document.activeElement === main || document.activeElement === document.body) {
          target.setAttribute('tabindex', '-1');
          target.focus({ preventScroll: true });
          if (anchor) anchor.scrollIntoView({ block: 'start' });
        }
        focused = true;
      }
      document.title = `${heading?.textContent ?? 'Cargando'} · STREAMING`;
    }
    update();
    const observer = new MutationObserver(update);
    if (main) observer.observe(main, { childList: true, subtree: true, characterData: true });
    return () => observer.disconnect();
  }, [pathname, hash]);
  return null;
}
