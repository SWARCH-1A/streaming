import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';

import type { CorePort } from './core.ts';
import { type Db, transaction, type Tx } from './db.ts';
import { ID, sha256 } from './ids.ts';
import type { Log } from './log.ts';

const MAX_BODY = 16 * 1024;
const CURSOR_LIFETIME_S = 3600;
const IDEMPOTENCY_RETENTION = "interval '24 hours'";

class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
const invalid = (message: string) => new ApiError(400, 'VALIDATION_ERROR', message);

export interface ApiDeps {
  db: Db;
  core: CorePort;
  cursorSecret: string;
  log: Log;
}

interface Request {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: IncomingMessage['headers'];
  body: unknown;
  requestId: string;
}

type Route = { method: string; pattern: RegExp; mutation: boolean; run: (r: Request, user: string, m: string[]) => Promise<Result> };
type Result = { status: number; body?: unknown };

/** p2.notifications.*: every operation acts on the session principal, never on a userId from the client. */
export function apiHandler(deps: ApiDeps) {
  const routes: Route[] = [
    { method: 'GET', pattern: /^\/api\/notifications$/, mutation: false, run: (r, u) => list(deps, r, u) },
    { method: 'PUT', pattern: /^\/api\/notifications\/([A-Za-z0-9_-]{1,128})\/read$/, mutation: true, run: (r, u, m) => markRead(deps, u, m[1]!) },
    { method: 'GET', pattern: /^\/api\/notifications\/preferences$/, mutation: false, run: async (_r, u) => ({ status: 200, body: await mediums(deps.db, u) }) },
    { method: 'PUT', pattern: /^\/api\/notifications\/preferences$/, mutation: true, run: (r, u) => putMediums(deps, r, u) },
    { method: 'GET', pattern: /^\/api\/notifications\/channels\/([A-Za-z0-9_-]{1,128})\/preference$/, mutation: false,
      run: async (_r, u, m) => ({ status: 200, body: await channel(deps.db, u, m[1]!) }) },
    { method: 'PUT', pattern: /^\/api\/notifications\/channels\/([A-Za-z0-9_-]{1,128})\/preference$/, mutation: true,
      run: (r, u, m) => putChannel(deps, r, u, m[1]!) },
    { method: 'PUT', pattern: /^\/api\/notifications\/devices\/([A-Za-z0-9_-]{1,128})$/, mutation: true, run: (r, u, m) => putDevice(deps, r, u, m[1]!) },
    { method: 'DELETE', pattern: /^\/api\/notifications\/devices\/([A-Za-z0-9_-]{1,128})$/, mutation: true, run: (_r, u, m) => deleteDevice(deps, u, m[1]!) },
  ];

  return async (incoming: IncomingMessage, response: ServerResponse): Promise<void> => {
    const requestId = /^[A-Za-z0-9_-]{1,128}$/.test(String(incoming.headers['x-request-id'] ?? ''))
      ? String(incoming.headers['x-request-id']) : `req_${randomUUID().replaceAll('-', '')}`;
    try {
      const url = new URL(incoming.url ?? '/', 'http://notifications');
      if (url.pathname === '/health/ready') {
        await deps.db.query('SELECT 1');
        return send(response, 200, { status: 'UP' });
      }
      const candidates = routes.filter((route) => route.pattern.test(url.pathname));
      if (!candidates.length) throw new ApiError(404, 'NOT_FOUND', 'Ruta no encontrada.');
      const route = candidates.find((candidate) => candidate.method === incoming.method);
      if (!route) throw new ApiError(405, 'METHOD_NOT_ALLOWED', 'Método no permitido.');
      const match = route.pattern.exec(url.pathname)!;
      const request: Request = { method: route.method, path: url.pathname, query: url.searchParams, headers: incoming.headers,
        body: await readBody(incoming, route.method), requestId };
      const { credential, cookie } = credentialOf(incoming);
      if (!credential) throw new ApiError(401, 'AUTH_REQUIRED', 'Se requiere una sesión activa.');
      // Browser cookies need the double-submit CSRF token already issued by Core; Bearer clients do not.
      if (route.mutation && cookie) requireCsrf(incoming);
      let user: string | null;
      try {
        user = await deps.core.sessionUser(credential);
      } catch {
        throw new ApiError(503, 'AUTH_UNAVAILABLE', 'No se pudo comprobar la sesión.');
      }
      if (!user) throw new ApiError(401, 'AUTH_REQUIRED', 'Se requiere una sesión activa.');
      const result = route.mutation
        ? await idempotent(deps.db, user, request, () => route.run(request, user, match))
        : await route.run(request, user, match);
      send(response, result.status, result.body);
    } catch (error) {
      if (error instanceof ApiError) return send(response, error.status, { code: error.code, message: error.message, requestId });
      deps.log.warn('api error', { requestId, code: error instanceof Error ? error.name : 'UNKNOWN' });
      send(response, 503, { code: 'SERVICE_UNAVAILABLE', message: 'Servicio no disponible.', requestId });
    }
  };
}

function send(response: ServerResponse, status: number, body?: unknown): void {
  response.statusCode = status;
  response.setHeader('Cache-Control', 'no-store, private');
  response.setHeader('X-Content-Type-Options', 'nosniff');
  if (body === undefined) {
    response.end();
    return;
  }
  response.setHeader('Content-Type', 'application/json; charset=utf-8');
  response.end(JSON.stringify(body));
}

async function readBody(incoming: IncomingMessage, method: string): Promise<unknown> {
  if (method === 'GET' || method === 'DELETE') return undefined;
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of incoming) {
    size += (chunk as Buffer).length;
    if (size > MAX_BODY) throw new ApiError(413, 'PAYLOAD_TOO_LARGE', 'El cuerpo supera 16 KiB.');
    chunks.push(chunk as Buffer);
  }
  if (!size) return undefined;
  const type = String(incoming.headers['content-type'] ?? '').split(';')[0]!.trim().toLowerCase();
  if (type !== 'application/json') throw new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Se requiere application/json.');
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw invalid('JSON inválido.');
  }
}

function cookies(incoming: IncomingMessage): Map<string, string> {
  const values = new Map<string, string>();
  for (const part of String(incoming.headers.cookie ?? '').split(';')) {
    const index = part.indexOf('=');
    if (index > 0) values.set(part.slice(0, index).trim(), part.slice(index + 1).trim());
  }
  return values;
}

function credentialOf(incoming: IncomingMessage): { credential: string | null; cookie: boolean } {
  const authorization = String(incoming.headers.authorization ?? '');
  if (authorization.startsWith('Bearer ')) return { credential: authorization.slice(7).trim() || null, cookie: false };
  return { credential: cookies(incoming).get('stream_session') ?? null, cookie: true };
}

function requireCsrf(incoming: IncomingMessage): void {
  const header = String(incoming.headers['x-xsrf-token'] ?? '');
  const cookie = cookies(incoming).get('XSRF-TOKEN') ?? '';
  const a = Buffer.from(header);
  const b = Buffer.from(cookie);
  if (!header || a.length !== b.length || !timingSafeEqual(a, b)) throw new ApiError(403, 'CSRF_INVALID', 'La solicitud no está autorizada.');
}

function object(body: unknown, allowed: string[], required: string[]): Record<string, unknown> {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) throw invalid('Se requiere un objeto JSON.');
  const value = body as Record<string, unknown>;
  for (const key of Object.keys(value)) if (!allowed.includes(key)) throw invalid(`Campo no admitido: ${key}`);
  for (const key of required) if (!(key in value)) throw invalid(`Falta el campo ${key}.`);
  return value;
}

function version(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 1) throw invalid('expectedVersion no es válido.');
  return value as number;
}

/** Idempotency-Key UUID, bound to the user, operation and body; same key with other content is 409. */
async function idempotent(db: Db, user: string, request: Request, action: () => Promise<Result>): Promise<Result> {
  const key = String(request.headers['idempotency-key'] ?? '');
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(key)) {
    throw new ApiError(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Se requiere Idempotency-Key UUID.');
  }
  const keyHash = sha256(key.toLowerCase());
  const fingerprint = sha256(`${request.method} ${request.path} ${JSON.stringify(request.body ?? null)}`);
  return transaction(db, async (tx) => {
    await tx.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`notifications.idem|${user}|${keyHash}`]);
    const previous = await tx.query<{ fingerprint: string; status: number; response: string }>(
      'SELECT fingerprint, status, response FROM idempotency WHERE user_id=$1 AND key_hash=$2 AND retain_until_utc>now()', [user, keyHash]);
    if (previous.rowCount) {
      const row = previous.rows[0]!;
      if (row.fingerprint !== fingerprint) throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'La Idempotency-Key ya se usó con otra solicitud.');
      return { status: row.status, body: row.response === '' ? undefined : JSON.parse(row.response) };
    }
    const result = await action();
    await tx.query(
      `INSERT INTO idempotency (user_id, key_hash, fingerprint, status, response, retain_until_utc) VALUES ($1,$2,$3,$4,$5, now()+${IDEMPOTENCY_RETENTION})
       ON CONFLICT (user_id, key_hash) DO UPDATE SET fingerprint=EXCLUDED.fingerprint, status=EXCLUDED.status,
         response=EXCLUDED.response, retain_until_utc=EXCLUDED.retain_until_utc`,
      [user, keyHash, fingerprint, result.status, result.body === undefined ? '' : JSON.stringify(result.body)]);
    return result;
  });
}

// --- Inbox --------------------------------------------------------------------------------------

interface NotificationRow {
  notification_id: string;
  kind: string;
  channel_id: string;
  session_id: string;
  title: string;
  link_path: string;
  created_at_utc: Date;
  read_at_utc: Date | null;
  micros: string;
}

const NOTIFICATION_COLUMNS = `n.notification_id, n.kind, n.channel_id, n.session_id, n.title, n.link_path, n.created_at_utc, n.read_at_utc,
  (extract(epoch FROM n.created_at_utc) * 1000000)::bigint::text AS micros`;
// The inbox shows a notice once its INBOX delivery was processed by its worker.
const VISIBLE = `EXISTS (SELECT 1 FROM deliveries d WHERE d.notification_id=n.notification_id AND d.medium='INBOX' AND d.state='DELIVERED')`;

function notificationView(row: NotificationRow) {
  return {
    notificationId: row.notification_id,
    kind: row.kind,
    channelId: row.channel_id,
    sessionId: row.session_id,
    title: row.title,
    linkPath: row.link_path,
    createdAtUtc: row.created_at_utc.toISOString(),
    readAtUtc: row.read_at_utc ? row.read_at_utc.toISOString() : null,
  };
}

function sign(secret: string, user: string, body: string): string {
  return createHmac('sha256', secret).update(`notifications.inbox-cursor.v1|${user}|${body}`).digest('base64url');
}

async function list(deps: ApiDeps, request: Request, user: string): Promise<Result> {
  for (const key of request.query.keys()) if (key !== 'limit' && key !== 'cursor') throw invalid('Solo se admiten limit y cursor.');
  const limitText = request.query.get('limit');
  const limit = limitText === null ? 20 : Number(limitText);
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw invalid('limit debe estar entre 1 y 50.');
  let after: [string, string] | null = null;
  const cursor = request.query.get('cursor');
  if (cursor !== null) {
    const [encoded, mac] = cursor.split('.');
    const body = encoded ? Buffer.from(encoded, 'base64url').toString('utf8') : '';
    const expected = Buffer.from(sign(deps.cursorSecret, user, body));
    const supplied = Buffer.from(mac ?? '');
    const parts = body.split('|');
    if (cursor.length > 512 || expected.length !== supplied.length || !timingSafeEqual(expected, supplied) || parts.length !== 3
        || !/^\d+$/.test(parts[0]!) || !ID.test(parts[1]!)) {
      throw new ApiError(400, 'CURSOR_INVALID', 'El cursor no es válido.');
    }
    if (Number(parts[2]) + CURSOR_LIFETIME_S < Date.now() / 1000) throw new ApiError(410, 'CURSOR_EXPIRED', 'El cursor venció; reinicia la consulta.');
    after = [parts[0]!, parts[1]!];
  }
  const rows = await deps.db.query<NotificationRow>(
    `SELECT ${NOTIFICATION_COLUMNS} FROM notifications n WHERE n.user_id=$1 AND ${VISIBLE}
       AND ($2::bigint IS NULL OR ((extract(epoch FROM n.created_at_utc) * 1000000)::bigint, n.notification_id) < ($2::bigint, $3::text))
     ORDER BY n.created_at_utc DESC, n.notification_id DESC LIMIT $4`,
    [user, after?.[0] ?? null, after?.[1] ?? null, limit + 1]);
  const page = rows.rows.slice(0, limit);
  let nextCursor: string | null = null;
  if (rows.rows.length > limit) {
    const last = page[page.length - 1]!;
    const body = `${last.micros}|${last.notification_id}|${Math.floor(Date.now() / 1000)}`;
    nextCursor = `${Buffer.from(body).toString('base64url')}.${sign(deps.cursorSecret, user, body)}`;
  }
  return { status: 200, body: { items: page.map(notificationView), nextCursor } };
}

async function markRead(deps: ApiDeps, user: string, notificationId: string): Promise<Result> {
  // Reading again keeps the first readAtUtc; another account's notice is indistinguishable from a missing one.
  const row = await deps.db.query<NotificationRow>(
    `UPDATE notifications n SET read_at_utc=COALESCE(n.read_at_utc, now())
     WHERE n.notification_id=$1 AND n.user_id=$2 AND ${VISIBLE} RETURNING ${NOTIFICATION_COLUMNS}`, [notificationId, user]);
  if (!row.rowCount) throw new ApiError(404, 'NOTIFICATION_NOT_FOUND', 'Aviso no encontrado.');
  return { status: 200, body: notificationView(row.rows[0]!) };
}

// --- Preferences --------------------------------------------------------------------------------

async function mediums(db: Db | Tx, user: string) {
  const row = (await db.query<{ email_enabled: boolean; push_enabled: boolean; version: string }>(
    'SELECT email_enabled, push_enabled, version FROM medium_preferences WHERE user_id=$1', [user])).rows[0];
  return { emailEnabled: row?.email_enabled ?? false, pushEnabled: row?.push_enabled ?? false, version: Number(row?.version ?? 1) };
}

async function channel(db: Db | Tx, user: string, channelId: string) {
  const row = (await db.query<{ email_enabled: boolean; push_enabled: boolean; version: string }>(
    'SELECT email_enabled, push_enabled, version FROM channel_preferences WHERE user_id=$1 AND channel_id=$2', [user, channelId])).rows[0];
  return { channelId, emailEnabled: row?.email_enabled ?? false, pushEnabled: row?.push_enabled ?? false, version: Number(row?.version ?? 1) };
}

/**
 * Conditional update: a missing row behaves as version 1 with both media off. The version only grows
 * when a value changes; a stale expectedVersion is a 409 so concurrent edits are never lost.
 */
async function putMediums(deps: ApiDeps, request: Request, user: string): Promise<Result> {
  const body = object(request.body, ['emailEnabled', 'pushEnabled', 'expectedVersion'], ['emailEnabled', 'pushEnabled', 'expectedVersion']);
  if (typeof body.emailEnabled !== 'boolean' || typeof body.pushEnabled !== 'boolean') throw invalid('emailEnabled y pushEnabled deben ser booleanos.');
  const expected = version(body.expectedVersion);
  const updated = await deps.db.query(
    `INSERT INTO medium_preferences AS m (user_id, email_enabled, push_enabled, version)
     SELECT $1, $2, $3, CASE WHEN $2 OR $3 THEN 2 ELSE 1 END WHERE $4 = 1
     ON CONFLICT (user_id) DO UPDATE SET email_enabled=EXCLUDED.email_enabled, push_enabled=EXCLUDED.push_enabled,
       version=m.version + CASE WHEN (m.email_enabled, m.push_enabled) IS DISTINCT FROM (EXCLUDED.email_enabled, EXCLUDED.push_enabled) THEN 1 ELSE 0 END,
       updated_at_utc=now()
     WHERE m.version = $4`, [user, body.emailEnabled, body.pushEnabled, expected]);
  if (!updated.rowCount) throw new ApiError(409, 'VERSION_CONFLICT', 'Las preferencias cambiaron; vuelve a leerlas.');
  return { status: 200, body: await mediums(deps.db, user) };
}

async function putChannel(deps: ApiDeps, request: Request, user: string, channelId: string): Promise<Result> {
  const body = object(request.body, ['emailEnabled', 'pushEnabled', 'expectedVersion'], ['emailEnabled', 'pushEnabled', 'expectedVersion']);
  if (typeof body.emailEnabled !== 'boolean' || typeof body.pushEnabled !== 'boolean') throw invalid('emailEnabled y pushEnabled deben ser booleanos.');
  const expected = version(body.expectedVersion);
  const updated = await deps.db.query(
    `INSERT INTO channel_preferences AS c (user_id, channel_id, email_enabled, push_enabled, version)
     SELECT $1, $2, $3, $4, CASE WHEN $3 OR $4 THEN 2 ELSE 1 END WHERE $5 = 1
     ON CONFLICT (user_id, channel_id) DO UPDATE SET email_enabled=EXCLUDED.email_enabled, push_enabled=EXCLUDED.push_enabled,
       version=c.version + CASE WHEN (c.email_enabled, c.push_enabled) IS DISTINCT FROM (EXCLUDED.email_enabled, EXCLUDED.push_enabled) THEN 1 ELSE 0 END,
       updated_at_utc=now()
     WHERE c.version = $5`, [user, channelId, body.emailEnabled, body.pushEnabled, expected]);
  if (!updated.rowCount) throw new ApiError(409, 'VERSION_CONFLICT', 'La preferencia cambió; vuelve a leerla.');
  return { status: 200, body: await channel(deps.db, user, channelId) };
}

// --- Devices ------------------------------------------------------------------------------------

async function putDevice(deps: ApiDeps, request: Request, user: string, installationId: string): Promise<Result> {
  const body = object(request.body, ['installationId', 'platform', 'providerToken'], ['installationId', 'platform', 'providerToken']);
  if (body.installationId !== installationId) throw invalid('installationId del cuerpo y de la ruta deben coincidir.');
  if (body.platform !== 'ANDROID' && body.platform !== 'IOS' && body.platform !== 'WEB') throw invalid('platform no es válida.');
  if (typeof body.providerToken !== 'string' || !body.providerToken || body.providerToken.length > 4096) throw invalid('providerToken no es válido.');
  // Re-registering under another account moves the installation; a rotated token re-enables it.
  const row = (await deps.db.query<{ platform: string; enabled: boolean; version: string }>(
    `INSERT INTO devices AS d (installation_id, user_id, platform, provider_token, enabled, version) VALUES ($1,$2,$3,$4,true,1)
     ON CONFLICT (installation_id) DO UPDATE SET user_id=EXCLUDED.user_id, platform=EXCLUDED.platform,
       provider_token=EXCLUDED.provider_token, enabled=true, updated_at_utc=now(),
       version=d.version + CASE WHEN (d.user_id, d.platform, d.provider_token, d.enabled) IS DISTINCT FROM
         (EXCLUDED.user_id, EXCLUDED.platform, EXCLUDED.provider_token, true) THEN 1 ELSE 0 END
     RETURNING platform, enabled, version`, [installationId, user, body.platform, body.providerToken])).rows[0]!;
  return { status: 200, body: { installationId, platform: row.platform, enabled: row.enabled, version: Number(row.version) } };
}

async function deleteDevice(deps: ApiDeps, user: string, installationId: string): Promise<Result> {
  // Logout: only the caller's own association is removed; deleting twice is still 204.
  await deps.db.query('DELETE FROM devices WHERE installation_id=$1 AND user_id=$2', [installationId, user]);
  return { status: 204 };
}


