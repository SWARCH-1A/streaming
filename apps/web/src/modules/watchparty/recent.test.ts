import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { forgetParty, rememberParty, useRecentParties } from './recent';

const a = { partyId: `wp_${'a'.repeat(32)}`, title: 'Sala A' };
const b = { partyId: `wp_${'b'.repeat(32)}`, title: 'Sala B' };

beforeEach(() => window.localStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe('salas recientes del visitante', () => {
  it('recuerda por usuario, sin repetir y con la más reciente primero', () => {
    const mine = renderHook(() => useRecentParties('usr_1'));
    const other = renderHook(() => useRecentParties('usr_2'));
    act(() => {
      rememberParty('usr_1', a);
      rememberParty('usr_1', b);
      rememberParty('usr_1', a);
    });
    expect(mine.result.current.map((item) => item.title)).toEqual(['Sala A', 'Sala B']);
    expect(other.result.current).toEqual([]);
    act(() => forgetParty('usr_1', a.partyId));
    expect(mine.result.current).toEqual([b]);
  });

  it('solo guarda partyId y título: nada que se parezca a un código', () => {
    rememberParty('usr_1', { ...a, accessCode: 'c'.repeat(43) } as typeof a);
    const stored = window.localStorage.getItem('vault.watchparty.recent.v1:usr_1') ?? '';
    expect(JSON.parse(stored)).toEqual([a]);
    expect(stored).not.toContain('c'.repeat(43));
    expect(stored).not.toMatch(/accessCode/i);
  });

  it('descarta datos corruptos o manipulados y limita la lista a 8', () => {
    window.localStorage.setItem(
      'vault.watchparty.recent.v1:usr_1',
      JSON.stringify([
        a,
        { partyId: '../x', title: 'mala' },
        { partyId: b.partyId, title: 3 },
        'x',
      ]),
    );
    expect(renderHook(() => useRecentParties('usr_1')).result.current).toEqual([a]);
    window.localStorage.setItem('vault.watchparty.recent.v1:usr_1', '{no es json');
    expect(renderHook(() => useRecentParties('usr_1')).result.current).toEqual([]);
    for (let index = 0; index < 12; index++)
      rememberParty('usr_3', {
        partyId: `wp_${String(index).padStart(32, '0')}`,
        title: `S${index}`,
      });
    expect(renderHook(() => useRecentParties('usr_3')).result.current).toHaveLength(8);
  });

  it('funciona sin almacenamiento: no lanza y simplemente no recuerda', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    const view = renderHook(() => useRecentParties('usr_1'));
    expect(() => act(() => rememberParty('usr_1', a))).not.toThrow();
    expect(view.result.current).toEqual([]);
    expect(renderHook(() => useRecentParties(undefined)).result.current).toEqual([]);
  });
});
