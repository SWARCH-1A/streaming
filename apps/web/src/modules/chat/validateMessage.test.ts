import { describe, expect, it } from 'vitest';

import { validateMessage } from './validateMessage';

describe('mensaje de chat', () => {
  it('normaliza a NFC y recorta extremos', () => {
    expect(validateMessage('  cafe\u0301  ')).toEqual({ text: 'café', error: null });
  });
  it('rechaza texto vacío', () => {
    expect(validateMessage(' \n ').error).toBeTruthy();
  });
  it('cuenta emojis como puntos de código, no unidades UTF-16', () => {
    expect(validateMessage('🎮'.repeat(500)).error).toBeNull();
    expect(validateMessage('🎮'.repeat(501)).error).toBeTruthy();
  });
});
