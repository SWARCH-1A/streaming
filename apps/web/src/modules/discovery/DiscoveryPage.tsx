import { Link, useSearchParams } from 'react-router';
import queries from '@contracts/graphql-queries.json';
import type { GqlChannelConnection, GqlStreamConnection, GraphQLResponse } from '@contracts/p1';

import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Select } from '@/src/components/atoms/Select';
import { EmptyState } from '@/src/components/molecules/EmptyState';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { useCatalog } from '@/src/modules/taxonomy/entry';
import { request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';

import styles from './DiscoveryPage.module.css';

interface Results {
  streams: GqlStreamConnection;
  channels: GqlChannelConnection | null;
}
export function DiscoveryPage({ search = false }: { search?: boolean }) {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '',
    categoryId = params.get('category') ?? '',
    tagId = params.get('tag') ?? '';
  const cursor = params.get('cursor'),
    channelCursor = params.get('channelCursor');
  const catalog = useCatalog();
  const resource = useResource(
    JSON.stringify([q, categoryId, tagId, cursor, channelCursor, search]),
    async (signal) => {
      async function query(name: string, variables: Record<string, unknown>) {
        const operation = queries.find((item) => item.name === name);
        if (!operation) throw new Error('Consulta no generada.');
        const response = await request<GraphQLResponse>('/api/discovery/graphql', {
          method: 'POST',
          body: { query: operation.query, variables },
          signal,
        });
        if (response.errors?.length)
          throw new Error(
            response.errors
              .map(
                (item) =>
                  `${item.message} (${item.extensions.code} · ${item.extensions.requestId})`,
              )
              .join(' '),
          );
        if (!response.data) throw new Error('Discovery no devolvió datos.');
        return response.data;
      }
      const [streams, channels] = await Promise.all([
        query('LiveStreams', {
          q,
          categoryId: categoryId || null,
          tagId: tagId || null,
          cursor,
          limit: 20,
        }),
        search ? query('Channels', { q, cursor: channelCursor, limit: 20 }) : Promise.resolve(null),
      ]);
      return {
        streams: streams.streams as GqlStreamConnection,
        channels: channels?.channels as GqlChannelConnection | null,
      } satisfies Results;
    },
  );
  function update(key: string, value: string) {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.delete('cursor');
      next.delete('channelCursor');
      if (value) next.set(key, value);
      else next.delete(key);
      return next;
    });
  }
  const data = resource.data;
  return (
    <div className={styles.page}>
      <div>
        <p className="eyebrow">Encuentra tu comunidad</p>
        <h1>
          {q
            ? `Resultados para “${q}”`
            : search
              ? 'Buscar canales y transmisiones'
              : 'Explorar transmisiones'}
        </h1>
      </div>
      <div className="row">
        <FormField id="category-filter" label="Categoría">
          <Select
            id="category-filter"
            value={categoryId}
            disabled={!catalog.data}
            onChange={(event) => update('category', event.target.value)}
          >
            <option value="">Todas las categorías</option>
            {categoryId && !catalog.data?.categories.some((item) => item.id === categoryId) ? (
              <option value={categoryId}>Categoría no disponible</option>
            ) : null}
            {catalog.data?.categories.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField id="tag-filter" label="Etiqueta">
          <Select
            id="tag-filter"
            value={tagId}
            disabled={!catalog.data}
            onChange={(event) => update('tag', event.target.value)}
          >
            <option value="">Todas las etiquetas</option>
            {tagId && !catalog.data?.tags.some((item) => item.id === tagId) ? (
              <option value={tagId}>Etiqueta no disponible</option>
            ) : null}
            {catalog.data?.tags.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </Select>
        </FormField>
        <Button variant="secondary" onClick={() => setParams({})}>
          Limpiar filtros
        </Button>
      </div>
      {catalog.error ? <Notice tone="error">Catálogo: {catalog.error}</Notice> : null}
      {resource.loading ? (
        <p role="status">Cargando resultados…</p>
      ) : resource.error ? (
        <Notice tone="error">{resource.error}</Notice>
      ) : null}
      {search && data?.channels ? (
        <section>
          <h2>Canales encontrados</h2>
          {!data.channels.statusFresh ? (
            <Notice tone="warning">El estado de emisiones no está actualizado.</Notice>
          ) : null}
          <div className={styles.channels}>
            {data.channels.items.map((item) => (
              <Panel key={item.channelId}>
                <Avatar
                  name={item.displayName}
                  {...(item.avatarUri ? { src: item.avatarUri } : {})}
                />
                <Link to={`/channels/${item.handle}`}>
                  {item.displayName} (@{item.handle})
                </Link>
                <Badge>{item.statusFresh ? item.availability : 'UNKNOWN'}</Badge>
              </Panel>
            ))}
          </div>
          {!data.channels.items.length ? <p>No hay canales para esta búsqueda.</p> : null}
          {data.channels.nextCursor ? (
            <Button
              onClick={() => {
                setParams((previous) => {
                  const next = new URLSearchParams(previous);
                  next.set('channelCursor', data.channels?.nextCursor ?? '');
                  return next;
                });
              }}
            >
              Más canales
            </Button>
          ) : null}
        </section>
      ) : null}
      {data?.streams ? (
        <section>
          <h2>Transmisiones en vivo</h2>
          <p role="status">{data.streams.items.length} transmisiones · Más populares</p>
          {!data.streams.statusFresh ? (
            <Notice tone="warning">El estado de emisiones no está actualizado.</Notice>
          ) : null}
          <div className={styles.channels}>
            {data.streams.items.map((item) => (
              <Panel key={item.streamId} className="stack">
                <Badge tone="live">EN VIVO</Badge>
                <Link to={`/watch/${item.streamId}`}>
                  <h3>{item.title}</h3>
                </Link>
                <Link to={`/channels/${item.channel.handle}`}>{item.channel.displayName}</Link>
                <p>
                  {item.category.name} · {item.tags.map((tag) => tag.name).join(' · ')}
                </p>
                <p>
                  {item.viewerCountFresh
                    ? `${item.viewerCount} espectadores`
                    : 'Conteo no actualizado'}
                </p>
              </Panel>
            ))}
          </div>
          {!data.streams.items.length ? (
            <EmptyState
              title="No encontramos transmisiones"
              description="Prueba otra búsqueda o elimina los filtros."
            />
          ) : null}
          {data.streams.nextCursor ? (
            <Button
              onClick={() => {
                setParams((previous) => {
                  const next = new URLSearchParams(previous);
                  next.set('cursor', data.streams.nextCursor ?? '');
                  return next;
                });
              }}
            >
              Más transmisiones
            </Button>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
