import { errorMessage, HttpError } from '@/src/shared/api/http';

const ADMIN_REQUIRED_MESSAGE =
  'Tu cuenta no tiene permisos administrativos. Inicia sesión con una cuenta autorizada.';

export function isAdminRequired(message: string | null | undefined) {
  return message?.includes('ADMIN_REQUIRED') ?? false;
}

export function readableAdminError(error: unknown) {
  if (error instanceof HttpError && error.detail.code === 'ADMIN_REQUIRED')
    return ADMIN_REQUIRED_MESSAGE;
  return errorMessage(error);
}
