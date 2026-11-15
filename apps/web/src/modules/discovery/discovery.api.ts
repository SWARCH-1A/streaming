import queries from '@contracts/graphql-queries.json';
import type { GqlChannelConnection, GqlStreamConnection, GraphQLResponse } from '@contracts/p1';
import type { P2ExploreScreen } from '@contracts/p2';

import { request } from '@/src/shared/api/http';

export interface Results {
  streams: GqlStreamConnection;
  channels: GqlChannelConnection | null;
}
export function explorePath(params: URLSearchParams, search: boolean) {
  const query = new URLSearchParams({ limit: '12' });
  for (const [from, to] of [
    ['q', 'title'],
    ['category', 'categoryId'],
    ['tag', 'tagId'],
    ['cursor', 'liveCursor'],
    ['channelCursor', 'channelCursor'],
  ]) {
    const value = params.get(from!);
    if (value) query.set(to!, value);
  }
  if (search) query.set('searchChannels', 'true');
  return `/api/screens/explore?${query}`;
}
export function discoveryResults(screen: P2ExploreScreen): Results {
  const section = screen.discovery;
  if ((section.status !== 'AVAILABLE' && section.status !== 'EMPTY') || !section.data)
    throw new Error('Las transmisiones no están disponibles. Reintenta la consulta.');
  return {
    streams: section.data.streams as GqlStreamConnection,
    channels: (section.data.channels ?? null) as GqlChannelConnection | null,
  };
}

/** Core-only development keeps the public base API; a deployed Gateway failure never selects this path. */
export async function baseDiscovery(
  params: URLSearchParams,
  search: boolean,
  signal: AbortSignal,
): Promise<Results> {
  async function query(name: string, variables: Record<string, unknown>) {
    const operation = queries.find((item) => item.name === name);
    if (!operation) throw new Error('Consulta no generada.');
    const response = await request<GraphQLResponse>('/api/discovery/graphql', {
      method: 'POST',
      body: { query: operation.query, variables },
      signal,
    });
    if (response.errors?.length || !response.data) throw new Error('Discovery no está disponible.');
    return response.data;
  }
  const [streams, channels] = await Promise.all([
    query('LiveStreams', {
      q: params.get('q') ?? '',
      categoryId: params.get('category'),
      tagId: params.get('tag'),
      cursor: params.get('cursor'),
      limit: 20,
    }),
    search
      ? query('Channels', {
          q: params.get('q') ?? '',
          cursor: params.get('channelCursor'),
          limit: 20,
        })
      : Promise.resolve(null),
  ]);
  return {
    streams: streams.streams as GqlStreamConnection,
    channels: (channels?.channels ?? null) as GqlChannelConnection | null,
  };
}
