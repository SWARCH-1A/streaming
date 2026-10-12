import { useEffect, useEffectEvent, useRef, useState } from 'react';
import type { PublicSession } from '@contracts/p1';
import type Hls from 'hls.js';

import artwork from '@/public/images/signal-grid.svg';

import { Button } from '@/src/components/atoms/Button';
import { BroadcastPlaceholder } from '@/src/components/molecules/BroadcastPlaceholder';
import { Notice } from '@/src/components/molecules/Notice';
import { ViewerLeaseController } from '@/src/modules/streaming/ViewerLease';

import styles from './Player.module.css';

function bufferedPosition(element: HTMLVideoElement): number | null {
  for (let index = 0; index < element.buffered.length; index++) {
    if (element.buffered.end(index) > element.currentTime + 0.05)
      return Math.max(element.currentTime, element.buffered.start(index));
  }
  return null;
}

export function HlsPlayer({
  session,
  title,
  poster,
}: {
  session: PublicSession;
  title: string;
  poster?: string | null | undefined;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const synchronize = useRef<(() => void) | null>(null);
  const [failure, setFailure] = useState('');
  const [leaseError, setLeaseError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [drained, setDrained] = useState(!session.playbackUrl);
  // Null ends delivery, not playback of frames already downloaded.
  const [source, setSource] = useState(session.playbackUrl);
  if (session.playbackUrl && session.playbackUrl !== source) setSource(session.playbackUrl);
  const currentSession = useEffectEvent(() => session);
  const playable =
    session.availability === 'PLAYABLE' && session.status === 'LIVE' && !!session.playbackUrl;

  useEffect(() => {
    const element = video.current;
    if (!element || !source) return;
    let disposed = false,
      engine: Hls | undefined,
      lease: ViewerLeaseController | null = null,
      frame: number | undefined,
      retry: ReturnType<typeof setTimeout> | undefined,
      networkRetries = 0,
      needsRestart = false,
      deliveryEnded = false;
    const sessionId = currentSession().sessionId;
    function stopLease() {
      lease?.stop();
      lease = null;
    }
    function ended() {
      return deliveryEnded || currentSession().status === 'ENDED';
    }
    function finish() {
      if (!ended()) return;
      const remaining = bufferedPosition(element!);
      setDrained(remaining === null);
      if (remaining === null) element!.pause();
      else if (!element!.paused && remaining > element!.currentTime)
        element!.currentTime = remaining;
    }
    function sync() {
      const state = currentSession();
      if (ended() || state.availability !== 'PLAYABLE') {
        stopLease();
        setLeaseError('');
      }
      if (ended()) {
        clearTimeout(retry);
        retry = undefined;
        engine?.stopLoad();
        setFailure('');
        finish();
      } else if (state.availability === 'PLAYABLE' && !element!.paused && needsRestart) resume();
    }
    function startLease() {
      if (disposed || element!.paused || element!.readyState < 2 || element!.videoWidth === 0)
        return;
      setFailure('');
      networkRetries = 0;
      const state = currentSession();
      if (lease || ended() || state.status !== 'LIVE' || state.availability !== 'PLAYABLE') return;
      lease = new ViewerLeaseController(sessionId, (message) => {
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
      else startLease(); // HAVE_CURRENT_DATA and videoWidth confirm a decoded frame.
    }
    function fail() {
      if (disposed || ended()) return;
      stopLease();
      setFailure('No se pudo reproducir la señal. Puedes reintentar.');
    }
    function pause() {
      stopLease();
      clearTimeout(retry);
      retry = undefined;
      // Refresh the playlist without fetching obsolete fragments during a pause.
      engine?.pauseBuffering();
    }
    function resume() {
      if (disposed) return;
      const buffered = bufferedPosition(element!);
      if (buffered !== null) {
        if (buffered > element!.currentTime) element!.currentTime = buffered;
      } else if (!ended()) {
        const available = engine?.liveSyncPosition;
        if (available != null) element!.currentTime = available;
        else if (element!.seekable.length)
          element!.currentTime = element!.seekable.start(element!.seekable.length - 1);
      }
      if (ended()) {
        finish();
        return;
      }
      if (needsRestart) {
        needsRestart = false;
        if (engine?.levels.length === 0)
          engine.loadSource(new URL(source!, window.location.origin).pathname);
        engine?.startLoad(element!.currentTime);
      }
      engine?.resumeBuffering();
      if (!engine && element!.error) {
        element!.load();
        void element!.play().catch(() => {});
      }
    }
    function mediaError() {
      if (ended()) finish();
      else if (!element!.paused) fail();
    }
    function recoverNetwork() {
      needsRestart = true;
      if (disposed || ended() || element!.paused || retry !== undefined) return;
      retry = setTimeout(() => {
        retry = undefined;
        if (disposed || ended() || element!.paused) return;
        if (++networkRetries > 3 && bufferedPosition(element!) === null) {
          if (currentSession().availability === 'PLAYABLE') fail();
          return;
        }
        resume();
      }, 2000);
    }
    function pageShow() {
      if (!disposed) decoded();
    }
    synchronize.current = sync;
    element.addEventListener('playing', decoded);
    element.addEventListener('play', resume);
    element.addEventListener('pause', pause);
    element.addEventListener('error', mediaError);
    element.addEventListener('timeupdate', finish);
    element.addEventListener('waiting', finish);
    element.addEventListener('ended', finish);
    window.addEventListener('pagehide', stopLease);
    window.addEventListener('pageshow', pageShow);
    async function load() {
      const url = new URL(source!, window.location.origin);
      if (
        url.pathname !== `/hls/${sessionId}/index.m3u8` ||
        url.search ||
        url.hash ||
        url.username ||
        url.password
      ) {
        fail();
        return;
      }
      if (!window.MediaSource && element!.canPlayType('application/vnd.apple.mpegurl'))
        element!.src = url.pathname;
      else {
        const { default: Hls } = await import('hls.js');
        if (disposed) return;
        if (!Hls.isSupported()) {
          if (element!.canPlayType('application/vnd.apple.mpegurl')) element!.src = url.pathname;
          else fail();
        } else {
          engine = new Hls({ enableWorker: true, backBufferLength: 30 });
          engine.on(Hls.Events.ERROR, (_event, data) => {
            if (!data.fatal || disposed) return;
            // Preserve MediaSource and its queued frames when delivery ends.
            if (data.response?.code === 410) {
              deliveryEnded = true;
              sync();
            } else if (ended()) finish();
            else if (data.type === Hls.ErrorTypes.NETWORK_ERROR) recoverNetwork();
            else fail();
          });
          engine.loadSource(url.pathname);
          engine.attachMedia(element!);
        }
      }
      sync();
      if (ended()) return;
      try {
        await element!.play();
      } catch {
        /* Native controls permit an explicit user gesture. */
      }
    }
    void load().catch(fail);
    return () => {
      disposed = true;
      synchronize.current = null;
      clearTimeout(retry);
      stopLease();
      if (frame !== undefined) element.cancelVideoFrameCallback(frame);
      engine?.destroy();
      element.removeEventListener('playing', decoded);
      element.removeEventListener('play', resume);
      element.removeEventListener('pause', pause);
      element.removeEventListener('error', mediaError);
      element.removeEventListener('timeupdate', finish);
      element.removeEventListener('waiting', finish);
      element.removeEventListener('ended', finish);
      window.removeEventListener('pagehide', stopLease);
      window.removeEventListener('pageshow', pageShow);
      element.pause();
      element.removeAttribute('src');
      element.load();
    };
  }, [session.sessionId, source, attempt]);
  useEffect(() => {
    synchronize.current?.();
  }, [session.status, session.availability]);

  return (
    <div className="stack">
      <div className={styles.viewport}>
        {/* Live captions are outside the explicitly agreed P1 scope (SPEC-08). */}
        <video
          ref={video}
          controls={!(session.status === 'ENDED' && drained)}
          poster={poster || artwork}
          muted
          playsInline
          aria-label={`Reproductor de ${title}`}
          style={{ width: '100%', height: '100%' }}
        />
        {session.status === 'ENDED' && drained ? (
          <div className={styles.endState}>
            <BroadcastPlaceholder
              title="La emisión ha finalizado."
              description="Vuelve al canal para saber qué viene después."
              image={poster}
            />
          </div>
        ) : null}
      </div>
      {!playable && session.status !== 'ENDED' ? (
        <Notice>
          {session.availability === 'RECONNECTING'
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
