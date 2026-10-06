import { Link, NavLink } from 'react-router';

import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Icon } from '@/src/components/atoms/Icon';
import { demoStreams } from '@/src/modules/discovery/entry';
import { formatViewers } from '@/src/shared/format';

import styles from './Sidebar.module.css';

export function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <aside className={styles.sidebar}>
      <div className={styles.top}>
        <Icon name="broadcast" size={16} />
        <span>CANALES EN VIVO</span>
      </div>
      <nav className={styles.channels} aria-label="Canales destacados">
        {demoStreams.slice(0, 4).map((stream) => (
          <Link key={stream.id} to={`/watch/${stream.id}`} onClick={onNavigate}>
            <Avatar name={stream.channel.name} src={stream.channel.avatar} online size="sm" />
            <div>
              <strong>{stream.channel.name}</strong>
              <span>{stream.category.name}</span>
            </div>
            <b>{formatViewers(stream.viewers)}</b>
          </Link>
        ))}
      </nav>
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
          <NavLink to="/profile" onClick={onNavigate}>
            <Icon name="user" size={18} />
            Mi perfil
          </NavLink>
        </nav>
      </div>
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
      <div className={styles.footer}>
        <Badge tone="primary" dot>
          ENTORNO DE DEMOSTRACIÓN
        </Badge>
        <p>Descubre, crea y conecta.</p>
      </div>
    </aside>
  );
}
