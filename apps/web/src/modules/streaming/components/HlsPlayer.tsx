import { useEffect, useRef, useState } from 'react';
import type { PublicSession } from '@contracts/p1';
import type Hls from 'hls.js';

import { Button } from '@/src/components/atoms/Button';
import { Notice } from '@/src/components/molecules/Notice';
import { ViewerLeaseController } from '@/src/modules/streaming/ViewerLease';

import styles from './Player.module.css';

export function HlsPlayer({ session, title }: { session: PublicSession; title: string }) {
  const video = useRef<HTMLVideoElement>(null);
  const [failure, setFailure] = useState('');
  const [leaseError, setLeaseError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const playable =
    session.availability === 'PLAYABLE' && session.status === 'LIVE' && !!session.playbackUrl;
  useEffect(() => {
    const element = video.current;
    if (!element || !playable) return;
    let disposed = false,
      engine: Hls | undefined,
      lease: ViewerLeaseController | null = null,
      frame: number | undefined;
    function stopLease() {
      lease?.stop();
      lease = null;
    }
    function startLease() {
      if (
        disposed ||
        element!.paused ||
        element!.readyState < 2 ||
        element!.videoWidth === 0 ||
        lease
      )
        return;
      lease = new ViewerLeaseController(session.sessionId, (message) => {
        if (!disposed) setLeaseError(message);
      });
      lease.start();
    }
    function decoded() {
      if (frame !== undefined) element!.cancelVideoFrameCallback(frame);
      if (typeof element!.requestVideoFrameCallback === 'function')
        frame = element!.requestVideoFrameCallback(() => {
          frame = undefined;
          startLease();
        });
      else startLease(); // readyState >= HAVE_CURRENT_DATA plus videoWidth means a decoded frame.
    }
    function fail() {
      if (disposed) return;
      stopLease();
      setFailure('No se pudo reproducir la señal. Puedes reintentar.');
    }
    function pageHide() {
      stopLease();
    }
    function pageShow() {
      if (!disposed) decoded();
    }
    element.addEventListener('playing', decoded);
    element.addEventListener('pause', stopLease);
    element.addEventListener('error', fail);
    window.addEventListener('pagehide', pageHide);
    window.addEventListener('pageshow', pageShow);
    async function load() {
      const url = new URL(session.playbackUrl!, window.location.origin);
      // Public media is always delivered through our same-origin proxy, never an arbitrary URL.
      if (
        url.pathname !== `/hls/${session.sessionId}/index.m3u8` ||
        url.search ||
        url.hash ||
        url.username ||
        url.password
      ) {
        fail();
        return;
      }
      const source = url.pathname;
      if (element!.canPlayType('application/vnd.apple.mpegurl')) element!.src = source;
      else {
        const { default: Hls } = await import('hls.js');
        if (disposed) return;
        if (!Hls.isSupported()) {
          fail();
          return;
        }
        engine = new Hls({ enableWorker: true, backBufferLength: 30 });
        engine.on(Hls.Events.ERROR, (_event, data) => {
          if (data.fatal) {
            engine?.destroy();
            fail();
          }
        });
        engine.loadSource(source);
        engine.attachMedia(element!);
      }
      try {
        await element!.play();
      } catch {
        /* Native controls permit an explicit user gesture. */
      }
    }
    void load().catch(fail);
    return () => {
      disposed = true;
      stopLease();
      if (frame !== undefined) element.cancelVideoFrameCallback(frame);
      engine?.destroy();
      element.removeEventListener('playing', decoded);
      element.removeEventListener('pause', stopLease);
      element.removeEventListener('error', fail);
      window.removeEventListener('pagehide', pageHide);
      window.removeEventListener('pageshow', pageShow);
      element.pause();
      element.removeAttribute('src');
      element.load();
    };
  }, [session.sessionId, session.playbackUrl, playable, attempt]);
  return (
    <div className="stack">
      <div className={styles.viewport}>
        {/* Live captions are outside the explicitly agreed P1 scope (SPEC-08). */}
        {}
        <video
          ref={video}
          controls
          muted
          playsInline
          aria-label={`Reproductor de ${title}`}
          style={{ width: '100%', height: '100%' }}
        />
      </div>
      {!playable ? (
        <Notice>
          {session.status === 'ENDED'
            ? 'La emisión ha finalizado.'
            : session.availability === 'RECONNECTING'
              ? 'Reconectando la señal…'
              : 'Esperando señal reproducible…'}
        </Notice>
      ) : null}
      {failure ? (
        <Notice tone="error">
          {failure}
          <Button
            onClick={() => {
              setFailure('');
              setAttempt((previous) => previous + 1);
            }}
          >
            Reintentar reproducción
          </Button>
        </Notice>
      ) : null}
      {leaseError ? <Notice tone="warning">{leaseError}</Notice> : null}
    </div>
  );
}
