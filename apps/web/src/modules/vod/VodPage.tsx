import { useEffect, useState } from 'react';
import type { P2ReplayMessage, P2ReplayPage, P2Vod } from '@contracts/p2';

import { Notice } from '@/src/components/molecules/Notice';
import { ReplayChatPanel } from '@/src/modules/chat/entry';
import { errorMessage, request } from '@/src/shared/api/http';

import { duration } from './format';
import styles from './VodPage.module.css';
import { VodPlayer } from './VodPlayer';

export function VodPage({ vod }: { vod: P2Vod }) {
  const [window, setWindow] = useState({ from: -1, grant: '' });
  const [pages, setPages] = useState<string[]>([]);
  const [messages, setMessages] = useState<P2ReplayMessage[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [chatError, setChatError] = useState('');
  const [retry, setRetry] = useState(0);
  const [position, setPosition] = useState(0);
  const cursor = pages.at(-1);
  useEffect(() => {
    if (window.from < 0 || window.from >= vod.durationMs) return;
    const controller = new AbortController();
    void request<P2ReplayPage>(
      `/api/chat/vods/${vod.vodId}/messages?fromMs=${window.from}&toMs=${Math.min(vod.durationMs, window.from + 10000)}&limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
      { signal: controller.signal, headers: { 'X-VOD-Playback-Grant': window.grant } },
    )
      .then(
        (page) => {
          if (page.mappingVersion !== vod.mappingVersion) {
            setMessages([]);
            setNextCursor(null);
            setChatError('La versión del video cambió. Vuelve a abrir la grabación.');
            return;
          }
          // Al avanzar se conservan las ventanas anteriores, como el historial de un chat en vivo.
          setMessages((previous) =>
            [
              ...previous.filter(
                (item) => !page.items.some((next) => next.messageId === item.messageId),
              ),
              ...page.items,
            ].slice(-500),
          );
          setNextCursor(page.nextCursor);
          setChatError('');
        },
        (reason: unknown) => {
          if (!controller.signal.aborted) {
            setMessages([]);
            setNextCursor(null);
            setChatError(errorMessage(reason));
          }
        },
      )
      .finally(() => {
        if (!controller.signal.aborted) setPending(false);
      });
    return () => controller.abort();
  }, [vod.vodId, vod.durationMs, vod.mappingVersion, window, cursor, retry]);
  return (
    <main className="stack">
      <h1>{vod.title}</h1>
      <div className={styles.layout}>
        <div className="stack">
          {vod.processingState === 'READY' ? (
            <VodPlayer
              vod={vod}
              onPosition={(ms, grant) => {
                setPosition(ms);
                const from = Math.floor(ms / 10000) * 10000;
                if (window.from !== from || window.grant !== grant) {
                  // Reproducción continua: siguiente ventana. Un salto reinicia el historial.
                  const forward = from === window.from + 10000 || from === window.from;
                  setPending(true);
                  if (!forward) setMessages([]);
                  setPages([]);
                  setNextCursor(null);
                  setWindow({ from, grant });
                }
              }}
            />
          ) : (
            <Notice>La grabación todavía no está lista para reproducirse.</Notice>
          )}
          <p>
            {duration(vod.durationMs)} ·{' '}
            {vod.publicationState === 'DRAFT' ? 'Vista previa privada' : 'Grabación publicada'}
          </p>
          <p>{vod.description}</p>
        </div>
        <ReplayChatPanel
          messages={messages}
          positionMs={position}
          pending={pending}
          error={chatError}
          onRetry={() => {
            setPending(true);
            setRetry((value) => value + 1);
          }}
          onMore={
            nextCursor
              ? () => {
                  setPending(true);
                  setPages((previous) => [...previous, nextCursor]);
                }
              : undefined
          }
        />
      </div>
    </main>
  );
}
