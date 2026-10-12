export interface StreamCardData {
  id: string;
  title: string;
  image: string;
  channel: { handle: string; name: string; avatar: string };
  category: { id: string; name: string };
  tags: readonly { id: string; name: string }[];
  viewers: number;
  startedAt: string;
  availability: 'PLAYABLE' | 'OFFLINE' | 'RECONNECTING';
}
