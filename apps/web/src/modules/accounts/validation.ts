import { codePointLength } from '@/src/shared/format';

export interface RegistrationValues {
  email: string;
  handle: string;
  password: string;
}
export function validateRegistration(values: RegistrationValues) {
  const errors: Partial<Record<keyof RegistrationValues, string>> = {};
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email.trim()))
    errors.email = 'Escribe un correo electrónico válido.';
  if (!/^[a-zA-Z0-9_]{4,25}$/.test(values.handle))
    errors.handle = 'Usa de 4 a 25 letras ASCII, números o guiones bajos.';
  const length = codePointLength(values.password);
  if (length < 12 || length > 128) errors.password = 'Usa de 12 a 128 caracteres.';
  return errors;
}
export function validateProfile(displayName: string, bio: string) {
  const errors: { displayName?: string; bio?: string } = {};
  if (!displayName.trim() || codePointLength(displayName) > 50)
    errors.displayName = 'Usa un nombre de 1 a 50 caracteres.';
  if (codePointLength(bio) > 300) errors.bio = 'La biografía admite hasta 300 caracteres.';
  return errors;
}
