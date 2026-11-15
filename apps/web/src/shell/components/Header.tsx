import { useState } from 'react';
import { NavLink } from 'react-router';
import type { P2NotificationPage } from '@contracts/p2';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Avatar } from '@/src/components/atoms/Avatar';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { errorMessage, request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';
import { useSession } from '@/src/shell/session/useSession';

import { Brand } from './Brand';
import styles from './Header.module.css';
import { SearchForm } from './SearchForm';

export function Header({ onMenu, menuOpen = false }: { onMenu: () => void; menuOpen?: boolean }) {
  const [error, setError] = useState('');
  const { user, signOut } = useSession();
  return (
    <header className={styles.header}>
      <div className={styles.logo}>
        <Button
          variant="ghost"
          size="icon"
          className={styles.menu}
          aria-label="Abrir navegación"
          aria-haspopup="dialog"
          aria-controls="navigation-dialog"
          aria-expanded={menuOpen}
          onClick={onMenu}
        >
          <Icon name="menu" />
        </Button>
        <Brand />
      </div>
      <nav className={styles.nav} aria-label="Navegación principal">
        {[
          { to: '/', label: 'Explorar' },
          { to: '/search', label: 'Canales' },
          ...(user ? [{ to: '/watch-parties', label: 'Salas' }] : []),
          { to: '/studio', label: 'Estudio' },
          ...(import.meta.env.DEV
            ? [{ to: '/design-system/components', label: 'Componentes' }]
            : []),
        ].map((link) => (
          <NavLink
            key={link.to}
            to={link.to}
            end={link.to === '/'}
            className={({ isActive }) => (isActive ? styles.active : '')}
          >
            {link.label}
          </NavLink>
        ))}
      </nav>
      <SearchForm />
      <div className={styles.actions}>
        {error ? <span role="alert">{error}</span> : null}
        {user ? <NotificationLink userId={user.userId} /> : null}
        <ActionLink to="/studio" variant="danger" size="sm" aria-label="Transmitir">
          <Icon name="broadcast" size={16} />
          <span className={styles.transmit}>Transmitir</span>
        </ActionLink>
        {user ? (
          <>
            <ActionLink
              to="/profile"
              variant="ghost"
              size="sm"
              aria-label={`Mi perfil, ${user.displayName}`}
            >
              <Avatar
                name={user.displayName}
                {...(user.avatarUri ? { src: user.avatarUri } : {})}
                size="sm"
              />
              <span className={styles.userName}>{user.displayName}</span>
            </ActionLink>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                void signOut().catch((error) => setError(errorMessage(error)));
              }}
            >
              Salir
            </Button>
          </>
        ) : (
          <>
            <ActionLink to="/login" variant="ghost" size="sm" className={styles.login}>
              Iniciar sesión
            </ActionLink>
            <ActionLink to="/register" size="sm">
              Registro
            </ActionLink>
          </>
        )}
      </div>
    </header>
  );
}

function NotificationLink({ userId }: { userId: string }) {
  const inbox = useResource(
    `notification-summary:${userId}`,
    (signal) => request<P2NotificationPage>('/api/notifications?limit=50', { signal }),
    5000,
  );
  const unreadCount =
    inbox.data?.items.filter((notification) => !notification.readAtUtc).length ?? 0;
  const countLabel = unreadCount > 9 ? '9+' : String(unreadCount);

  return (
    <ActionLink
      to="/notifications"
      variant="ghost"
      size="sm"
      className={styles.notifications}
      aria-label={
        unreadCount ? `Notificaciones, ${unreadCount} sin leer` : 'Notificaciones, ninguna sin leer'
      }
    >
      <span className={styles.notificationIcon}>
        <Icon name="bell" size={17} />
        {unreadCount ? <span className={styles.notificationDot} aria-hidden="true" /> : null}
      </span>
      <span className={styles.notificationLabel}>Notificaciones</span>
      {unreadCount ? (
        <span className={styles.notificationCount} aria-hidden="true">
          {countLabel}
        </span>
      ) : null}
    </ActionLink>
  );
}
