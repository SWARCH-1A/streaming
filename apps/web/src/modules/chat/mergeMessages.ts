import type { ChatMessage } from '@contracts/p1';

export function mergeMessages(
  sessionId: string,
  current: readonly ChatMessage[],
  incoming: readonly ChatMessage[],
): ChatMessage[] {
  const bySequence = new Map<number, ChatMessage>();
  for (const message of [...current, ...incoming]) {
    if (message.sessionId !== sessionId) continue;
    const previous = bySequence.get(message.sequence);
    if (previous && previous.messageId !== message.messageId)
      throw new Error('Secuencia de chat inconsistente.');
    bySequence.set(message.sequence, message);
  }
  return [...bySequence.values()].sort((a, b) => a.sequence - b.sequence).slice(-50);
}
export function hasGap(messages: readonly ChatMessage[]) {
  return messages.some(
    (message, index) => index > 0 && message.sequence !== (messages[index - 1]?.sequence ?? 0) + 1,
  );
}
