import { useState } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { Skeleton } from '@/src/components/atoms/Skeleton';
import { EmptyState } from '@/src/components/molecules/EmptyState';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { SectionHeading } from '@/src/components/molecules/SectionHeading';

import styles from './CatalogStates.module.css';

export function CatalogStates() {
  const [retried, setRetried] = useState(false);
  return (
    <section>
      <SectionHeading title="Carga, ausencia de datos y errores" />
      <div className={styles.grid}>
        <Panel className="stack" aria-busy="true" aria-label="Ejemplo de catálogo cargando">
          <p className="eyebrow">Cargando</p>
          <Skeleton variant="media" />
          <Skeleton />
          <Skeleton />
          <p className="muted">Buscando transmisiones…</p>
        </Panel>
        <EmptyState
          title="Sin transmisiones activas"
          description="Explora otra categoría para encontrar un directo."
          icon="broadcast"
        />
        <Panel className="stack">
          <p className="eyebrow">Error</p>
          <Notice tone={retried ? 'success' : 'error'}>
            {retried
              ? 'Vista de ejemplo recuperada.'
              : 'No se pudo cargar el catálogo. Vuelve a intentarlo.'}
          </Notice>
          <Button variant="secondary" onClick={() => setRetried((previous) => !previous)}>
            {retried ? 'Simular error' : 'Reintentar ejemplo'}
          </Button>
        </Panel>
      </div>
    </section>
  );
}
