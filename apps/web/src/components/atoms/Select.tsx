import type { SelectHTMLAttributes } from 'react';

import styles from './Input.module.css';

export function Select({ className = '', ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={`${styles.control} ${className}`} {...props} />;
}
