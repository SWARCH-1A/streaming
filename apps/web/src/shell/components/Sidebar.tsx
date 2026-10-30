import { NavLink } from 'react-router';

import { Badge } from '@/src/components/atoms/Badge';
import { Icon } from '@/src/components/atoms/Icon';

import styles from './Sidebar.module.css';

export function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <aside className={styles.sidebar}>
      <div className={styles.top}>
        <Icon name="broadcast" size={16} />
        <span>Vault tv</span>
      </div>
      <div className={styles.section}>
        <p className="eyebrow">Tu comunidad</p>
        <nav aria-label="Explorar secciones">
          <NavLink to="/" onClick={onNavigate}>
            <Icon name="explore" size={18} />
            Explorar
          </NavLink>
          <NavLink to="/search" onClick={onNavigate}>
            <Icon name="users" size={18} />
            Canales
          </NavLink>
          <NavLink to="/studio" onClick={onNavigate}>
            <Icon name="screen" size={18} />
            Estudio de emisión
          </NavLink>
          <NavLink to="/videos" onClick={onNavigate}>
            Grabaciones
          </NavLink>
          <NavLink to="/studio/vods" onClick={onNavigate}>
            Mis grabaciones
          </NavLink>
          <NavLink to="/notifications" onClick={onNavigate}>
            <Icon name="bell" size={18} />
            Notificaciones
          </NavLink>
          <NavLink to="/admin" onClick={onNavigate}>
            Administración
          </NavLink>
          <NavLink to="/profile" onClick={onNavigate}>
            <Icon name="user" size={18} />
            Mi perfil
          </NavLink>
        </nav>
      </div>
      {import.meta.env.DEV ? (
        <div className={styles.section}>
          <p className="eyebrow">Sistema de diseño</p>
          <nav aria-label="Sistema de diseño">
            <NavLink to="/design-system" end onClick={onNavigate}>
              Índice & fundamentos
            </NavLink>
            <NavLink to="/design-system/components" onClick={onNavigate}>
              Componentes UI
            </NavLink>
            <NavLink to="/prototype" onClick={onNavigate}>
              Prototipo interactivo
            </NavLink>
          </nav>
        </div>
      ) : null}
      <div className={styles.footer}>
        <Badge tone="primary" dot>
          PROTOTIPO 2
        </Badge>
        <p>Descubre, crea y conecta.</p>
      </div>
    </aside>
  );
}
