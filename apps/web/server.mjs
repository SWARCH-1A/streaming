// SSR runtime (SPEC-18): HTTPS, hashed static assets and React Router rendering per request.
import { createReadStream, readFileSync } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer as createHttp } from 'node:http';
import { createServer as createHttps } from 'node:https';
import { extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { createRequestListener } from '@react-router/node';

const client = fileURLToPath(new URL('./build/client/', import.meta.url));
const build = await import('./build/server/index.js');
const render = createRequestListener({ build, mode: process.env.NODE_ENV ?? 'production' });

const TYPES = {
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ico': 'image/x-icon',
  '.json': 'application/json',
  '.txt': 'text/plain; charset=utf-8',
};

/** Serves a file from build/client if it exists; never escapes that directory. */
async function staticFile(request, response) {
  if (request.method !== 'GET' && request.method !== 'HEAD') return false;
  const pathname = decodeURIComponent(new URL(request.url ?? '/', 'http://web').pathname);
  if (pathname === '/' || pathname.endsWith('/')) return false;
  const file = normalize(join(client, pathname));
  if (!file.startsWith(client.endsWith(sep) ? client : client + sep)) return false;
  const type = TYPES[extname(file)];
  if (!type) return false;
  try {
    const info = await stat(file);
    if (!info.isFile()) return false;
    response.statusCode = 200;
    response.setHeader('Content-Type', type);
    response.setHeader('Content-Length', info.size);
    response.setHeader('X-Content-Type-Options', 'nosniff');
    // Vite fingerprints /assets/*: they can be cached forever. Anything else revalidates.
    response.setHeader(
      'Cache-Control',
      pathname.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache',
    );
    if (request.method === 'HEAD') response.end();
    else createReadStream(file).pipe(response);
    return true;
  } catch {
    return false;
  }
}

async function handler(request, response) {
  try {
    if (await staticFile(request, response)) return;
  } catch {
    /* fall through to the router */
  }
  response.setHeader('X-Content-Type-Options', 'nosniff');
  response.setHeader('Referrer-Policy', 'same-origin');
  render(request, response);
}

const port = Number(process.env.PORT ?? 3443);
const cert = process.env.WEB_TLS_CERT_FILE;
const key = process.env.WEB_TLS_KEY_FILE;
const server =
  cert && key
    ? createHttps(
        { cert: readFileSync(cert), key: readFileSync(key), minVersion: 'TLSv1.2' },
        handler,
      )
    : createHttp(handler);
server.requestTimeout = 15_000;
server.listen(port, () => {
  console.log(
    JSON.stringify({
      level: 'info',
      component: 'web',
      event: 'listening',
      port,
      tls: Boolean(cert),
    }),
  );
});
for (const signal of ['SIGTERM', 'SIGINT'])
  process.once(signal, () => server.close(() => process.exit(0)));
