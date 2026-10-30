import { useEffect, useState } from 'react';
import type { P2ReplayMessage, P2ReplayPage, P2Vod } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { Notice } from '@/src/components/molecules/Notice';
import { errorMessage, request } from '@/src/shared/api/http';

import { duration } from './format';
import { VodPlayer } from './VodPlayer';

export function VodPage({ vod }: { vod: P2Vod }) {
  const [window, setWindow] = useState({ from: -1, grant: '' });
  const [pages, setPages] = useState<string[]>([]);
  const [messages, setMessages] = useState<P2ReplayMessage[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [chatError, setChatError] = useState('');
  const [retry, setRetry] = useState(0);
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
          setMessages((previous) =>
            cursor
              ? [
                  ...previous.filter(
                    (item) => !page.items.some((next) => next.messageId === item.messageId),
                  ),
                  ...page.items,
                ]
              : page.items,
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
      <p>
        {duration(vod.durationMs)} ·{' '}
        {vod.publicationState === 'DRAFT' ? 'Vista previa privada' : 'Grabación publicada'}
      </p>
      {vod.processingState === 'READY' ? (
        <VodPlayer
          vod={vod}
          onPosition={(ms, grant) => {
            const from = Math.floor(ms / 10000) * 10000;
            if (window.from !== from || window.grant !== grant) {
              setPending(true);
              setMessages([]);
              setPages([]);
              setNextCursor(null);
              setWindow({ from, grant });
            }
          }}
        />
      ) : (
        <Notice>La grabación todavía no está lista para reproducirse.</Notice>
      )}
      <p>{vod.description}</p>
      <section aria-label="Chat de la grabación">
        <h2>Chat de la grabación</h2>
        {chatError ? (
          <Notice tone="warning">
            {chatError} El video puede continuar.{' '}
            <Button
              onClick={() => {
                setPending(true);
                setRetry((value) => value + 1);
              }}
            >
              Reintentar chat
            </Button>
          </Notice>
        ) : (
          messages.map((message) => (
            <p key={message.messageId}>
              {message.author.displayName}: {message.text}
            </p>
          ))
        )}
        {pending ? <p role="status">Cargando chat…</p> : null}
        {nextCursor ? (
          <Button
            disabled={pending}
            onClick={() => {
              setPending(true);
              setPages((previous) => [...previous, nextCursor]);
            }}
          >
            Cargar más mensajes de este momento
          </Button>
        ) : null}
      </section>
    </main>
  );
}
