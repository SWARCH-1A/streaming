import { useEffect, useEffectEvent, useRef, useState } from 'react';
import type { PublicSession } from '@contracts/p1';
import type Hls from 'hls.js';

import artwork from '@/public/images/signal-grid.svg';

import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Select } from '@/src/components/atoms/Select';
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

function formatTime(seconds: number) {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00:00';
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const remainder = total % 60;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

export function HlsPlayer({
  session,
  title,
  poster,
  viewerCount,
}: {
  session: PublicSession;
  title: string;
  poster?: string | null | undefined;
  viewerCount?: number | undefined;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const viewport = useRef<HTMLDivElement>(null);
  const synchronize = useRef<(() => void) | null>(null);
  const selectQuality = useRef<((uri: string) => void) | null>(null);
  const [quality, setQuality] = useState({
    levels: [] as { uri: string; label: string }[],
    selected: '',
    active: '',
    native: false,
  });
  const [failure, setFailure] = useState('');
  const [leaseError, setLeaseError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [drained, setDrained] = useState(!session.playbackUrl);
  const [paused, setPaused] = useState(true);
  const [muted, setMuted] = useState(true);
  const [volume, setVolume] = useState(0.85);
  const [theater, setTheater] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  // Null ends delivery, not playback of frames already downloaded.
  const [source, setSource] = useState(session.playbackUrl);
  if (session.playbackUrl && session.playbackUrl !== source) setSource(session.playbackUrl);
  const currentSession = useEffectEvent(() => session);
  const playable =
    session.availability === 'PLAYABLE' && session.status === 'LIVE' && !!session.playbackUrl;
  const signalTone =
    session.availability === 'PLAYABLE'
      ? 'success'
      : session.availability === 'RECONNECTING'
        ? 'warning'
        : 'neutral';
  const signalLabel =
    session.availability === 'PLAYABLE'
      ? 'En vivo'
      : session.availability === 'RECONNECTING'
        ? 'Reconectando'
        : session.status === 'ENDED'
          ? 'Emisión finalizada'
          : 'Esperando señal';

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
    let selectedUri = '';
    setQuality({ levels: [], selected: '', active: '', native: false });
    function updateQualities() {
      if (disposed || !engine) return;
      const levels = engine.levels.map((level) => ({
        uri: level.uri,
        label: [
          level.height > 0 ? `${level.height}p` : 'Original',
          level.bitrate > 0
            ? `${(level.bitrate / 1_000_000).toLocaleString('es-CO', { maximumFractionDigits: 2 })} Mb/s`
            : '',
        ]
          .filter(Boolean)
          .join(' · '),
      }));
      selectedUri = levels.some((level) => level.uri === selectedUri) ? selectedUri : '';
      // A removed/reordered level must never pin the player to a different variant.
      engine.loadLevel = selectedUri ? levels.findIndex((level) => level.uri === selectedUri) : -1;
      setQuality({
        levels,
        selected: selectedUri,
        active: engine.levels[engine.currentLevel]?.uri ?? '',
        native: false,
      });
    }
    function nativeQuality() {
      setQuality({ levels: [], selected: '', active: '', native: true });
    }
    selectQuality.current = (uri) => {
      if (!engine || disposed) return;
      const index = uri ? engine.levels.findIndex((level) => level.uri === uri) : -1;
      if (uri && index < 0) return;
      // Conservative switching preserves already buffered audio/video and the viewer lease.
      engine.loadLevel = index;
      selectedUri = uri;
      setQuality((previous) => ({ ...previous, selected: uri }));
    };
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
      if (!window.MediaSource && element!.canPlayType('application/vnd.apple.mpegurl')) {
        element!.src = url.pathname;
        nativeQuality();
      } else {
        const { default: Hls } = await import('hls.js');
        if (disposed) return;
        if (!Hls.isSupported()) {
          if (element!.canPlayType('application/vnd.apple.mpegurl')) {
            element!.src = url.pathname;
            nativeQuality();
          } else fail();
        } else {
          engine = new Hls({ enableWorker: true, backBufferLength: 30 });
          engine.on(Hls.Events.MANIFEST_PARSED, updateQualities);
          engine.on(Hls.Events.LEVELS_UPDATED, updateQualities);
          engine.on(Hls.Events.LEVEL_SWITCHED, (_event, data) => {
            if (disposed) return;
            const level = engine?.levels[data.level];
            setQuality((previous) => ({ ...previous, active: level?.uri ?? '' }));
          });
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
      selectQuality.current = null;
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

  useEffect(() => {
    if (!video.current) return;
    video.current.volume = volume;
    video.current.muted = muted;
  }, [muted, volume]);

  useEffect(() => {
    function updateFullscreen() {
      setFullscreen(document.fullscreenElement === viewport.current);
    }
    document.addEventListener('fullscreenchange', updateFullscreen);
    return () => document.removeEventListener('fullscreenchange', updateFullscreen);
  }, []);

  function togglePlayback() {
    const element = video.current;
    if (!element) return;
    if (element.paused) void element.play().catch(() => {});
    else element.pause();
  }

  function syncToLive() {
    const element = video.current;
    if (!element || !element.seekable.length) return;
    const liveEdge = element.seekable.end(element.seekable.length - 1);
    element.currentTime = liveEdge;
    if (element.paused) void element.play().catch(() => {});
  }

  function toggleMute() {
    setMuted((previous) => {
      const next = !previous;
      if (video.current) video.current.muted = next;
      return next;
    });
  }

  async function toggleFullscreen() {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (viewport.current?.requestFullscreen) await viewport.current.requestFullscreen();
    } catch {
      // The browser can reject fullscreen without changing playback state.
    }
  }

  return (
    <div className={styles.player}>
      <div ref={viewport} className={`${styles.viewport} ${theater ? styles.theater : ''}`}>
        {/* Live captions are outside the explicitly agreed P1 scope (SPEC-08). */}
        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
        <video
          ref={video}
          controls={false}
          poster={poster || artwork}
          muted={muted}
          playsInline
          aria-label={`Reproductor de ${title}`}
          onPlay={() => setPaused(false)}
          onPause={() => setPaused(true)}
          onVolumeChange={(event) => {
            setVolume(event.currentTarget.volume);
            setMuted(event.currentTarget.muted);
          }}
        />
        <div className={styles.overlayBar}>
          <div className={styles.overlayGroup}>
            <Badge tone={session.availability === 'PLAYABLE' ? 'live' : signalTone} dot>
              {session.availability === 'PLAYABLE' ? 'EN VIVO' : signalLabel}
            </Badge>
            {viewerCount !== undefined ? (
              <span className={styles.viewerChip}>
                <Icon name="eye" size={15} />
                <strong>{viewerCount.toLocaleString('es-CO')}</strong>
                <span>espectadores</span>
              </span>
            ) : null}
          </div>
        </div>
        {!playable && session.status !== 'ENDED' ? (
          <div className={styles.overlayMessage} role="status">
            <Icon name="broadcast" size={30} />
            <h2>{signalLabel}</h2>
            <p>
              {session.availability === 'RECONNECTING'
                ? 'Estamos intentando recuperar la señal.'
                : 'Esperando una señal reproducible.'}
            </p>
          </div>
        ) : null}
        {session.status === 'ENDED' && drained ? (
          <div className={styles.endState}>
            <BroadcastPlaceholder
              title="La emisión ha finalizado."
              description="Vuelve al canal para saber qué viene después."
              image={poster}
            />
          </div>
        ) : null}
        <div className={styles.controls}>
          <div className={styles.controlBar}>
            <div className={styles.controlGroup}>
              <Button
                variant="secondary"
                size="icon"
                className={styles.playerButton}
                aria-label={paused ? 'Reproducir emisión' : 'Pausar emisión'}
                onClick={togglePlayback}
              >
                <Icon name={paused ? 'play' : 'pause'} size={20} />
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className={styles.liveSync}
                aria-label="Ir al directo"
                onClick={syncToLive}
              >
                <span aria-hidden="true" />
                <span>DIRECTO</span>
              </Button>
              <div className={styles.volumeControl}>
                <Button
                  variant="ghost"
                  size="icon"
                  className={styles.iconButton}
                  aria-label={muted ? 'Activar volumen' : 'Silenciar emisión'}
                  aria-pressed={muted}
                  onClick={toggleMute}
                >
                  <Icon name={muted ? 'muted' : 'volume'} size={20} />
                </Button>
                <div
                  className={`${styles.rangeTrack} ${styles.volumeTrack}`}
                  style={{
                    background: `linear-gradient(to right, var(--color-primary) 0 ${(muted ? 0 : volume) * 100}%, rgb(255 255 255 / 24%) ${(muted ? 0 : volume) * 100}% 100%)`,
                  }}
                >
                  <Input
                    className={styles.rangeControl}
                    type="range"
                    min="0"
                    max="1"
                    step="0.05"
                    value={muted ? 0 : volume}
                    aria-label="Volumen"
                    onChange={(event) => {
                      const next = Number(event.target.value);
                      setVolume(next);
                      setMuted(next === 0);
                      if (video.current) {
                        video.current.volume = next;
                        video.current.muted = next === 0;
                      }
                    }}
                  />
                  <span
                    className={styles.rangeThumb}
                    style={{ left: `${(muted ? 0 : volume) * 100}%` }}
                    aria-hidden="true"
                  />
                </div>
              </div>
              <span className={styles.timecode}>
                {formatTime(session.timelinePositionMs / 1000)}
              </span>
            </div>
            <div className={styles.controlGroup}>
              {quality.levels.length > 0 ? (
                <label className={styles.qualityControl}>
                  <Icon className={styles.qualityIcon} name="settings" size={16} />
                  <span className="sr-only">Calidad</span>
                  <Select
                    className={styles.qualitySelect}
                    value={quality.selected}
                    disabled={!playable}
                    onChange={(event) => selectQuality.current?.(event.target.value)}
                  >
                    <option value="">Automática</option>
                    {quality.levels.map((level) => (
                      <option key={level.uri} value={level.uri}>
                        {level.label}
                      </option>
                    ))}
                  </Select>
                  <Icon className={styles.qualityChevron} name="chevron" size={14} />
                </label>
              ) : (
                <span className={styles.qualityFallback} aria-label="Calidad automática">
                  <Icon className={styles.qualityIcon} name="settings" size={16} />
                  <span>AUTO</span>
                </span>
              )}
              <Button
                variant="ghost"
                size="icon"
                className={styles.iconButton}
                aria-label={theater ? 'Salir del modo cine' : 'Activar modo cine'}
                aria-pressed={theater}
                onClick={() => setTheater((previous) => !previous)}
              >
                <Icon name="screen" size={19} />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className={styles.iconButton}
                aria-label={fullscreen ? 'Salir de pantalla completa' : 'Pantalla completa'}
                aria-pressed={fullscreen}
                onClick={() => void toggleFullscreen()}
              >
                <Icon name="fullscreen" size={20} />
              </Button>
            </div>
          </div>
          <span className="sr-only" role="status">
            {quality.native
              ? 'Calidad automática gestionada por el navegador.'
              : quality.active
                ? `Reproduciendo ${quality.levels.find((level) => level.uri === quality.active)?.label ?? 'señal'}`
                : 'Esperando calidad de reproducción…'}
          </span>
        </div>
      </div>
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
