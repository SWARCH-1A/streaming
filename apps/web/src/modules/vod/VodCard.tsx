import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { P2Vod } from '@contracts/p2';

import artwork from '@/public/images/signal-grid.svg';

import { duration } from './format';
import styles from './VodCard.module.css';

interface Props {
  vod: P2Vod;
  category?: string | undefined;
  tags?: readonly string[] | undefined;
  children?: ReactNode;
}

/** Visual común para la biblioteca propia, los canales y los resultados de Explorar. */
export function VodCard({ vod, category, tags = [], children }: Props) {
  const playable = vod.restriction === 'NONE';
  const state =
    vod.restriction === 'RETIRED'
      ? 'Retirada por administración'
      : vod.restriction === 'DELETED'
        ? 'Eliminada'
        : vod.processingState === 'READY'
          ? vod.publicationState === 'PUBLISHED'
            ? 'Publicada'
            : 'Borrador privado'
          : vod.processingState === 'FAILED'
            ? 'No se pudo validar'
            : 'Procesando';
  const badgeState =
    vod.restriction !== 'NONE'
      ? 'retired'
      : vod.processingState === 'READY' && vod.publicationState === 'PUBLISHED'
        ? 'published'
        : vod.processingState.toLowerCase();
  const cover = (
    <div className={styles.cover}>
      <img src={artwork} alt="" aria-hidden="true" />
      <span className={styles.coverLabel}>
        VAULT TV <span>·</span> GRABACIÓN
      </span>
      <span className={styles.coverTitle}>{vod.title}</span>
      {vod.processingState === 'READY' ? (
        <span className={styles.play} aria-hidden="true">
          ▶
        </span>
      ) : null}
      <span className={styles.duration}>{duration(vod.durationMs)}</span>
    </div>
  );

  return (
    <article className={styles.card}>
      {playable ? (
        <Link to={`/vod/${vod.vodId}`} className={styles.coverLink} aria-label={`Ver ${vod.title}`}>
          {cover}
        </Link>
      ) : (
        <div className={styles.coverLink} aria-hidden="true">
          {cover}
        </div>
      )}
      <div className={styles.cardBody}>
        <div className={styles.cardTitle}>
          <h3>{playable ? <Link to={`/vod/${vod.vodId}`}>{vod.title}</Link> : vod.title}</h3>
          <span className={styles.badge} data-state={badgeState}>
            {state}
          </span>
        </div>
        <p className={styles.meta}>
          <span>
            {new Date(vod.createdAtUtc).toLocaleDateString('es-CO', {
              dateStyle: 'medium',
              timeZone: 'UTC',
            })}
          </span>
          {category ? <span>{category}</span> : null}
          <span>
            {vod.renditions.length} {vod.renditions.length === 1 ? 'calidad' : 'calidades'}
          </span>
        </p>
        {vod.description ? <p className={styles.description}>{vod.description}</p> : null}
        {tags.length ? (
          <ul className={styles.tags} aria-label="Etiquetas">
            {tags.map((tag) => (
              <li key={tag}>{tag}</li>
            ))}
          </ul>
        ) : null}
        {children ? <div className={styles.management}>{children}</div> : null}
      </div>
    </article>
  );
}
