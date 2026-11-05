import { type ChangeEvent, useEffect, useRef, useState } from 'react';
import type { P2SubtitlePage, P2Vod } from '@contracts/p2';
import type Hls from 'hls.js';

import artwork from '@/public/images/signal-grid.svg';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Select } from '@/src/components/atoms/Select';
import { Notice } from '@/src/components/molecules/Notice';
// The live and VOD players deliberately share the same visual control contract.
// eslint-disable-next-line no-restricted-imports
import styles from '@/src/modules/streaming/components/Player.module.css';
import { request } from '@/src/shared/api/http';

function formatTime(seconds: number) {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00:00';
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const remainder = total % 60;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

export function VodPlayer({
  vod,
  onPosition,
}: {
  vod: P2Vod;
  onPosition?: (ms: number, grant: string) => void;
}) {
  return (
    <Player
      key={`${vod.vodId}:${vod.mappingVersion}`}
      vod={vod}
      {...(onPosition ? { onPosition } : {})}
    />
  );
}

function Player({
  vod,
  onPosition,
}: {
  vod: P2Vod;
  onPosition?: (ms: number, grant: string) => void;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const engine = useRef<Hls | null>(null);
  const permission = useRef('');
  const [levels, setLevels] = useState<{ index: number; label: string }[]>([]);
  const [selected, setSelected] = useState(-1);
  const [native, setNative] = useState(false);
  const [trackResult, setTrackResult] = useState({
    vodId: vod.vodId,
    mapping: vod.mappingVersion,
    items: [] as P2SubtitlePage['items'],
    nextCursor: null as string | null,
  });
  const currentTracks =
    trackResult.vodId === vod.vodId && trackResult.mapping === vod.mappingVersion;
  const tracks = currentTracks ? trackResult.items : [];
  const [track, setTrack] = useState('');
  const selectedTrack = useRef('');
  const failedTracks = useRef(new Set<string>());
  const [trackAttempt, setTrackAttempt] = useState(0);
  const [failure, setFailure] = useState('');
  const [trackError, setTrackError] = useState('');
  const [trackListError, setTrackListError] = useState('');
  const [trackPage, setTrackPage] = useState({
    vodId: vod.vodId,
    mapping: vod.mappingVersion,
    cursor: '',
    request: 0,
  });
  const nextTrackCursor = currentTracks ? trackResult.nextCursor : null;
  const [trackPending, setTrackPending] = useState(false);
  const trackCursor =
    trackPage.vodId === vod.vodId && trackPage.mapping === vod.mappingVersion
      ? trackPage.cursor
      : '';
  const [attempt, setAttempt] = useState(0);
  const [paused, setPaused] = useState(true);
  const [muted, setMuted] = useState(true);
  const [volume, setVolume] = useState(0.85);
  const [currentTime, setCurrentTime] = useState(0);
  const [mediaDuration, setMediaDuration] = useState(Math.max(0, vod.durationMs / 1000));
  const [theater, setTheater] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const viewport = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = video.current;
    if (!element || !vod.playbackPath) return;
    const controller = new AbortController();
    let instance: Hls | undefined;
    async function authorize() {
      const response = await fetch(vod.playbackPath, {
        credentials: 'include',
        cache: 'no-store',
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]),
      });
      if (!response.ok)
        throw new Error('El video no está disponible o no se pudo renovar su acceso.');
      permission.current = response.headers.get('X-VOD-Playback-Grant') ?? '';
      await response.text();
    }
    async function start() {
      try {
        await authorize();
        const { default: Library } = await import('hls.js');
        if (controller.signal.aborted) return;
        if (Library.isSupported()) {
          instance = new Library({
            xhrSetup: (xhr) => {
              xhr.withCredentials = true;
              if (permission.current)
                xhr.setRequestHeader('X-VOD-Playback-Grant', permission.current);
            },
          });
          engine.current = instance;
          instance.on(Library.Events.MANIFEST_PARSED, () => {
            setLevels(
              instance?.levels.map((level, index) => ({
                index,
                label: level.height ? `${level.height}p` : 'Original',
              })) ?? [],
            );
          });
          instance.on(Library.Events.ERROR, (_event, data) => {
            if (data.fatal) setFailure('No se pudo reproducir esta grabación.');
          });
          instance.attachMedia(element!);
          instance.loadSource(vod.playbackPath);
        } else if (element!.canPlayType('application/vnd.apple.mpegurl')) {
          setNative(true);
          element!.src = vod.playbackPath;
        } else setFailure('Este navegador no admite reproducción HLS.');
      } catch (reason) {
        if (!controller.signal.aborted)
          setFailure(reason instanceof Error ? reason.message : 'Video no disponible.');
      }
    }
    void start();
    const timer = setInterval(() => {
      void authorize().catch(() => {
        if (!controller.signal.aborted) {
          instance?.stopLoad();
          element.pause();
          setFailure('No se pudo renovar el acceso al video.');
        }
      });
    }, 45000);
    return () => {
      controller.abort();
      clearInterval(timer);
      instance?.destroy();
      engine.current = null;
      element.removeAttribute('src');
      element.load();
    };
  }, [attempt, vod.playbackPath]);

  useEffect(() => {
    const controller = new AbortController();
    void request<P2SubtitlePage>(
      `/api/vods/${vod.vodId}/subtitles${trackCursor ? `?cursor=${encodeURIComponent(trackCursor)}` : ''}`,
      {
        signal: controller.signal,
      },
    )
      .then(
        (page) => {
          if (controller.signal.aborted) return;
          const items = page.items.filter((item) => item.mappingVersion === vod.mappingVersion);
          setTrackResult((previous) => ({
            vodId: vod.vodId,
            mapping: vod.mappingVersion,
            items:
              trackCursor && previous.vodId === vod.vodId && previous.mapping === vod.mappingVersion
                ? [
                    ...previous.items.filter(
                      (item) => !items.some((next) => next.trackId === item.trackId),
                    ),
                    ...items,
                  ]
                : items,
            nextCursor: page.nextCursor,
          }));
          if (!trackCursor) setTrack('');
          setTrackListError('');
        },
        () => {
          if (!controller.signal.aborted)
            setTrackListError('No se pudieron consultar los subtítulos.');
        },
      )
      .finally(() => {
        if (!controller.signal.aborted) setTrackPending(false);
      });
    return () => controller.abort();
  }, [vod.vodId, vod.mappingVersion, trackCursor, trackPage.request]);

  useEffect(() => {
    function updateFullscreen() {
      setFullscreen(document.fullscreenElement === viewport.current);
    }
    document.addEventListener('fullscreenchange', updateFullscreen);
    return () => document.removeEventListener('fullscreenchange', updateFullscreen);
  }, []);

  useEffect(() => {
    if (!video.current) return;
    video.current.volume = volume;
    video.current.muted = muted;
  }, [muted, volume]);

  function togglePlayback() {
    const element = video.current;
    if (!element) return;
    if (element.paused) void element.play().catch(() => {});
    else element.pause();
  }

  function seek(event: ChangeEvent<HTMLInputElement>) {
    const element = video.current;
    const next = Number(event.currentTarget.value);
    if (!element || !Number.isFinite(mediaDuration) || mediaDuration <= 0) return;
    element.currentTime = (next / 100) * mediaDuration;
    setCurrentTime(element.currentTime);
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
      // Fullscreen can be rejected without affecting playback.
    }
  }

  const progress =
    mediaDuration > 0 ? Math.min(100, Math.max(0, (currentTime / mediaDuration) * 100)) : 0;

  function selectTrack(id: string) {
    selectedTrack.current = id;
    setTrack(id);
    setTrackError(
      failedTracks.current.has(id)
        ? 'No se pudo cargar la pista. Puedes reintentar o desactivar los subtítulos.'
        : '',
    );
    if (video.current)
      for (const item of Array.from(video.current.textTracks))
        item.mode = item.id === id ? 'showing' : 'disabled';
  }
  return (
    <section className={`${styles.player} stack`} aria-label="Reproductor de grabación">
      <div ref={viewport} className={`${styles.viewport} ${theater ? styles.theater : ''}`}>
        {/* eslint-disable-next-line jsx-a11y/media-has-caption -- Las pistas reales de SPEC-22 se cargan dinámicamente. */}
        <video
          ref={video}
          className={styles.media}
          controls={false}
          playsInline
          preload="metadata"
          poster={artwork}
          aria-label={vod.title}
          onPlay={() => setPaused(false)}
          onPause={() => setPaused(true)}
          onLoadedMetadata={(event) => {
            if (Number.isFinite(event.currentTarget.duration) && event.currentTarget.duration > 0)
              setMediaDuration(event.currentTarget.duration);
          }}
          onTimeUpdate={(event) => {
            const next = event.currentTarget.currentTime;
            setCurrentTime(next);
            if (Number.isFinite(event.currentTarget.duration) && event.currentTarget.duration > 0)
              setMediaDuration(event.currentTarget.duration);
            onPosition?.(Math.floor(next * 1000), permission.current);
          }}
          onVolumeChange={(event) => {
            setVolume(event.currentTarget.volume);
            setMuted(event.currentTarget.muted);
          }}
          onError={() => setFailure('No se pudo reproducir esta grabación.')}
          onLoadedData={() => selectTrack(track)}
        >
          {tracks.map((item) => (
            <track
              key={`${item.trackId}:${trackAttempt}`}
              id={item.trackId}
              kind="subtitles"
              srcLang={item.language}
              label={item.label}
              src={item.contentPath}
              default={item.trackId === track}
              ref={(element) => {
                if (!element) return;
                // Track load/error events do not bubble. Listen on the actual track element.
                const loaded = () => {
                  failedTracks.current.delete(item.trackId);
                  if (selectedTrack.current === item.trackId) selectTrack(item.trackId);
                };
                const failed = () => {
                  failedTracks.current.add(item.trackId);
                  if (selectedTrack.current === item.trackId)
                    setTrackError(
                      'No se pudo cargar la pista. Puedes reintentar o desactivar los subtítulos.',
                    );
                };
                element.addEventListener('load', loaded);
                element.addEventListener('error', failed);
                return () => {
                  element.removeEventListener('load', loaded);
                  element.removeEventListener('error', failed);
                };
              }}
            />
          ))}
        </video>
        <div className={styles.controls}>
          <div
            className={styles.rangeTrack}
            style={{
              background: `linear-gradient(to right, var(--color-live) 0 ${progress}%, rgb(255 255 255 / 22%) ${progress}% 100%)`,
            }}
          >
            <Input
              className={styles.rangeControl}
              type="range"
              min="0"
              max="100"
              step="0.1"
              value={progress}
              aria-label="Progreso de la grabación"
              onChange={seek}
            />
            <span
              className={styles.rangeThumb}
              style={{ left: `${progress}%` }}
              aria-hidden="true"
            />
          </div>
          <div className={styles.controlBar}>
            <div className={styles.controlGroup}>
              <Button
                variant="secondary"
                size="icon"
                className={styles.playerButton}
                aria-label={paused ? 'Reproducir grabación' : 'Pausar grabación'}
                onClick={togglePlayback}
              >
                <Icon name={paused ? 'play' : 'pause'} size={20} />
              </Button>
              <div className={styles.volumeControl}>
                <Button
                  variant="ghost"
                  size="icon"
                  className={styles.iconButton}
                  aria-label={muted ? 'Activar volumen' : 'Silenciar grabación'}
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
                    aria-label="Volumen de la grabación"
                    onChange={(event) => {
                      const next = Number(event.currentTarget.value);
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
                {formatTime(currentTime)} / {formatTime(mediaDuration)}
              </span>
            </div>
            <div className={styles.controlGroup}>
              {levels.length ? (
                <label className={styles.qualityControl}>
                  <Icon className={styles.qualityIcon} name="settings" size={16} />
                  <span className="sr-only">Calidad</span>
                  <Select
                    className={styles.qualitySelect}
                    aria-label="Calidad"
                    value={selected}
                    onChange={(event) => {
                      const value = Number(event.target.value);
                      // Switch at the next playable fragment and keep the current position.
                      if (engine.current) engine.current.nextLevel = value;
                      setSelected(value);
                    }}
                  >
                    <option value={-1}>Automática</option>
                    {levels.map((level) => (
                      <option key={level.index} value={level.index}>
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
        </div>
      </div>
      {failure ? (
        <Notice tone="error">
          {failure}{' '}
          <Button
            onClick={() => {
              setFailure('');
              setAttempt((value) => value + 1);
            }}
          >
            Reintentar reproducción
          </Button>
        </Notice>
      ) : null}
      {native ? <p>La calidad se adapta automáticamente en este navegador.</p> : null}
      <label>
        Subtítulos
        <Select value={track} onChange={(event) => selectTrack(event.target.value)}>
          <option value="">Desactivados</option>
          {tracks.map((item) => (
            <option key={item.trackId} value={item.trackId}>
              {item.label} · {item.language}
            </option>
          ))}
        </Select>
      </label>
      {nextTrackCursor ? (
        <Button
          disabled={trackPending}
          onClick={() => {
            setTrackPending(true);
            setTrackPage((previous) => ({
              vodId: vod.vodId,
              mapping: vod.mappingVersion,
              cursor: nextTrackCursor,
              request: previous.request + 1,
            }));
          }}
        >
          Cargar más pistas
        </Button>
      ) : null}
      {trackListError ? (
        <Notice tone="warning">
          {trackListError}{' '}
          <Button
            disabled={trackPending}
            onClick={() => {
              setTrackPending(true);
              setTrackPage((previous) => ({ ...previous, request: previous.request + 1 }));
            }}
          >
            Reintentar listado de subtítulos
          </Button>
        </Notice>
      ) : null}
      {trackError ? (
        <Notice tone="warning">
          {trackError}{' '}
          <Button
            onClick={() => {
              setTrackError('');
              failedTracks.current.delete(track);
              setTrackAttempt((value) => value + 1);
            }}
          >
            Reintentar subtítulos
          </Button>
        </Notice>
      ) : null}
    </section>
  );
}
