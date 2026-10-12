export interface ChatMessage {
  id: string;
  author: string;
  text: string;
  time: string;
  role: 'broadcaster' | 'viewer';
}
