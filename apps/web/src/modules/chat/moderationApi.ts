import type {
  P2ChatBlock,
  P2ChatBlockCommand,
  P2ChatModerationOperation,
  P2ChatRemoval,
} from '@contracts/p2';

import { request } from '@/src/shared/api/http';

// Chat valida el doble envío CSRF emitido por Core y exige Idempotency-Key en cada mutación.
function mutation<T>(path: string, method: string, body?: unknown) {
  return request<T>(path, {
    method,
    body,
    coreMutation: true,
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  });
}

export const BLOCK_OPTIONS = [
  { value: 'PT10M', label: '10 minutos' },
  { value: 'PT1H', label: '1 hora' },
  { value: 'PT24H', label: '24 horas' },
  { value: 'P7D', label: '7 días' },
  { value: 'PERMANENT', label: 'Permanente' },
] as const;
export type BlockChoice = (typeof BLOCK_OPTIONS)[number]['value'];

export function removeMessage(sessionId: string, messageId: string) {
  return mutation<P2ChatRemoval>(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/messages/${encodeURIComponent(messageId)}`,
    'DELETE',
  );
}

export function blockUser(channelId: string, userId: string, choice: BlockChoice) {
  const command: P2ChatBlockCommand =
    choice === 'PERMANENT' ? { mode: 'PERMANENT' } : { mode: 'TEMPORARY', duration: choice };
  return mutation<P2ChatBlock>(
    `/api/chat/channels/${encodeURIComponent(channelId)}/blocks/${encodeURIComponent(userId)}`,
    'PUT',
    command,
  );
}

export function unblockUser(channelId: string, userId: string) {
  return mutation<P2ChatBlock>(
    `/api/chat/channels/${encodeURIComponent(channelId)}/blocks/${encodeURIComponent(userId)}`,
    'DELETE',
  );
}

export function removeUserMessages(channelId: string, sessionId: string, userId: string) {
  return mutation<P2ChatModerationOperation>(
    `/api/chat/channels/${encodeURIComponent(channelId)}/message-removals`,
    'POST',
    { sessionId, userId },
  );
}

/** Texto para quien está bloqueado: hasta cuándo, en la hora local del navegador. */
export function blockedNotice(blockedUntilUtc?: string) {
  if (!blockedUntilUtc) return 'Estás bloqueado de forma permanente en el chat de este canal.';
  const until = new Date(blockedUntilUtc).toLocaleString('es', {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  return `Estás bloqueado en el chat de este canal hasta el ${until}.`;
}
