import type { TextareaHTMLAttributes } from 'react';

import styles from './Input.module.css';

export function Textarea({
  className = '',
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={`${styles.control} ${styles.textarea} ${className}`} {...props} />;
}
