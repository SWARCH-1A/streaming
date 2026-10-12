import styles from './Avatar.module.css';

interface AvatarProps {
  name: string;
  src?: string;
  size?: 'sm' | 'md' | 'lg';
  online?: boolean;
}
export function Avatar({ name, src, size = 'md', online = false }: AvatarProps) {
  const initials = name
    .replaceAll('_', ' ')
    .split(/\s+/)
    .map((part) => part[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <span className={`${styles.avatar} ${styles[size]}`} aria-hidden="true">
      {src ? <img src={src} alt="" loading="lazy" /> : initials}
      {online ? <span className={styles.online} /> : null}
    </span>
  );
}
