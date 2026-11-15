import { Outlet } from 'react-router';

import { StudioNavigationFrame } from '@/src/shell/components/StudioNavigation';

import styles from './creator.module.css';

export default function CreatorLayout() {
  return (
    <div className={styles.layout}>
      <StudioNavigationFrame />
      <Outlet />
    </div>
  );
}
