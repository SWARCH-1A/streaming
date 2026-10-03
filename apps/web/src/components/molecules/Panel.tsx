import type { HTMLAttributes } from 'react';

import styles from './Panel.module.css';

export function Panel({ className = '', ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={`${styles.panel} ${className}`} {...props} />;
}
