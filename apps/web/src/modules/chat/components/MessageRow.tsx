import { Badge } from '@/src/components/atoms/Badge';
import type { ChatMessage } from '@/src/modules/chat/chat.types';

import styles from './MessageRow.module.css';

export function MessageRow({ message }: { message: ChatMessage }) {
  return (
    <div className={styles.message}>
      <time>{message.time}</time>
      <div>
        <strong className={message.role === 'broadcaster' ? styles.broadcaster : styles.viewer}>
          {message.role === 'broadcaster' ? <Badge tone="live">EMISOR</Badge> : null}
          {message.author}
        </strong>
        <span>{message.text}</span>
      </div>
    </div>
  );
}
