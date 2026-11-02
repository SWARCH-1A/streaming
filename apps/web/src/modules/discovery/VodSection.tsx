import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import type { Taxonomy } from '@contracts/p1';
import type { P2ExploreScreen, P2SectionError, P2Vod } from '@contracts/p2';

import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { EmptyState } from '@/src/components/molecules/EmptyState';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { errorMessage, request } from '@/src/shared/api/http';

import styles from './DiscoveryPage.module.css';

const PAGE = 12;
const MAX_TITLE = 140;

interface Loaded {
  items: P2Vod[];
  nextCursor: string | null;
  failure: string;
}

/** Spanish text for a section the Gateway could not fill; the live section never depends on it. */
function sectionFailure(status: 'UNAVAILABLE' | 'DENIED', error: P2SectionError) {
  const reference = `${error.code} · ${error.requestId}`;
  return status === 'DENIED'
    ? `No tienes acceso a las grabaciones. (${reference})`
    : `Las grabaciones no están disponibles ahora; las transmisiones en vivo siguen funcionando. (${reference})`;
}

function exploreQuery(q: string, categoryId: string, tagId: string, cursor?: string) {
  const params = new URLSearchParams({ limit: String(PAGE) });
  if (q) params.set('title', q);
  if (categoryId) params.set('categoryId', categoryId);
  if (tagId) params.set('tagId', tagId);
  if (cursor) params.set('cursor', cursor);
  return `/api/screens/explore?${params.toString()}`;
}

type Page = { items: P2Vod[]; nextCursor: string | null };
/** The section's page, or the reason it could not be filled. */
function outcome(section: P2ExploreScreen['vods']): { page: Page } | { failure: string } {
  if (section.status === 'AVAILABLE' || section.status === 'EMPTY')
    return { page: section.data ?? { items: [], nextCursor: null } };
  return { failure: sectionFailure(section.status, section.error) };
}

function minutes(ms: number) {
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/**
 * Recorded-content search (RF-074/075). It reads the VOD section of the Gateway's explore screen with
 * the same exact category/tag IDs as the live filters. VOD has its own cursor and its own failure state:
 * if it fails only this section reports it, and live results are unaffected (and the other way round).
 * The parent keys this component by the filters, so a new search starts from a clean state.
 */
export function VodSection({
  q,
  categoryId,
  tagId,
  taxonomy,
}: {
  q: string;
  categoryId: string;
  tagId: string;
  taxonomy: Taxonomy | null;
}) {
  const tooLong = [...q].length > MAX_TITLE;
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [revision, setRevision] = useState(0);
  const [more, setMore] = useState<{ pending: boolean; error: string }>({
    pending: false,
    error: '',
  });

  useEffect(() => {
    if (tooLong) return;
    const controller = new AbortController();
    request<P2ExploreScreen>(exploreQuery(q, categoryId, tagId), {
      signal: controller.signal,
    }).then(
      (screen) => {
        const result = outcome(screen.vods);
        setLoaded(
          'failure' in result
            ? { items: [], nextCursor: null, failure: result.failure }
            : { ...result.page, failure: '' },
        );
      },
      (reason: unknown) => {
        if (!controller.signal.aborted)
          setLoaded({ items: [], nextCursor: null, failure: errorMessage(reason) });
      },
    );
    return () => controller.abort();
  }, [q, categoryId, tagId, revision, tooLong]);

  async function loadMore(cursor: string) {
    setMore({ pending: true, error: '' });
    try {
      const result = outcome(
        (await request<P2ExploreScreen>(exploreQuery(q, categoryId, tagId, cursor))).vods,
      );
      if ('failure' in result) {
        setMore({ pending: false, error: result.failure });
        return;
      }
      const { page } = result;
      setLoaded((previous) =>
        previous
          ? { ...previous, items: [...previous.items, ...page.items], nextCursor: page.nextCursor }
          : previous,
      );
      setMore({ pending: false, error: '' });
    } catch (reason) {
      setMore({ pending: false, error: errorMessage(reason) });
    }
  }

  const view = loaded;
  const category = (id: string) => taxonomy?.categories.find((item) => item.id === id)?.name;
  const tag = (id: string) => taxonomy?.tags.find((item) => item.id === id)?.name;

  return (
    <section aria-labelledby="vod-results">
      <h2 id="vod-results">Grabaciones</h2>
      {tooLong ? (
        <Notice tone="warning">
          La búsqueda tiene más de {MAX_TITLE} caracteres: acórtala para buscar grabaciones.
        </Notice>
      ) : !view ? (
        <p role="status">Cargando grabaciones…</p>
      ) : view.failure ? (
        <Notice tone="error">
          {view.failure}{' '}
          <Button
            variant="secondary"
            onClick={() => {
              setLoaded(null);
              setRevision((value) => value + 1);
            }}
          >
            Reintentar grabaciones
          </Button>
        </Notice>
      ) : (
        <>
          <p role="status">
            {view.items.length} {view.items.length === 1 ? 'grabación' : 'grabaciones'}
            {view.nextCursor ? ' (hay más)' : ''}
          </p>
          {view.items.length ? (
            <div className={styles.vods}>
              {view.items.map((vod) => (
                <Panel key={vod.vodId} className="stack">
                  <h3>
                    <Link to={`/vod/${vod.vodId}`}>{vod.title}</Link>
                  </h3>
                  <p className="muted">
                    {minutes(vod.durationMs)} ·{' '}
                    {new Date(vod.createdAtUtc).toLocaleDateString('es-CO', {
                      dateStyle: 'medium',
                      timeZone: 'UTC',
                    })}
                  </p>
                  <p>
                    <Badge>{category(vod.categoryId) ?? 'Categoría'}</Badge>
                    {vod.tagIds.map((id) => (
                      <span key={id}> {tag(id) ?? 'Etiqueta'}</span>
                    ))}
                  </p>
                </Panel>
              ))}
            </div>
          ) : (
            <EmptyState
              title="No encontramos grabaciones"
              description="Prueba otra búsqueda o elimina los filtros."
              icon="screen"
            />
          )}
          {more.error ? <Notice tone="error">{more.error}</Notice> : null}
          {view.nextCursor ? (
            <Button
              disabled={more.pending}
              onClick={() => {
                void loadMore(view.nextCursor ?? '');
              }}
            >
              Cargar más grabaciones
            </Button>
          ) : null}
        </>
      )}
    </section>
  );
}
