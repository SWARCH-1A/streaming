import { useRef, useState } from 'react';
import { Outlet, useLocation } from 'react-router';

import { RouteFocus } from '@/src/accessibility/RouteFocus';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Header } from '@/src/shell/components/Header';
import { Sidebar } from '@/src/shell/components/Sidebar';
import { ViewErrorBoundary } from '@/src/shell/components/ViewErrorBoundary';

import styles from './AppLayout.module.css';

export function AppLayout() {
  const dialog = useRef<HTMLDialogElement>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const { pathname } = useLocation();
  return (
    <>
      <a href="#main-content" className={styles.skip}>
        Saltar al contenido
      </a>
      <Header
        menuOpen={menuOpen}
        onMenu={() => {
          dialog.current?.showModal();
          setMenuOpen(true);
        }}
      />
      <div className={styles.sidebar}>
        <Sidebar />
      </div>
      <dialog
        id="navigation-dialog"
        ref={dialog}
        className={styles.drawer}
        aria-labelledby="navigation-title"
        onClose={() => setMenuOpen(false)}
      >
        <div className={styles.drawerHeader}>
          <h2 id="navigation-title">Navegación</h2>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Cerrar navegación"
            onClick={() => dialog.current?.close()}
          >
            <Icon name="close" />
          </Button>
        </div>
        <Sidebar onNavigate={() => dialog.current?.close()} />
      </dialog>
      <main id="main-content" className={styles.main} tabIndex={-1}>
        <ViewErrorBoundary key={pathname} name="Esta vista">
          <Outlet />
          <RouteFocus />
        </ViewErrorBoundary>
      </main>
      <footer className={styles.footer}>
        Vault tv <span>·</span> En directo, contigo.
      </footer>
    </>
  );
}
