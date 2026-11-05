import { useEffect, useId, useRef, useState } from 'react';
import type { Taxonomy } from '@contracts/p1';
import type { P2Accepted, P2OperationStatus, P2Vod, P2VodPage } from '@contracts/p2';

import { Button } from '@/src/components/atoms/Button';
import { Input } from '@/src/components/atoms/Input';
import { Select } from '@/src/components/atoms/Select';
import { Textarea } from '@/src/components/atoms/Textarea';
import { Notice } from '@/src/components/molecules/Notice';
import { errorMessage, request } from '@/src/shared/api/http';

import styles from './Library.module.css';
import { SubtitleManager } from './SubtitleManager';
import { VodCard } from './VodCard';

export function Library({
  initial,
  mine = false,
  channelId,
  initialError = '',
}: {
  initial?: P2VodPage;
  mine?: boolean;
  channelId?: string;
  initialError?: string;
}) {
  const fieldId = useId();
  const [page, setPage] = useState<P2VodPage | null>(initial ?? null);
  const [title, setTitle] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [tagId, setTagId] = useState('');
  const [taxonomy, setTaxonomy] = useState<Taxonomy | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    void request<Taxonomy>('/api/taxonomy', { signal: controller.signal }).then(
      setTaxonomy,
      () => undefined,
    );
    return () => controller.abort();
  }, []);
  const [error, setError] = useState(initialError);
  const [pending, setPending] = useState(false);
  const [revision, setRevision] = useState(0);
  const [operation, setOperation] = useState<P2Accepted | null>(null);
  const [status, setStatus] = useState<P2OperationStatus | null>(null);
  const base = mine ? '/api/me/vods' : '/api/vods';
  const query = new URLSearchParams({
    limit: '20',
    ...(channelId ? { channelId } : {}),
    ...(title ? { title } : {}),
    ...(categoryId ? { categoryId } : {}),
    ...(tagId ? { tagId } : {}),
  });
  const path = `${base}?${query.toString()}`;
  const initialPagePath = useRef(initial ? path : null);

  useEffect(() => {
    if (initialPagePath.current === path && revision === 0) return;
    initialPagePath.current = null;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      setPending(true);
      void request<P2VodPage>(path, { signal: controller.signal })
        .then(
          (data) => {
            setPage(data);
            setError('');
          },
          (reason: unknown) => {
            if (!controller.signal.aborted) setError(errorMessage(reason));
          },
        )
        .finally(() => {
          if (!controller.signal.aborted) setPending(false);
        });
    }, 250);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [path, revision]);

  useEffect(() => {
    if (!operation) return;
    const controller = new AbortController();
    const refresh = () => {
      void request<P2OperationStatus>(operation.statusUri, { signal: controller.signal }).then(
        setStatus,
        (reason: unknown) => {
          if (!controller.signal.aborted) setError(errorMessage(reason));
        },
      );
    };
    refresh();
    const timer = setInterval(refresh, 3000);
    return () => {
      clearInterval(timer);
      controller.abort();
    };
  }, [operation]);

  const keys = useRef(new Map<string, string>());
  async function remove(vod: P2Vod) {
    const fingerprint = `delete:${vod.vodId}:${vod.version}`;
    let key = keys.current.get(fingerprint);
    if (!key) {
      key = crypto.randomUUID();
      keys.current.set(fingerprint, key);
    }
    setPending(true);
    try {
      const result = await request<P2Accepted>(`/api/vods/${vod.vodId}`, {
        method: 'DELETE',
        coreMutation: true,
        headers: { 'Idempotency-Key': key, 'If-Match': `"${vod.version}"` },
      });
      setOperation(result);
      setRevision((value) => value + 1);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setPending(false);
    }
  }

  async function next() {
    if (!page?.nextCursor) return;
    setPending(true);
    try {
      const result = await request<P2VodPage>(
        `${path}&cursor=${encodeURIComponent(page.nextCursor)}`,
      );
      setPage({ ...result, items: [...page.items, ...result.items] });
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setPending(false);
    }
  }

  const categoryName = (id: string) => taxonomy?.categories.find((item) => item.id === id)?.name;
  const tagName = (id: string) => taxonomy?.tags.find((item) => item.id === id)?.name;

  return (
    <section
      className={styles.library}
      aria-label={mine ? 'Mis grabaciones' : 'Grabaciones del canal'}
    >
      <div className={styles.heading}>
        <div>
          <p className="eyebrow">{mine ? 'TU CONTENIDO' : 'REPRODUCCIONES'}</p>
          <h2>{mine ? 'Tu biblioteca' : 'Grabaciones del canal'}</h2>
          <p>
            {mine
              ? 'Organiza, edita y publica tus emisiones pasadas.'
              : 'Vuelve a ver las emisiones publicadas de este canal.'}
          </p>
        </div>
        {page ? (
          <span className={styles.count}>
            {page.items.length}{' '}
            {page.items.length === 1 ? 'grabación visible' : 'grabaciones visibles'}
          </span>
        ) : null}
      </div>

      <div className={styles.filters}>
        <label className={styles.search} htmlFor={fieldId}>
          Buscar por título
          <Input
            id={fieldId}
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            type="search"
            maxLength={140}
            placeholder="Ej. sesión de dibujo…"
          />
        </label>
        {taxonomy ? (
          <>
            <label htmlFor={`${fieldId}-category`}>
              Categoría
              <Select
                id={`${fieldId}-category`}
                value={categoryId}
                onChange={(event) => setCategoryId(event.target.value)}
              >
                <option value="">Todas</option>
                {taxonomy.categories.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </Select>
            </label>
            <label htmlFor={`${fieldId}-tag`}>
              Etiqueta
              <Select
                id={`${fieldId}-tag`}
                value={tagId}
                onChange={(event) => setTagId(event.target.value)}
              >
                <option value="">Todas</option>
                {taxonomy.tags.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </Select>
            </label>
          </>
        ) : null}
      </div>

      {error ? (
        <Notice tone="error">
          {error} <Button onClick={() => setRevision((value) => value + 1)}>Reintentar</Button>
        </Notice>
      ) : null}
      {pending || (!page && !error) ? <p role="status">Cargando grabaciones…</p> : null}
      {status ? (
        <Notice tone={status.status === 'FAILED' ? 'warning' : 'info'}>
          Limpieza de archivos:{' '}
          {status.status === 'COMPLETED'
            ? 'completada'
            : status.status === 'FAILED'
              ? 'pendiente de reintento'
              : 'en curso'}
          . La retirada del catálogo ya es efectiva.
        </Notice>
      ) : null}
      {!error && page?.items.length === 0 ? (
        <div className={styles.empty}>
          <span aria-hidden="true">▶</span>
          <h3>No hay grabaciones todavía</h3>
          <p>Cuando termines una emisión, la encontrarás aquí para revisarla y compartirla.</p>
        </div>
      ) : null}

      {page?.items.length ? (
        <div className={styles.grid}>
          {page.items.map((vod) => (
            <VodCard
              key={vod.vodId}
              vod={vod}
              category={categoryName(vod.categoryId)}
              tags={vod.tagIds.map((id) => tagName(id) ?? id)}
            >
              {mine ? (
                <>
                  {vod.restriction === 'NONE' ? (
                    <Editor
                      key={`${vod.vodId}:${vod.version}`}
                      vod={vod}
                      taxonomy={taxonomy}
                      onSaved={() => setRevision((value) => value + 1)}
                    />
                  ) : null}
                  {vod.processingState === 'READY' && vod.restriction === 'NONE' ? (
                    <SubtitleManager vod={vod} />
                  ) : null}
                  <details>
                    <summary>Eliminar grabación</summary>
                    <p>
                      Retira el acceso y programa la limpieza de video y pistas. Esta acción es
                      permanente.
                    </p>
                    <Button
                      variant="secondary"
                      disabled={pending}
                      onClick={() => {
                        void remove(vod);
                      }}
                    >
                      Eliminar definitivamente
                    </Button>
                  </details>
                </>
              ) : null}
            </VodCard>
          ))}
        </div>
      ) : null}
      {page?.nextCursor ? (
        <div className={styles.more}>
          <Button
            variant="secondary"
            disabled={pending}
            onClick={() => {
              void next();
            }}
          >
            {pending ? 'Cargando…' : 'Cargar más grabaciones'}
          </Button>
        </div>
      ) : null}
    </section>
  );
}

function Editor({
  vod,
  onSaved,
  taxonomy,
}: {
  vod: P2Vod;
  onSaved: () => void;
  taxonomy: Taxonomy | null;
}) {
  const fieldId = useId();
  const [title, setTitle] = useState(vod.title);
  const [description, setDescription] = useState(vod.description);
  const [categoryId, setCategoryId] = useState(vod.categoryId);
  const [tagIds, setTagIds] = useState(vod.tagIds);
  const keys = useRef(new Map<string, string>());
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  async function action(publish: boolean) {
    const body = {
      expectedVersion: vod.version,
      ...(!publish
        ? {
            title,
            description,
            ...(categoryId !== vod.categoryId ? { categoryId } : {}),
            ...(JSON.stringify(tagIds) !== JSON.stringify(vod.tagIds) ? { tagIds } : {}),
          }
        : {}),
    };
    const fingerprint = JSON.stringify({ publish, body });
    let key = keys.current.get(fingerprint);
    if (!key) {
      key = crypto.randomUUID();
      keys.current.set(fingerprint, key);
    }
    setPending(true);
    try {
      await request<P2Vod>(`/api/vods/${vod.vodId}${publish ? '/publication' : ''}`, {
        method: publish ? 'POST' : 'PATCH',
        coreMutation: true,
        headers: { 'Idempotency-Key': key },
        body,
      });
      setError('');
      onSaved();
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setPending(false);
    }
  }
  return (
    <details>
      <summary>Editar y publicar</summary>
      <form
        className="stack"
        onSubmit={(event) => {
          event.preventDefault();
          void action(false);
        }}
      >
        <label htmlFor={`${fieldId}-title`}>
          Título
          <Input
            id={`${fieldId}-title`}
            required
            maxLength={140}
            value={title}
            onChange={(event) => setTitle(event.target.value)}
          />
        </label>
        <label htmlFor={`${fieldId}-description`}>
          Descripción
          <Textarea
            id={`${fieldId}-description`}
            maxLength={4000}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </label>
        {taxonomy ? (
          <>
            <label htmlFor={`${fieldId}-category`}>
              Categoría
              <Select
                id={`${fieldId}-category`}
                value={categoryId}
                onChange={(event) => setCategoryId(event.target.value)}
              >
                {!taxonomy.categories.some((item) => item.id === categoryId) ? (
                  <option value={categoryId}>Categoría actual</option>
                ) : null}
                {taxonomy.categories.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </Select>
            </label>
            <fieldset>
              <legend>Etiquetas (máximo 5)</legend>
              {taxonomy.tags.map((item) => (
                <label key={item.id}>
                  <Input
                    type="checkbox"
                    checked={tagIds.includes(item.id)}
                    disabled={!tagIds.includes(item.id) && tagIds.length >= 5}
                    onChange={(event) =>
                      setTagIds(
                        event.target.checked
                          ? [...tagIds, item.id]
                          : tagIds.filter((id) => id !== item.id),
                      )
                    }
                  />
                  {item.name}
                </label>
              ))}
            </fieldset>
          </>
        ) : null}
        {error ? <Notice tone="error">{error}</Notice> : null}
        <Button type="submit" disabled={pending}>
          Guardar cambios
        </Button>
        {vod.processingState === 'READY' && vod.publicationState === 'DRAFT' ? (
          <Button
            disabled={pending}
            onClick={() => {
              void action(true);
            }}
          >
            Publicar grabación
          </Button>
        ) : null}
      </form>
    </details>
  );
}
