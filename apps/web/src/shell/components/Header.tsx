import { NavLink } from 'react-router';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Avatar } from '@/src/components/atoms/Avatar';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { useSession } from '@/src/shell/session/useSession';

import { Brand } from './Brand';
import styles from './Header.module.css';
import { SearchForm } from './SearchForm';

export function Header({ onMenu }: { onMenu: () => void }) {
  const { user, signOut } = useSession();
  return (
    <header className={styles.header}>
      <div className={styles.logo}>
        <Button
          variant="ghost"
          size="icon"
          className={styles.menu}
          aria-label="Abrir navegación"
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
          { to: '/studio', label: 'Estudio' },
          { to: '/design-system/components', label: 'Componentes' },
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
        <ActionLink to="/studio" variant="danger" size="sm" aria-label="Transmitir">
          <Icon name="broadcast" size={16} />
          <span className={styles.transmit}>Transmitir</span>
        </ActionLink>
        {user ? (
          <>
            <ActionLink to="/profile" variant="ghost" size="sm">
              <Avatar name={user.displayName} size="sm" />
              <span className={styles.userName}>{user.displayName}</span>
            </ActionLink>
            <Button variant="ghost" size="sm" onClick={signOut}>
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
