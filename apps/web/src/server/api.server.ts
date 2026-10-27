// Server-only (".server"): never bundled for the browser.

/**
 * Loader client. Each call receives the incoming request, so identity is per request: only the session
 * and CSRF cookies are forwarded, and only to the configured trusted upstream (Gateway in P2; Core's
 * public listener until the Gateway is deployed). Never to a URL taken from a form, query or event.
 */
const FORWARDED_COOKIES = new Set(['stream_session', 'XSRF-TOKEN']);

export class UpstreamError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`upstream ${status}`);
    this.status = status;
  }
}

function upstream(streaming = false): string {
  const base = streaming
    ? (process.env.WEB_STREAMING_UPSTREAM ?? process.env.WEB_API_UPSTREAM)
    : process.env.WEB_API_UPSTREAM;
  if (!base) throw new UpstreamError(503);
  return base.replace(/\/$/, '');
}

function cookies(request: Request): string {
  return (request.headers.get('cookie') ?? '')
    .split(';')
    .map((part) => part.trim())
    .filter((part) => FORWARDED_COOKIES.has(part.slice(0, part.indexOf('='))))
    .join('; ');
}

export function hasSession(request: Request): boolean {
  return cookies(request).includes('stream_session=');
}

/** GET only: loaders read. Commands keep running in the browser against the domain APIs. */
export async function serverGet<T>(request: Request, path: string): Promise<T> {
  if (!path.startsWith('/api/')) throw new Error('Loader paths must target the public API');
  return read<T>(request, path, upstream(), true);
}

/** Public live reads need no identity cookies; keep the configured Streaming origin server-only. */
export async function serverStreamGet<T>(request: Request, path: string): Promise<T> {
  if (!path.startsWith('/api/streams/')) throw new Error('Expected a public Streaming path');
  return read<T>(request, path, upstream(true), false);
}

async function read<T>(
  request: Request,
  path: string,
  base: string,
  identity: boolean,
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const cookie = identity ? cookies(request) : '';
  if (cookie) headers.Cookie = cookie;
  const requestId = request.headers.get('x-request-id');
  if (requestId && /^[A-Za-z0-9_-]{1,128}$/.test(requestId)) headers['X-Request-ID'] = requestId;
  let response: Response;
  try {
    response = await fetch(base + path, {
      headers,
      redirect: 'error',
      signal: AbortSignal.any([AbortSignal.timeout(3000), request.signal]),
    });
  } catch {
    throw new UpstreamError(503);
  }
  if (!response.ok) throw new UpstreamError(response.status);
  return (await response.json()) as T;
}
