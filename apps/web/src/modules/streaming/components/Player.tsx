import { useEffect, useRef, useState } from 'react';

import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
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
      <div ref={viewport} className={styles.viewport} aria-label={`Vista previa de ${title}`}>
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
          <div className="row">
            <Button
              variant="secondary"
              size="icon"
              aria-label={paused ? 'Reanudar vista previa' : 'Pausar vista previa'}
              aria-pressed={paused}
              disabled={!playing}
              onClick={() => setPaused((previous) => !previous)}
            >
              <Icon name={paused ? 'play' : 'pause'} />
            </Button>
            <Badge tone="live" dot>
              DEMO
            </Badge>
            <Button
              variant="ghost"
              size="icon"
              aria-label={
                muted ? 'Activar audio de demostración' : 'Silenciar audio de demostración'
              }
              aria-pressed={muted}
              disabled={!playing}
              onClick={() => setMuted((previous) => !previous)}
            >
              <Icon name={muted ? 'muted' : 'volume'} />
            </Button>
          </div>
          <div className="row">
            <span className={styles.quality}>1080p · 60 fps</span>
            <Button
              variant="secondary"
              size="icon"
              aria-label={expanded ? 'Salir del modo cine' : 'Activar modo cine'}
              aria-pressed={expanded}
              onClick={() => {
                void toggleFullscreen();
              }}
            >
              <Icon name="fullscreen" />
            </Button>
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
