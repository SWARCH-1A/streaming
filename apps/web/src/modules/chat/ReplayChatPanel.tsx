import { useEffect, useRef, useState } from 'react';
import type { P2ReplayMessage } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { Notice } from '@/src/components/molecules/Notice';

import styles from './ChatPanel.module.css';

interface Props {
  messages: readonly P2ReplayMessage[];
  /** Posición actual del video: solo se muestran los mensajes ya alcanzados, como en vivo. */
  positionMs: number;
  pending?: boolean | undefined;
  error?: string | undefined;
  onRetry?: (() => void) | undefined;
  onMore?: (() => void) | undefined;
}

function clock(ms: number) {
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/** Chat de una grabación: mismo panel lateral que el chat en vivo, sin escritura. */
export function ReplayChatPanel({ messages, positionMs, pending, error, onRetry, onMore }: Props) {
  const [autoscroll, setAutoscroll] = useState(true);
  const history = useRef<HTMLDivElement>(null);
  const visible = messages
    .filter((message) => message.mediaOffsetMs <= positionMs)
    .toSorted((a, b) => a.mediaOffsetMs - b.mediaOffsetMs || a.sequence - b.sequence);
  const count = visible.length;
  useEffect(() => {
    if (autoscroll && history.current) history.current.scrollTop = history.current.scrollHeight;
  }, [autoscroll, count]);
  return (
    <aside id="chat" className={styles.chat} aria-label="Chat de la grabación">
      <header className={styles.header}>
        <h2>Chat de la grabación</h2>
        <Button
          variant="ghost"
          aria-pressed={!autoscroll}
          onClick={() => setAutoscroll((previous) => !previous)}
        >
          {autoscroll ? 'Pausar autodesplazamiento' : 'Reanudar autodesplazamiento'}
        </Button>
      </header>
      <div
        ref={history}
        className={styles.history}
        role="log"
        aria-label="Mensajes del chat"
        aria-live={autoscroll ? 'polite' : 'off'}
        tabIndex={0}
        onScroll={(event) => {
          const element = event.currentTarget;
          if (element.scrollHeight - element.scrollTop - element.clientHeight > 40)
            setAutoscroll(false);
        }}
      >
        {visible.map((message) => (
          <div key={message.messageId} className={styles.message}>
            <p>
              <strong>{message.author.displayName}</strong>: {message.text}
            </p>
            <time className={styles.time}>{clock(message.mediaOffsetMs)}</time>
          </div>
        ))}
        {pending ? <p role="status">Cargando chat…</p> : null}
      </div>
      <div className={styles.composer}>
        {error ? (
          <Notice tone="warning">
            {error} El video puede continuar.{' '}
            {onRetry ? <Button onClick={onRetry}>Reintentar chat</Button> : null}
          </Notice>
        ) : null}
        {onMore ? (
          <Button variant="secondary" disabled={pending} onClick={onMore}>
            Cargar más mensajes de este momento
          </Button>
        ) : null}
        <Notice>Repetición del chat: no se pueden enviar mensajes.</Notice>
      </div>
    </aside>
  );
}
