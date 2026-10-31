import { useEffect, useRef, useState } from 'react';
import type { P2ChatReplayItem, P2ChatReplayPage } from '@contracts/p2';

import { Notice } from '@/src/components/molecules/Notice';
import { errorMessage, HttpError, request } from '@/src/shared/api/http';

import styles from './ChatPanel.module.css';
import { REPLAY_WINDOW_MS, visibleAt, windowsFor } from './replayWindow';

const MAX_PAGES = 20; // 20 x 50 mensajes por ventana de 30 s cubre la cuota de 20 msg/s

async function loadWindow(vodId: string, fromMs: number, signal: AbortSignal) {
  const items: P2ChatReplayItem[] = [];
  let cursor: string | null = null;
  for (let page = 0; page < MAX_PAGES; page++) {
    const query = new URLSearchParams({
      fromMs: String(fromMs),
      toMs: String(fromMs + REPLAY_WINDOW_MS),
      limit: '50',
    });
    if (cursor) query.set('cursor', cursor);
    const result: P2ChatReplayPage = await request<P2ChatReplayPage>(
      `/api/chat/vods/${encodeURIComponent(vodId)}/messages?${query.toString()}`,
      { signal },
    );
    if (result.vodId !== vodId || result.fromMs !== fromMs)
      throw new Error('Replay de otra ventana.');
    items.push(...result.items);
    cursor = result.nextCursor;
    if (!cursor) break;
  }
  return items;
}

/**
 * Chat sincronizado con un VOD. positionMs es el tiempo actual del reproductor: al adelantar o
 * retroceder, los mensajes se muestran desde esa posición. Pide ventanas de 30 s alineadas.
 */
export function ReplayChatPanel({ vodId, positionMs }: { vodId: string; positionMs: number }) {
  const [windows, setWindows] = useState<Map<number, P2ChatReplayItem[]>>(new Map());
  const [error, setError] = useState('');
  const pending = useRef(new Map<number, AbortController>());
  const history = useRef<HTMLDivElement>(null);
  const needed = windowsFor(positionMs);
  const key = needed.join(',');

  useEffect(() => {
    const wanted = new Set(key.split(',').map(Number));
    // Una búsqueda lejana cancela las ventanas que ya no hacen falta y libera las viejas.
    for (const [start, controller] of pending.current)
      if (!wanted.has(start)) {
        controller.abort();
        pending.current.delete(start);
      }
    for (const start of wanted) {
      if (pending.current.has(start)) continue;
      const controller = new AbortController();
      pending.current.set(start, controller);
      loadWindow(vodId, start, controller.signal)
        .then((items) => {
          // Conserva solo ventanas cercanas a la posición para acotar la memoria.
          setWindows((previous) => {
            const next = new Map(previous).set(start, items);
            for (const loaded of next.keys())
              if (Math.abs(loaded - start) > 2 * REPLAY_WINDOW_MS) {
                next.delete(loaded);
                pending.current.delete(loaded);
              }
            return next;
          });
          setError('');
        })
        .catch((failure: unknown) => {
          if (controller.signal.aborted) return;
          pending.current.delete(start); // permite reintentar en el siguiente cambio de posición
          setError(
            failure instanceof HttpError && failure.status === 404
              ? 'El chat de este video no está disponible.'
              : `No se pudo cargar el chat del video. ${errorMessage(failure)}`,
          );
        });
    }
  }, [vodId, key]);

  useEffect(
    () => () => {
      for (const controller of pending.current.values()) controller.abort();
    },
    [],
  );

  const visible = visibleAt(windows, positionMs);
  const latest = visible.at(-1)?.messageId;
  useEffect(() => {
    if (history.current) history.current.scrollTop = history.current.scrollHeight;
  }, [latest]);

  return (
    <aside className={styles.chat} aria-label="Chat de la transmisión">
      <header className={styles.header}>
        <h2>Chat de la transmisión</h2>
      </header>
      <div
        ref={history}
        className={styles.history}
        role="log"
        aria-label="Mensajes del chat"
        tabIndex={0}
      >
        {visible.map((item) =>
          item.removed ? (
            <p key={item.sequence} className={styles.removed}>
              Mensaje eliminado por el moderador
            </p>
          ) : (
            <div key={item.sequence} className="stack">
              <p>
                <strong>{item.author.displayName}</strong>
              </p>
              <p>{item.text}</p>
            </div>
          ),
        )}
      </div>
      {error ? (
        <div className={styles.composer}>
          <Notice tone="warning">{error}</Notice>
        </div>
      ) : null}
    </aside>
  );
}
