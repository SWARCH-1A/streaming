export type BroadcastState =
  'OFFLINE' | 'PREPARING' | 'LIVE' | 'RECONNECT_GRACE' | 'ENDED' | 'ERROR';
export interface StreamMetadata {
  title: string;
  categoryId: string;
  tagIds: string[];
}
