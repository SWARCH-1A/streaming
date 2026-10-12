import { codePointLength } from '@/src/shared/format';

export function validateMessage(raw: string) {
  const text = raw.normalize('NFC').trim();
  const length = codePointLength(text);
  return {
    text,
    error:
      length === 0
        ? 'Escribe un mensaje antes de enviarlo.'
        : length > 500
          ? 'El mensaje admite hasta 500 caracteres.'
          : null,
  };
}
