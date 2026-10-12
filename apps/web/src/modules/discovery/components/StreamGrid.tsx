import { StreamCard } from '@/src/components/organisms/StreamCard';
import type { StreamCardData } from '@/src/components/organisms/StreamCard.types';

import styles from './StreamGrid.module.css';

export function StreamGrid({ streams }: { streams: readonly StreamCardData[] }) {
  return (
    <div className={styles.grid}>
      {streams.map((stream) => (
        <StreamCard key={stream.id} stream={stream} />
      ))}
    </div>
  );
}
