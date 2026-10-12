import artwork from '@/public/images/signal-grid.svg';

import styles from './BroadcastPlaceholder.module.css';

export function BroadcastPlaceholder({
  title,
  description,
  image,
}: {
  title: string;
  description?: string;
  image?: string | null | undefined;
}) {
  return (
    <div className={styles.placeholder}>
      <img src={image || artwork} alt="" width="1280" height="720" />
      <div className={styles.caption}>
        <span className={styles.marker} aria-hidden="true" />
        <h2>{title}</h2>
        {description ? <p>{description}</p> : null}
      </div>
    </div>
  );
}
