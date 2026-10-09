import type { Taxonomy } from '@contracts/p1';

import { request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';

export function useCatalog() {
  return useResource('taxonomy', (signal) => request<Taxonomy>('/api/taxonomy', { signal }), 60000);
}
