import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { HttpError } from '@/src/shared/api/http';

import { useCommand } from './useCommand';

beforeEach(() => vi.useFakeTimers({ toFake: ['Date'] }));
afterEach(() => vi.useRealTimers());

const definitive = new HttpError(409, { code: 'WATCH_PARTY_FULL', message: 'x', requestId: 'r' });
const uncertain = new HttpError(503, { code: 'CORE_UNAVAILABLE', message: 'x', requestId: 'r' });

async function run(
  hook: ReturnType<typeof useCommand>,
  action: string,
  outcome: 'ok' | Error,
  keys: string[],
) {
  await act(async () => {
    await hook.run(action, (key) => {
      keys.push(key);
      return outcome === 'ok' ? Promise.resolve('ok') : Promise.reject(outcome);
    });
  });
}

it('reutiliza la clave tras un fallo incierto (red o 5xx) de la misma acción', async () => {
  const keys: string[] = [];
  const { result } = renderHook(() => useCommand());
  await run(result.current, 'add:x', uncertain, keys);
  await run(result.current, 'add:x', new TypeError('network'), keys);
  await run(result.current, 'add:x', 'ok', keys);
  expect(new Set(keys).size).toBe(1);
});

it('no reutiliza la clave tras una respuesta definitiva: un 4xx no aplicó nada', async () => {
  const keys: string[] = [];
  const { result } = renderHook(() => useCommand());
  await run(result.current, 'add:x', definitive, keys);
  await run(result.current, 'add:x', 'ok', keys);
  expect(new Set(keys).size).toBe(2);
});

it('un éxito de cualquier acción descarta las claves pendientes: la sala ya cambió', async () => {
  const keys: string[] = [];
  const { result } = renderHook(() => useCommand());
  await run(result.current, 'add:x', uncertain, keys); // quizá Core sí la aplicó
  await run(result.current, 'remove:x', 'ok', keys); // el propietario la quita
  await run(result.current, 'add:x', 'ok', keys); // la vuelve a añadir a propósito
  expect(keys[2]).not.toBe(keys[0]);
});

it('la clave de un fallo incierto caduca al cabo de un minuto', async () => {
  const keys: string[] = [];
  const { result } = renderHook(() => useCommand());
  await run(result.current, 'add:x', uncertain, keys);
  vi.setSystemTime(Date.now() + 61_000);
  await run(result.current, 'add:x', 'ok', keys);
  expect(keys[1]).not.toBe(keys[0]);
});
