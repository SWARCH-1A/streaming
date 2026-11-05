import { NavLink } from 'react-router';

import { useSession } from '@/src/shell/session/useSession';

import styles from './StudioNavigation.module.css';

function linkClass({ isActive }: { isActive: boolean }) {
  return isActive ? `${styles.link} ${styles.active}` : styles.link;
}

export function StudioNavigation({ channelHandle }: { channelHandle: string }) {
  return (
    <nav className={styles.navigation} aria-label="Herramientas del creador">
      <NavLink to="/studio" end className={linkClass}>
        Estudio de emisión
      </NavLink>
      <NavLink to="/studio/channel" className={linkClass}>
        Mi canal &amp; portada
      </NavLink>
      <NavLink to="/profile" className={linkClass}>
        Mi perfil
      </NavLink>
      <NavLink to={`/channels/${channelHandle}`} className={linkClass}>
        Ver canal
      </NavLink>
      <NavLink to="/studio/vods" end className={linkClass}>
        Grabaciones del canal
      </NavLink>
    </nav>
  );
}

export function StudioNavigationFrame() {
  const { user } = useSession();
  if (!user) return null;

  return (
    <div className={styles.frame}>
      <p className="eyebrow">Herramientas del creador</p>
      <StudioNavigation channelHandle={user.handle} />
    </div>
  );
}
