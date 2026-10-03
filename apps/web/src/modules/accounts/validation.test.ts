import { describe, expect, it } from 'vitest';

import { validateProfile, validateRegistration } from './validation';

const valid = { email: 'demo@example.test', handle: 'demo_01', password: '123456789012' };
describe('registro y perfil', () => {
  it.each([12, 128])('acepta el límite de %i puntos de código Unicode de contraseña', (length) => {
    expect(validateRegistration({ ...valid, password: '🎮'.repeat(length) })).toEqual({});
  });
  it.each([11, 129])('rechaza %i puntos de código', (length) => {
    expect(
      validateRegistration({ ...valid, password: '🎮'.repeat(length) }).password,
    ).toBeDefined();
  });
  it('no recorta ni normaliza la contraseña', () => {
    expect(validateRegistration({ ...valid, password: ' '.repeat(12) })).toEqual({});
  });
  it.each(['abc', 'ñdemo', 'a'.repeat(26), 'demo-name'])('rechaza handle inválido %s', (handle) => {
    expect(validateRegistration({ ...valid, handle }).handle).toBeDefined();
  });
  it('valida correo, nombre vacío y límites del perfil', () => {
    expect(validateRegistration({ ...valid, email: 'bad' }).email).toBeDefined();
    expect(validateProfile('', '').displayName).toBeDefined();
    expect(validateProfile('A'.repeat(50), '🎮'.repeat(300))).toEqual({});
    expect(validateProfile('A'.repeat(51), '🎮'.repeat(301))).toHaveProperty('bio');
  });
});
