import { Badge } from '@/src/components/atoms/Badge';
import type { ChatMessage } from '@/src/modules/chat/chat.types';

import styles from './MessageRow.module.css';

export function MessageRow({ message }: { message: ChatMessage }) {
  return (
    <div className={styles.message}>
      <p>
        <strong className={message.role === 'broadcaster' ? styles.broadcaster : styles.viewer}>
          {message.role === 'broadcaster' ? <Badge tone="live">EMISOR</Badge> : null}
          {message.author}
        </strong>{' '}
        {message.text} <time>{message.time}</time>
      </p>
    </div>
  );
}
