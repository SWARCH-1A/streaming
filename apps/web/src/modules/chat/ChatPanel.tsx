import type { FormEvent } from 'react';
import { useEffect, useRef, useState } from 'react';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Textarea } from '@/src/components/atoms/Textarea';
import { Notice } from '@/src/components/molecules/Notice';
import { MessageRow } from '@/src/modules/chat/components/MessageRow';
import { demoMessages } from '@/src/modules/chat/mock/messages';
import { codePointLength } from '@/src/shared/format';
import { useSession } from '@/src/shell/session/useSession';

import type { ChatMessage } from './chat.types';
import styles from './ChatPanel.module.css';
import { validateMessage } from './validateMessage';

interface ChatPanelProps {
  readOnly?: boolean;
  unavailable?: boolean;
}
export function ChatPanel({ readOnly = false, unavailable = false }: ChatPanelProps) {
  const { user } = useSession();
  const [messages, setMessages] = useState<readonly ChatMessage[]>(demoMessages);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [autoscroll, setAutoscroll] = useState(true);
  const history = useRef<HTMLDivElement>(null);
  const lastSentAt = useRef<number | null>(null);
  useEffect(() => {
    if (autoscroll && history.current) history.current.scrollTop = history.current.scrollHeight;
  }, [autoscroll, messages]);
  const disabled = !user || readOnly || unavailable;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled || !user) return;
    const next = validateMessage(text);
    if (next.error) {
      setError(next.error);
      return;
    }
    const now = Date.now();
    if (lastSentAt.current !== null && now - lastSentAt.current < 1000) {
      setError('Espera un segundo antes de enviar otro mensaje.');
      return;
    }
    lastSentAt.current = now;
    const message: ChatMessage = {
      id: crypto.randomUUID(),
      author: user.displayName,
      text: next.text,
      time: new Intl.DateTimeFormat('es', {
        hour: '2-digit',
        minute: '2-digit',
        timeZone: 'America/Bogota',
      }).format(now),
      role: 'viewer',
    };
    setMessages((previous) => [...previous, message].slice(-50));
    setText('');
    setError(null);
  }
  return (
    <aside id="chat" className={styles.chat} aria-label="Chat en vivo">
      <header className={styles.header}>
        <div className="row">
          <Icon name="chat" />
          <h2>Chat en vivo</h2>
        </div>
        <Button
          variant="ghost"
          size="icon"
          aria-label={autoscroll ? 'Pausar autodesplazamiento' : 'Reanudar autodesplazamiento'}
          aria-pressed={!autoscroll}
          onClick={() => setAutoscroll((previous) => !previous)}
        >
          <Icon name={autoscroll ? 'pause' : 'play'} />
        </Button>
      </header>
      <div className={styles.info}>
        <Badge tone="primary">CHAT LOCAL</Badge>
        <span>{autoscroll ? 'Autodesplazamiento activo' : 'Autodesplazamiento pausado'}</span>
      </div>
      <div
        ref={history}
        className={styles.history}
        role="log"
        aria-label="Mensajes del chat"
        aria-live={autoscroll ? 'polite' : 'off'}
        tabIndex={0}
      >
        {messages.map((message) => (
          <MessageRow key={message.id} message={message} />
        ))}
      </div>
      <div className={styles.composer}>
        {unavailable ? (
          <Notice tone="error">
            Chat no disponible. La vista del reproductor continúa funcionando.
          </Notice>
        ) : readOnly ? (
          <Notice>El chat está en modo lectura.</Notice>
        ) : !user ? (
          <ActionLink to="/login" variant="secondary">
            <Icon name="user" />
            Inicia sesión para escribir
          </ActionLink>
        ) : (
          <p className="muted">Escribes como {user.displayName}</p>
        )}
        <form onSubmit={submit}>
          <label htmlFor="chat-message" className="sr-only">
            Mensaje al canal
          </label>
          <Textarea
            id="chat-message"
            placeholder="Comparte un mensaje…"
            rows={2}
            value={text}
            onChange={(event) => {
              setText(event.target.value);
              setError(null);
            }}
            disabled={disabled}
            aria-invalid={!!error}
            aria-describedby={error ? 'chat-error' : 'chat-count'}
          />
          <div className={styles.send}>
            <span id="chat-count">{codePointLength(text)} / 500</span>
            <Button type="submit" size="sm" disabled={disabled}>
              Enviar
              <Icon name="send" size={16} />
            </Button>
          </div>
          {error ? (
            <p id="chat-error" className={styles.error} role="alert">
              {error}
            </p>
          ) : null}
        </form>
      </div>
    </aside>
  );
}
