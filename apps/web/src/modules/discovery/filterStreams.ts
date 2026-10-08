import type { StreamCardData } from '@/src/components/organisms/StreamCard.types';
import { normalizeSearch } from '@/src/shared/format';

import type { DemoChannel, DiscoveryFilters } from './discovery.types';

export function filterStreams(streams: readonly StreamCardData[], filters: DiscoveryFilters) {
  const query = normalizeSearch(filters.query);
  return streams
    .filter(
      (stream) =>
        stream.availability === 'PLAYABLE' &&
        normalizeSearch(stream.title).includes(query) &&
        (!filters.categoryId || stream.category.id === filters.categoryId) &&
        (!filters.tagId || stream.tags.some((tag) => tag.id === filters.tagId)),
    )
    .toSorted(
      (a, b) =>
        b.viewers - a.viewers || b.startedAt.localeCompare(a.startedAt) || a.id.localeCompare(b.id),
    );
}
export function filterChannels(channels: readonly DemoChannel[], query: string) {
  const normalized = normalizeSearch(query);
  const rank = (channel: DemoChannel) => {
    const handle = normalizeSearch(channel.handle);
    const name = normalizeSearch(channel.name);
    return handle === normalized
      ? 0
      : name === normalized
        ? 1
        : handle.startsWith(normalized)
          ? 2
          : name.startsWith(normalized)
            ? 3
            : handle.includes(normalized)
              ? 4
              : 5;
  };
  return channels
    .filter(
      (channel) =>
        normalizeSearch(channel.handle).includes(normalized) ||
        normalizeSearch(channel.name).includes(normalized),
    )
    .toSorted((a, b) => rank(a) - rank(b) || a.handle.localeCompare(b.handle));
}
