import { useEffect, useRef, useState } from 'react';
import type { P2SubtitlePage, P2Vod } from '@contracts/p2';
import type Hls from 'hls.js';

import { Button } from '@/src/components/atoms/Button';
import { Select } from '@/src/components/atoms/Select';
import { Notice } from '@/src/components/molecules/Notice';
import { request } from '@/src/shared/api/http';

export function VodPlayer({
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
  const [failure, setFailure] = useState('');
  const [trackError, setTrackError] = useState('');
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
          instance = new Library();
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
          setTrackError('');
        },
        () => {
          if (!controller.signal.aborted) setTrackError('No se pudieron consultar los subtítulos.');
        },
      )
      .finally(() => {
        if (!controller.signal.aborted) setTrackPending(false);
      });
    return () => controller.abort();
  }, [vod.vodId, vod.mappingVersion, trackCursor, trackPage.request]);

  function selectTrack(id: string) {
    setTrack(id);
    setTrackError('');
    if (video.current)
      for (const item of Array.from(video.current.textTracks))
        item.mode = item.id === id ? 'showing' : 'disabled';
  }
  return (
    <section className="stack" aria-label="Reproductor de grabación">
      {/* VOD uses seek and native media controls, without live viewer leases. */}
      {/* eslint-disable-next-line jsx-a11y/media-has-caption -- Las pistas reales de SPEC-22 se cargan dinámicamente. */}
      <video
        ref={video}
        controls
        playsInline
        preload="metadata"
        aria-label={vod.title}
        style={{ width: '100%', background: '#000', aspectRatio: '16/9' }}
        onTimeUpdate={(event) =>
          onPosition?.(Math.floor(event.currentTarget.currentTime * 1000), permission.current)
        }
        onLoadedData={() => selectTrack(track)}
      >
        {tracks.map((item) => (
          <track
            key={item.trackId}
            id={item.trackId}
            kind="subtitles"
            srcLang={item.language}
            label={item.label}
            src={item.contentPath}
            default={item.trackId === track}
            onError={() =>
              setTrackError('No se pudo cargar la pista. Puedes desactivar los subtítulos.')
            }
          />
        ))}
      </video>
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
      {levels.length ? (
        <label>
          Calidad
          <Select
            value={selected}
            onChange={(event) => {
              const value = Number(event.target.value);
              if (engine.current) engine.current.loadLevel = value;
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
        </label>
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
      {trackError ? <Notice tone="warning">{trackError}</Notice> : null}
    </section>
  );
}
