import { describe, expect, it } from 'vitest';

import { HttpError } from '@/src/shared/api/http';

import { isGone, joinLink, partyError } from './api';

function failure(status: number, code: string) {
  return new HttpError(status, { code, message: 'texto del servidor', requestId: 'req_test' });
}

describe('errores de Watch Party', () => {
  it.each([
    ['WATCH_PARTY_FULL', 409, 'máximo de 4 transmisiones'],
    ['STREAM_ALREADY_IN_PARTY', 409, 'ya está en la sala'],
    ['STREAM_NOT_LIVE', 409, 'no está en vivo'],
    ['WATCH_PARTY_CLOSED', 409, 'está cerrada'],
    ['WATCH_PARTY_FORBIDDEN', 403, 'Solo quien creó la sala'],
    ['STREAM_NOT_FOUND', 404, 'No encontramos esa transmisión'],
    ['WATCH_PARTY_NOT_FOUND', 404, 'No encontramos esa sala'],
    ['STREAMING_UNAVAILABLE', 503, 'No pudimos consultar'],
  ])('traduce %s con su código y la referencia de la solicitud', (code, status, text) => {
    const message = partyError(failure(status, code));
    expect(message).toContain(text);
    expect(message).toContain(`(${code} · req_test)`);
    expect(message).not.toContain('texto del servidor');
  });

  it('usa el estado HTTP cuando el código no se conoce y el mensaje del servicio si no hay nada mejor', () => {
    expect(partyError(failure(401, 'OTRO'))).toContain('Inicia sesión');
    expect(partyError(failure(503, 'OTRO'))).toContain('no está disponible');
    expect(partyError(failure(418, 'OTRO'))).toContain('texto del servidor');
    expect(partyError(new Error('sin red'))).toBe('sin red');
  });

  it('solo 401, 403 y 404 vacían una sala ya mostrada', () => {
    expect([401, 403, 404, 409, 503].map((status) => isGone(failure(status, 'X')))).toEqual([
      true,
      true,
      true,
      false,
      false,
    ]);
    expect(isGone(new Error('red'))).toBe(false);
  });

  it('el enlace de invitación lleva el código en el fragmento, nunca en la consulta', () => {
    const link = joinLink('https://vault.example', 'abc');
    expect(link).toBe('https://vault.example/watch-parties/join#abc');
    expect(new URL(link).search).toBe('');
  });
});
