import type { FormEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import type {
  ChatError,
  ChatHistory,
  ChatMessage,
  ChatReady,
  ChatStatus,
  MessageAccepted,
  MessageCreated,
  MessageSend,
} from '@contracts/p1';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Button } from '@/src/components/atoms/Button';
import { Textarea } from '@/src/components/atoms/Textarea';
import { Notice } from '@/src/components/molecules/Notice';
import { errorMessage, request } from '@/src/shared/api/http';
import { codePointLength } from '@/src/shared/format';
import { useSession } from '@/src/shell/session/useSession';

import styles from './ChatPanel.module.css';
import { hasGap, mergeMessages } from './mergeMessages';
import { validateMessage } from './validateMessage';

type Frame = ChatReady | ChatStatus | MessageAccepted | MessageCreated | ChatError;
interface Room {
  messages: ChatMessage[];
  status: ChatHistory['roomStatus'];
  connected: boolean;
  error: string;
  gap: boolean;
}
export function LiveChatPanel({ sessionId, readOnly }: { sessionId: string; readOnly: boolean }) {
  const { user } = useSession();
  const [room, setRoom] = useState<Room>({
    messages: [],
    status: 'NOT_OPEN',
    connected: false,
    error: '',
    gap: false,
  });
  const [text, setText] = useState('');
  const [pending, setPending] = useState<MessageSend | null>(null);
  const pendingRef = useRef<MessageSend | null>(null);
  const socket = useRef<WebSocket | null>(null);
  const [failure, setFailure] = useState('');
  const [autoscroll, setAutoscroll] = useState(true);
  const history = useRef<HTMLDivElement>(null);
  const userId = user?.userId;
  useEffect(() => {
    let disposed = false,
      retry = 0,
      connection: WebSocket | null = null,
      timer: ReturnType<typeof setTimeout>,
      readyTimer: ReturnType<typeof setTimeout>;
    let messages: ChatMessage[] = [],
      lastSequence = 0,
      lostHistory = false;
    const abort = new AbortController();
    const apply = (changes: Partial<Room>) => {
      if (!disposed) setRoom((previous) => ({ ...previous, ...changes }));
    };
    async function backlog() {
      const result = await request<ChatHistory>(
        `/api/chat/sessions/${sessionId}/messages?limit=50`,
        { signal: abort.signal },
      );
      if (disposed) return;
      if (result.sessionId !== sessionId) throw new Error('Historial de otra sesión.');
      const earliest = result.items[0]?.sequence ?? result.snapshotSequence + 1;
      const unrecoverable = lastSequence > 0 && earliest > lastSequence + 1;
      lostHistory ||= unrecoverable;
      messages = mergeMessages(sessionId, messages, result.items);
      lastSequence = Math.max(
        lastSequence,
        result.snapshotSequence,
        ...messages.map((item) => item.sequence),
      );
      apply({ messages, status: result.roomStatus, gap: lostHistory || hasGap(messages) });
    }
    function connect() {
      if (disposed) return;
      apply({ connected: false });
      const ws = new WebSocket(
        `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/realtime/chat/sessions/${sessionId}`,
      );
      connection = ws;
      socket.current = ws;
      let chain = Promise.resolve(),
        ready = false;
      readyTimer = setTimeout(() => ws.close(), 8000);
      ws.onmessage = (event) => {
        // Validate and process sequentially; async decoding must not reorder frames.
        const raw: unknown = event.data;
        chain = chain
          .then(async () => {
            if (disposed || connection !== ws) return;
            if (typeof raw !== 'string' || raw.length > 65536)
              throw new Error('Frame de chat inválido.');
            const frame = JSON.parse(raw) as Frame;
            const { validateFrame } = await import('@/src/shared/api/validate');
            validateFrame(frame);
            if (disposed || connection !== ws) return;
            if (frame.type === 'chat.ready') {
              if (ready || frame.sessionId !== sessionId)
                throw new Error('Sesión de chat inconsistente.');
              ready = true;
              clearTimeout(readyTimer);
              retry = 0;
              await backlog();
              if (lastSequence < frame.lastSequence) await backlog();
              apply({
                connected: true,
                error: '',
                gap: lostHistory || lastSequence < frame.lastSequence || hasGap(messages),
              });
            } else if (!ready) throw new Error('Chat no confirmó suscripción.');
            else if (frame.type === 'message.created') {
              if (frame.message.sessionId !== sessionId) throw new Error('Mensaje de otra sesión.');
              if (frame.message.sequence > lastSequence + 1) await backlog();
              messages = mergeMessages(sessionId, messages, [frame.message]);
              lastSequence = Math.max(lastSequence, frame.message.sequence);
              apply({ messages, gap: lostHistory || hasGap(messages) });
            } else if (frame.type === 'message.accepted') {
              if (frame.sessionId !== sessionId) throw new Error('ACK de otra sesión.');
              if (frame.clientMessageId === pendingRef.current?.clientMessageId) {
                pendingRef.current = null;
                setPending(null);
                setText('');
                setFailure('');
              }
            } else if (frame.type === 'chat.status') {
              if (frame.sessionId !== sessionId) throw new Error('Estado de otra sesión.');
              apply({ status: frame.roomStatus });
            } else if (frame.type === 'error') {
              setFailure(
                `${frame.message} (${frame.code}${frame.retryAfterMs ? ` · espera ${frame.retryAfterMs} ms` : ''})`,
              );
              if (frame.code === 'AUTH_REQUIRED')
                window.dispatchEvent(new Event('session-expired'));
            }
          })
          .catch((error) => {
            if (!disposed && connection === ws) {
              apply({ error: errorMessage(error), connected: false });
              ws.close();
            }
          });
      };
      ws.onerror = () => {
        if (!disposed && connection === ws)
          apply({ error: 'Chat no disponible. La reproducción continúa.' });
      };
      ws.onclose = () => {
        clearTimeout(readyTimer);
        if (disposed || connection !== ws) return;
        apply({ connected: false, error: 'Chat desconectado. Reconectando…' });
        timer = setTimeout(connect, Math.min(1000 * 2 ** retry++, 15000));
      };
    }
    connect();
    return () => {
      disposed = true;
      abort.abort();
      clearTimeout(timer);
      clearTimeout(readyTimer);
      if (socket.current === connection) socket.current = null;
      connection?.close();
    };
  }, [sessionId, userId]);
  useEffect(() => {
    if (autoscroll && history.current) history.current.scrollTop = history.current.scrollHeight;
  }, [autoscroll, room.messages]);
  const disabled = !user || readOnly || room.status !== 'OPEN' || !room.connected;
  function transmit(payload: MessageSend) {
    if ((!pendingRef.current && disabled) || !user || socket.current?.readyState !== WebSocket.OPEN)
      return;
    pendingRef.current = payload;
    setPending(payload);
    setFailure('');
    socket.current.send(JSON.stringify(payload));
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    const validated = validateMessage(text);
    if (validated.error) {
      setFailure(validated.error);
      return;
    }
    transmit(
      pendingRef.current?.text === validated.text
        ? pendingRef.current
        : { type: 'message.send', clientMessageId: crypto.randomUUID(), text: validated.text },
    );
  }
  return (
    <aside id="chat" className={styles.chat} aria-label="Chat en vivo">
      <header className={styles.header}>
        <h2>Chat en vivo</h2>
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
        {room.messages.map((message) => (
          <div key={message.sequence} className="stack">
            <p>
              <strong>{message.author.displayName}</strong>{' '}
              <time dateTime={message.serverCreatedAtUtc}>
                {new Date(message.serverCreatedAtUtc).toLocaleTimeString('es', {
                  hour: '2-digit',
                  minute: '2-digit',
                })}
              </time>
            </p>
            <p>{message.text}</p>
          </div>
        ))}
      </div>
      <div className={styles.composer}>
        {room.error ? <Notice tone="error">{room.error}</Notice> : null}
        {room.gap ? (
          <Notice tone="warning">
            Parte del historial ya no está disponible. Se muestran los mensajes recientes.
          </Notice>
        ) : null}
        {readOnly || room.status !== 'OPEN' ? <Notice>El chat está en modo lectura.</Notice> : null}
        {!user ? <ActionLink to="/login">Inicia sesión para escribir</ActionLink> : null}
        <form onSubmit={submit} className="stack">
          <label htmlFor="chat-message">Mensaje al canal</label>
          <Textarea
            id="chat-message"
            value={text}
            disabled={disabled || !!pending}
            rows={2}
            aria-describedby={failure ? 'chat-error' : 'chat-count'}
            aria-invalid={!!failure}
            onChange={(event) => {
              setText(event.target.value);
              setFailure('');
            }}
          />
          <span id="chat-count">{codePointLength(text)} / 500</span>
          <Button type="submit" disabled={disabled || !!pending}>
            Enviar
          </Button>
          {pending ? (
            <>
              <p role="status">Esperando confirmación del mensaje.</p>
              <Button disabled={!user || !room.connected} onClick={() => transmit(pending)}>
                Reintentar mensaje
              </Button>
              <Button
                variant="ghost"
                onClick={() => {
                  pendingRef.current = null;
                  setPending(null);
                }}
              >
                Descartar envío pendiente
              </Button>
            </>
          ) : null}
          {failure ? (
            <p id="chat-error" role="alert">
              {failure}
            </p>
          ) : null}
        </form>
      </div>
    </aside>
  );
}
