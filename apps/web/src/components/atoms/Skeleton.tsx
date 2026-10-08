import styles from './Skeleton.module.css';

export function Skeleton({ variant = 'line' }: { variant?: 'line' | 'media' }) {
  return <span className={`${styles.skeleton} ${styles[variant]}`} aria-hidden="true" />;
}
