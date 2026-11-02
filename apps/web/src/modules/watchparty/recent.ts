import { useMemo, useSyncExternalStore } from 'react';

import { PARTY_ID } from './api';

export interface RecentParty {
  partyId: string;
  title: string;
}

// Per-visitor convenience only: it never holds the access code, and every access tolerates blocked storage.
const PREFIX = 'vault.watchparty.recent.v1:';
const LIMIT = 8;
const listeners = new Set<() => void>();

function storageKey(userId: string) {
  return `${PREFIX}${userId}`;
}
function readRaw(userId: string): string {
  try {
    return window.localStorage.getItem(storageKey(userId)) ?? '';
  } catch {
    return '';
  }
}
function parse(raw: string): RecentParty[] {
  try {
    const value: unknown = JSON.parse(raw || '[]');
    if (!Array.isArray(value)) return [];
    return value
      .filter(
        (item): item is RecentParty =>
          typeof item === 'object' &&
          item !== null &&
          typeof (item as RecentParty).partyId === 'string' &&
          PARTY_ID.test((item as RecentParty).partyId) &&
          typeof (item as RecentParty).title === 'string',
      )
      .map(({ partyId, title }) => ({ partyId, title: title.slice(0, 100) }))
      .slice(0, LIMIT);
  } catch {
    return [];
  }
}
function write(userId: string, parties: RecentParty[]) {
  try {
    window.localStorage.setItem(storageKey(userId), JSON.stringify(parties.slice(0, LIMIT)));
  } catch {
    /* Without storage the list simply is not remembered. */
  }
  listeners.forEach((listener) => listener());
}

export function rememberParty(userId: string, party: RecentParty) {
  const rest = parse(readRaw(userId)).filter((item) => item.partyId !== party.partyId);
  write(userId, [{ partyId: party.partyId, title: party.title }, ...rest]);
}
export function forgetParty(userId: string, partyId: string) {
  write(
    userId,
    parse(readRaw(userId)).filter((item) => item.partyId !== partyId),
  );
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  window.addEventListener('storage', listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', listener);
  };
}

/** Recent rooms of this visitor; empty on the server and until the browser answers. */
export function useRecentParties(userId: string | undefined): RecentParty[] {
  const raw = useSyncExternalStore(
    subscribe,
    () => (userId ? readRaw(userId) : ''),
    () => '',
  );
  return useMemo(() => parse(raw), [raw]);
}
