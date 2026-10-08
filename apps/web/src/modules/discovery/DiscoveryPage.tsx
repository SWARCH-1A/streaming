import { useSearchParams } from 'react-router';

import { Button } from '@/src/components/atoms/Button';
import { Select } from '@/src/components/atoms/Select';
import { EmptyState } from '@/src/components/molecules/EmptyState';
import { FormField } from '@/src/components/molecules/FormField';
import { SectionHeading } from '@/src/components/molecules/SectionHeading';
import { CategoryRibbon } from '@/src/modules/discovery/components/CategoryRibbon';
import { ChannelCard } from '@/src/modules/discovery/components/ChannelCard';
import { FeaturedStream } from '@/src/modules/discovery/components/FeaturedStream';
import { StreamGrid } from '@/src/modules/discovery/components/StreamGrid';
import { demoChannels, demoStreams } from '@/src/modules/discovery/mock/streams';
import { demoCatalog } from '@/src/modules/taxonomy/entry';

import styles from './DiscoveryPage.module.css';
import { filterChannels, filterStreams } from './filterStreams';

export function DiscoveryPage({ search = false }: { search?: boolean }) {
  const [params, setParams] = useSearchParams();
  const filters = {
    query: params.get('q') ?? '',
    categoryId: params.get('category') ?? '',
    tagId: params.get('tag') ?? '',
  };
  const streams = filterStreams(demoStreams, filters);
  const channels = search ? filterChannels(demoChannels, filters.query) : [];
  const featured = demoStreams[0];
  const invalid =
    (!!filters.categoryId &&
      !demoCatalog.categories.some((value) => value.id === filters.categoryId)) ||
    (!!filters.tagId && !demoCatalog.tags.some((value) => value.id === filters.tagId));
  function update(key: string, value: string) {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      if (value) next.set(key, value);
      else next.delete(key);
      return next;
    });
  }
  return (
    <div className={styles.page}>
      {!search && featured ? (
        <FeaturedStream stream={featured} />
      ) : (
        <div>
          <p className="eyebrow">Encuentra tu comunidad</p>
          <h1>
            {filters.query
              ? `Resultados para “${filters.query}”`
              : 'Buscar canales y transmisiones'}
          </h1>
        </div>
      )}
      {search ? (
        <section>
          <SectionHeading title={`Canales encontrados (${channels.length})`} />
          <div className={styles.channels}>
            {channels.map((channel) => (
              <ChannelCard key={channel.handle} channel={channel} />
            ))}
          </div>
          {channels.length === 0 ? (
            <p className="muted">No hay canales con ese nombre. Prueba con otro handle.</p>
          ) : null}
        </section>
      ) : (
        <section>
          <SectionHeading eyebrow="Exploración temática" title="Categorías destacadas" />
          <CategoryRibbon
            catalog={demoCatalog}
            selected={filters.categoryId}
            onSelect={(id) => update('category', id)}
          />
        </section>
      )}
      <section aria-labelledby="streams-heading">
        <div className={styles.filters}>
          <div>
            <p className="eyebrow">Parrilla global</p>
            <h2 id="streams-heading">
              {search ? 'Directos para tu búsqueda' : 'Transmisiones que marcan tendencia'}
            </h2>
          </div>
          <div className="row">
            <FormField id="category-filter" label="Categoría">
              <Select
                id="category-filter"
                value={filters.categoryId}
                onChange={(event) => update('category', event.target.value)}
              >
                <option value="">Todas las categorías</option>
                {demoCatalog.categories.map((value) => (
                  <option key={value.id} value={value.id}>
                    {value.name}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField id="tag-filter" label="Etiqueta">
              <Select
                id="tag-filter"
                value={filters.tagId}
                onChange={(event) => update('tag', event.target.value)}
              >
                <option value="">Todas las etiquetas</option>
                {demoCatalog.tags.map((value) => (
                  <option key={value.id} value={value.id}>
                    {value.name}
                  </option>
                ))}
              </Select>
            </FormField>
          </div>
        </div>
        <p className={styles.results} role="status">
          {streams.length} transmisiones · Más populares
        </p>
        {invalid ? (
          <EmptyState
            title="Filtro no disponible"
            description="Selecciona una categoría o etiqueta del catálogo."
            action={<Button onClick={() => setParams({})}>Limpiar filtros</Button>}
          />
        ) : streams.length ? (
          <StreamGrid streams={streams} />
        ) : (
          <EmptyState
            title="No encontramos transmisiones"
            description="Prueba otra búsqueda o elimina los filtros para descubrir más directos."
            action={<Button onClick={() => setParams({})}>Limpiar filtros</Button>}
          />
        )}
      </section>
    </div>
  );
}
