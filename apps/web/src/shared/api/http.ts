import type { Csrf, Error as ApiError, Upload } from '@contracts/p1';

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly detail: ApiError,
  ) {
    super(`${detail.message} (${detail.code}${detail.requestId ? ` · ${detail.requestId}` : ''})`);
  }
}
export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  coreMutation?: boolean;
  keepalive?: boolean;
}
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!path.startsWith('/api/')) throw new Error('La API debe usar el mismo origen.');
  const signal = AbortSignal.any([
    AbortSignal.timeout(8000),
    ...(options.signal ? [options.signal] : []),
  ]);
  const headers = new Headers({
    Accept: 'application/json',
    'X-Request-ID': crypto.randomUUID(),
    ...options.headers,
  });
  if (options.coreMutation) {
    const csrf = await request<Csrf>('/api/identity/csrf', { signal });
    if (csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string')
      throw new Error('Respuesta CSRF inválida.');
    headers.set(csrf.headerName, csrf.token);
  }
  const multipart = options.body instanceof FormData;
  if (options.body !== undefined && !multipart) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    credentials: 'include',
    signal,
    ...(options.body !== undefined
      ? { body: multipart ? (options.body as FormData) : JSON.stringify(options.body) }
      : {}),
    keepalive: options.keepalive ?? false,
  });
  if (response.status === 401 && !path.includes('/viewer-leases'))
    window.dispatchEvent(new Event('session-expired'));
  if (response.status === 204) return undefined as T;
  if (!response.headers.get('content-type')?.toLowerCase().includes('application/json')) {
    throw new Error(`Respuesta inesperada del servicio (${response.status}).`);
  }
  const body: unknown = await response.json();
  if (!response.ok) {
    const detail = body as Partial<ApiError>;
    throw new HttpError(response.status, {
      code: typeof detail?.code === 'string' ? detail.code : 'HTTP_ERROR',
      message:
        typeof detail?.message === 'string' ? detail.message : 'No se pudo completar la solicitud.',
      requestId: typeof detail?.requestId === 'string' ? detail.requestId : '',
      ...(detail?.fieldErrors ? { fieldErrors: detail.fieldErrors } : {}),
    });
  }
  const { validateResponse } = await import('./validate');
  validateResponse(path, options.method ?? 'GET', response.status, body);
  signal.throwIfAborted();
  return body as T;
}
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'No se pudo completar la solicitud.';
}
export function upload(path: string, file: File, signal?: AbortSignal) {
  if (!['image/jpeg', 'image/png', 'image/gif'].includes(file.type) || file.size > 10 * 1024 * 1024)
    throw new Error('Usa una imagen JPEG, PNG o GIF de hasta 10 MB.');
  const body = new FormData();
  body.append('file', file);
  return request<Upload>(path, {
    method: 'POST',
    body,
    coreMutation: true,
    ...(signal ? { signal } : {}),
  });
}
