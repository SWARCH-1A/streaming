import { HttpError } from '@/src/shared/api/http';

export function fieldErrors<Field extends string>(error: unknown, fields: readonly Field[]) {
  const result: Partial<Record<Field, string>> = {};
  if (error instanceof HttpError) {
    for (const field of fields) {
      const message = error.detail.fieldErrors?.[field];
      if (typeof message === 'string' && message.trim()) result[field] = message;
    }
  }
  return result;
}
