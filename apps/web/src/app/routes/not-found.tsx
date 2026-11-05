import { lazy, Suspense } from 'react';
import { data } from 'react-router';

import { NotFoundPage } from '@/src/shell/routes/NotFoundPage';

const DevelopmentPages = import.meta.env.DEV
  ? lazy(() => import('@/src/shell/design-system/DevelopmentPages'))
  : null;

// Real 404 status in production HTML; development keeps serving the design-system pages here.
export function loader() {
  return data(null, { status: import.meta.env.DEV ? 200 : 404 });
}

export default function NotFound() {
  return DevelopmentPages ? (
    <Suspense fallback={<p role="status">Cargando biblioteca de desarrollo…</p>}>
      <DevelopmentPages />
    </Suspense>
  ) : (
    <NotFoundPage />
  );
}
