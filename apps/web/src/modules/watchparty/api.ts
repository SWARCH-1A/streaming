import type { P2WatchParty } from '@contracts/p2';

import { HttpError, request } from '@/src/shared/api/http';

export type Party = P2WatchParty;
export type PartySource = P2WatchParty['sources'][number];

const base = '/api/watch-parties';
export const MAX_SOURCES = 4;
export const PARTY_ID = /^wp_[0-9a-f]{32}$/;
export const ACCESS_CODE = /^[A-Za-z0-9_-]{43}$/;

const partyPath = (partyId: string) => `${base}/${encodeURIComponent(partyId)}`;

function command(
  path: string,
  method: 'POST' | 'DELETE',
  key: string,
  body?: unknown,
  signal?: AbortSignal,
) {
  return request<Party>(path, {
    method,
    coreMutation: true,
    headers: { 'Idempotency-Key': key },
    ...(body !== undefined ? { body } : {}),
    ...(signal ? { signal } : {}),
  });
}

export function getParty(partyId: string, signal?: AbortSignal) {
  return request<Party>(partyPath(partyId), signal ? { signal } : {});
}
export function createParty(title: string, key: string) {
  return command(base, 'POST', key, { title });
}
export function joinParty(accessCode: string, key: string) {
  return command(`${base}/join`, 'POST', key, { accessCode });
}
export function addSource(partyId: string, streamId: string, key: string) {
  return command(`${partyPath(partyId)}/streams`, 'POST', key, { streamId });
}
export function removeSource(partyId: string, streamId: string, key: string) {
  return command(`${partyPath(partyId)}/streams/${encodeURIComponent(streamId)}`, 'DELETE', key);
}
export function rotateAccessCode(partyId: string, key: string) {
  return command(`${partyPath(partyId)}/access-code/rotate`, 'POST', key);
}
export function closeParty(partyId: string, key: string) {
  return command(`${partyPath(partyId)}/close`, 'POST', key);
}

const messages: Record<string, string> = {
  WATCH_PARTY_NOT_FOUND: 'No encontramos esa sala o no tienes acceso a ella.',
  WATCH_PARTY_CLOSED: 'La sala está cerrada: ya no admite cambios ni nuevos miembros.',
  WATCH_PARTY_FULL: 'La sala ya tiene el máximo de 4 transmisiones. Quita una para añadir otra.',
  WATCH_PARTY_FORBIDDEN: 'Solo quien creó la sala puede hacer este cambio.',
  STREAM_ALREADY_IN_PARTY: 'Esa transmisión ya está en la sala.',
  STREAM_NOT_LIVE: 'Esa transmisión no está en vivo ahora, así que no se puede añadir.',
  STREAM_NOT_FOUND: 'No encontramos esa transmisión o su canal no está disponible.',
  STREAMING_UNAVAILABLE:
    'No pudimos consultar el estado de la transmisión. Inténtalo de nuevo en unos segundos.',
  IDEMPOTENCY_CONFLICT: 'Esta acción cambió mientras se enviaba. Vuelve a intentarlo.',
  CSRF_INVALID: 'La comprobación de seguridad expiró. Vuelve a intentarlo.',
  AUTH_REQUIRED: 'Tu sesión expiró. Inicia sesión de nuevo.',
  CORE_UNAVAILABLE: 'El servicio no está disponible ahora. Inténtalo de nuevo en unos segundos.',
};
const byStatus: Record<number, string> = {
  401: messages.AUTH_REQUIRED ?? '',
  404: messages.WATCH_PARTY_NOT_FOUND ?? '',
  503: messages.CORE_UNAVAILABLE ?? '',
};

/** Spanish message for a Watch Party failure; keeps the code and request ID so support can trace it. */
export function partyError(error: unknown): string {
  if (!(error instanceof HttpError))
    return error instanceof Error ? error.message : 'No se pudo completar la solicitud.';
  const text = messages[error.detail.code] ?? byStatus[error.status];
  if (!text) return error.message;
  const reference = error.detail.requestId ? ` · ${error.detail.requestId}` : '';
  return `${text} (${error.detail.code}${reference})`;
}

/** Definitive answers that mean the cached room must not stay on screen. */
export function isGone(error: unknown) {
  return error instanceof HttpError && [401, 403, 404].includes(error.status);
}

export function joinLink(origin: string, accessCode: string) {
  return `${origin}/watch-parties/join#${accessCode}`;
}
