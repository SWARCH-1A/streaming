import { useEffect, useRef, useState } from 'react';

import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Notice } from '@/src/components/molecules/Notice';
import type { BroadcastState } from '@/src/modules/streaming/streaming.types';

import styles from './Player.module.css';

interface PlayerProps {
  poster: string;
  title: string;
  state?: BroadcastState;
  onRetry?: () => void;
}
const messages: Record<Exclude<BroadcastState, 'LIVE'>, { title: string; detail: string }> = {
  OFFLINE: { title: 'El canal está offline', detail: 'Vuelve cuando comience una nueva emisión.' },
  PREPARING: { title: 'Preparando la emisión', detail: 'Esperando una señal reproducible.' },
  RECONNECT_GRACE: {
    title: 'Reconectando la señal',
    detail: 'El emisor perdió la conexión temporalmente.',
  },
  ENDED: { title: 'La emisión ha finalizado', detail: 'Gracias por acompañar a la comunidad.' },
  ERROR: {
    title: 'No se pudo cargar la reproducción',
    detail: 'Vuelve a intentarlo para recuperar la vista de demostración.',
  },
};

export function Player({ poster, title, state = 'LIVE', onRetry }: PlayerProps) {
  const [paused, setPaused] = useState(false);
  const [muted, setMuted] = useState(true);
  const [volume, setVolume] = useState(0.85);
  const [theater, setTheater] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [fullscreenError, setFullscreenError] = useState(false);
  const viewport = useRef<HTMLDivElement>(null);
  useEffect(() => {
    function updateFullscreen() {
      setExpanded(document.fullscreenElement === viewport.current);
    }
    document.addEventListener('fullscreenchange', updateFullscreen);
    return () => document.removeEventListener('fullscreenchange', updateFullscreen);
  }, []);
  async function toggleFullscreen() {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (viewport.current?.requestFullscreen) await viewport.current.requestFullscreen();
      else {
        setFullscreenError(true);
        return;
      }
      setFullscreenError(false);
    } catch {
      setFullscreenError(true);
    }
  }
  const playing = state === 'LIVE';
  return (
    <div className={styles.player}>
      <div
        ref={viewport}
        className={`${styles.viewport} ${theater ? styles.theater : ''}`}
        aria-label={`Vista previa de ${title}`}
      >
        <img src={poster} alt="" className={!playing ? styles.dimmed : ''} />
        <div className={styles.top}>
          <Badge
            tone={state === 'LIVE' ? 'live' : state === 'RECONNECT_GRACE' ? 'warning' : 'neutral'}
            dot
          >
            {state === 'LIVE'
              ? 'EN VIVO · DEMO'
              : state === 'RECONNECT_GRACE'
                ? 'RECONECTANDO'
                : state === 'PREPARING'
                  ? 'PREPARANDO'
                  : 'OFFLINE'}
          </Badge>
          <Badge>Vista previa estática</Badge>
        </div>
        {!playing ? (
          <div className={styles.overlay} role="status">
            <Icon name={state === 'ERROR' ? 'alert' : 'broadcast'} size={36} />
            <h2>{messages[state].title}</h2>
            <p>{messages[state].detail}</p>
            {state === 'ERROR' && onRetry ? (
              <Button onClick={onRetry}>
                <Icon name="refresh" />
                Reintentar
              </Button>
            ) : null}
          </div>
        ) : paused ? (
          <div className={styles.paused} role="status">
            Vista previa en pausa
          </div>
        ) : null}
        <div className={styles.controls}>
          <div className={styles.controlBar}>
            <div className={styles.controlGroup}>
              <Button
                variant="secondary"
                size="icon"
                className={styles.playerButton}
                aria-label={paused ? 'Reanudar vista previa' : 'Pausar vista previa'}
                aria-pressed={paused}
                disabled={!playing}
                onClick={() => setPaused((previous) => !previous)}
              >
                <Icon name={paused ? 'play' : 'pause'} size={20} />
              </Button>
              <div className={styles.volumeControl}>
                <Button
                  variant="ghost"
                  size="icon"
                  className={styles.iconButton}
                  aria-label={
                    muted ? 'Activar audio de demostración' : 'Silenciar audio de demostración'
                  }
                  aria-pressed={muted}
                  disabled={!playing}
                  onClick={() => setMuted((previous) => !previous)}
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
                    aria-label="Volumen de la vista previa"
                    disabled={!playing}
                    onChange={(event) => {
                      const next = Number(event.target.value);
                      setVolume(next);
                      setMuted(next === 0);
                    }}
                  />
                  <span
                    className={styles.rangeThumb}
                    style={{ left: `${(muted ? 0 : volume) * 100}%` }}
                    aria-hidden="true"
                  />
                </div>
              </div>
              <span className={styles.timecode}>03:42:19</span>
            </div>
            <div className={styles.controlGroup}>
              <span className={styles.qualityFallback} aria-label="Calidad 1080p · 60 fps">
                <Icon className={styles.qualityIcon} name="settings" size={16} />
                <span>1080p · 60 fps</span>
              </span>
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
                aria-label={expanded ? 'Salir de pantalla completa' : 'Pantalla completa'}
                aria-pressed={expanded}
                onClick={() => {
                  void toggleFullscreen();
                }}
              >
                <Icon name="fullscreen" size={20} />
              </Button>
            </div>
          </div>
        </div>
      </div>
      {fullscreenError ? (
        <Notice tone="warning">La pantalla completa no está disponible en este navegador.</Notice>
      ) : null}
      <Notice>
        Reproductor de demostración: la imagen es estática y los controles representan estados
        locales.
      </Notice>
    </div>
  );
}
