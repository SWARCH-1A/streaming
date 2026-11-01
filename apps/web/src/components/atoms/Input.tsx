import type { ComponentPropsWithRef } from 'react';

import styles from './Input.module.css';

export function Input({ className = '', ...props }: ComponentPropsWithRef<'input'>) {
  return <input className={`${styles.control} ${className}`} {...props} />;
}
