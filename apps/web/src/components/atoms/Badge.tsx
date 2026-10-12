import type { ReactNode } from 'react';

import styles from './Badge.module.css';

interface BadgeProps {
  children: ReactNode;
  tone?: 'neutral' | 'primary' | 'live' | 'success' | 'warning' | 'error';
  dot?: boolean;
}
export function Badge({ children, tone = 'neutral', dot = false }: BadgeProps) {
  return (
    <span className={`${styles.badge} ${styles[tone]}`}>
      {dot ? <span className={styles.dot} aria-hidden="true" /> : null}
      {children}
    </span>
  );
}
