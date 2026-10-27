import type { ComponentPropsWithRef } from 'react';

import styles from './Input.module.css';

export function Textarea({ className = '', ...props }: ComponentPropsWithRef<'textarea'>) {
  return <textarea className={`${styles.control} ${styles.textarea} ${className}`} {...props} />;
}
