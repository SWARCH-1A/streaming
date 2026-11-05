import { useEffect, useRef, useState } from 'react';
import type { P2ReplayMessage, P2ReplayPage, P2Vod } from '@contracts/p2';

import { Notice } from '@/src/components/molecules/Notice';
import { ReplayChatPanel } from '@/src/modules/chat/entry';
import { errorMessage, request } from '@/src/shared/api/http';

import { duration } from './format';
import styles from './VodPage.module.css';
import { VodPlayer } from './VodPlayer';

const replayWindowMs = 5 * 60 * 1000;

function mergeMessages(previous: readonly P2ReplayMessage[], incoming: readonly P2ReplayMessage[]) {
  const byId = new Map(previous.map((message) => [message.messageId, message]));
  for (const message of incoming) byId.set(message.messageId, message);
  return [...byId.values()]
    .toSorted((a, b) => a.mediaOffsetMs - b.mediaOffsetMs || a.sequence - b.sequence)
    .slice(-500);
}

export function VodPage({ vod }: { vod: P2Vod }) {
  const [window, setWindow] = useState({ from: -1, grant: '' });
  const [pages, setPages] = useState<string[]>([]);
  const [messages, setMessages] = useState<P2ReplayMessage[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [chatError, setChatError] = useState('');
  const [retry, setRetry] = useState(0);
  const [position, setPosition] = useState(0);
  const historyController = useRef<AbortController | null>(null);
  const cursor = pages.at(-1);

  useEffect(() => () => historyController.current?.abort(), []);

  async function loadHistoryThrough(targetMs: number, grant: string) {
    historyController.current?.abort();
    const controller = new AbortController();
    historyController.current = controller;
    const through = Math.min(vod.durationMs, targetMs + 10000);
    const items: P2ReplayMessage[] = [];
    try {
      for (let fromMs = 0; fromMs < through; fromMs += replayWindowMs) {
        const toMs = Math.min(through, fromMs + replayWindowMs);
        let pageCursor: string | null = null;
        do {
          const query: URLSearchParams = new URLSearchParams({
            fromMs: String(fromMs),
            toMs: String(toMs),
            limit: '50',
            ...(pageCursor ? { cursor: pageCursor } : {}),
          });
          const replayPage: P2ReplayPage = await request<P2ReplayPage>(
            `/api/chat/vods/${encodeURIComponent(vod.vodId)}/messages?${query.toString()}`,
            { signal: controller.signal, headers: { 'X-VOD-Playback-Grant': grant } },
          );
          if (replayPage.mappingVersion !== vod.mappingVersion) {
            setMessages([]);
            setNextCursor(null);
            setChatError('La versión del video cambió. Vuelve a abrir la grabación.');
            return;
          }
          items.push(...replayPage.items);
          pageCursor = replayPage.nextCursor;
        } while (pageCursor && !controller.signal.aborted);
      }
      if (!controller.signal.aborted) {
        setMessages((previous) =>
          mergeMessages(
            previous.filter((message) => message.mediaOffsetMs >= through),
            items,
          ),
        );
        setChatError('');
      }
    } catch (reason) {
      if (!controller.signal.aborted) setChatError(errorMessage(reason));
    } finally {
      if (historyController.current === controller) historyController.current = null;
    }
  }

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
          // Al avanzar se conservan las ventanas anteriores, como el historial de un chat en vivo. La
          // primera página de una ventana reemplaza lo que había de esa ventana: un mensaje retirado
          // después de cargarlo desaparece al refrescarla.
          const to = Math.min(vod.durationMs, window.from + 10000);
          setMessages((previous) =>
            mergeMessages(
              cursor
                ? previous
                : previous.filter(
                    (item) => item.mediaOffsetMs < window.from || item.mediaOffsetMs >= to,
                  ),
              page.items,
            ),
          );
          setNextCursor(page.nextCursor);
          setChatError('');
        },
        (reason: unknown) => {
          if (!controller.signal.aborted) {
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
                  // Mantén el historial al cambiar de ventana. Si se salta hacia delante,
                  // recupera también los mensajes de los intervalos que no se reprodujeron.
                  const forward = from === window.from + 10000 || from === window.from;
                  if (
                    !forward &&
                    ((window.from < 0 && from > 0) || (window.from >= 0 && from > window.from))
                  ) {
                    void loadHistoryThrough(from, grant);
                  }
                  setPending(true);
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
