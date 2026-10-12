import type { StreamCardData } from '@/src/components/organisms/StreamCard.types';

export interface DemoChannel {
  handle: string;
  name: string;
  avatar: string;
  status: 'LIVE' | 'OFFLINE';
  description: string;
  stream: StreamCardData | null;
}
export interface DiscoveryFilters {
  query: string;
  categoryId: string;
  tagId: string;
}
export type CatalogState = 'ready' | 'loading' | 'error';
