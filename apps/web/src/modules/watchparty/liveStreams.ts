import queries from '@contracts/graphql-queries.json';
import type { GqlStreamConnection, GraphQLResponse } from '@contracts/p1';

import { request } from '@/src/shared/api/http';

export type LiveStreamOption = GqlStreamConnection['items'][number];

/** First page of live streams from Discovery's canonical query; the owner picks sources from it. */
export async function loadLiveStreams(signal: AbortSignal): Promise<LiveStreamOption[]> {
  const operation = queries.find((item) => item.name === 'LiveStreams');
  if (!operation) throw new Error('Consulta no generada.');
  const response = await request<GraphQLResponse>('/api/discovery/graphql', {
    method: 'POST',
    body: { query: operation.query, variables: { limit: 50 } },
    signal,
  });
  if (response.errors?.length || !response.data) throw new Error('Discovery no está disponible.');
  return (response.data.streams as GqlStreamConnection).items;
}
