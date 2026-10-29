// Production SSR HTTP checks with a fake domain API; no real accounts or credentials.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';

const source = JSON.parse(await readFile(new URL('../../../contracts/p1.json', import.meta.url)));
const channel = source.schemas.ChannelBootstrap.examples[0];
const profile = { ...source.schemas.Profile.examples[0], userId: 'usr_fan' };
const stream = { ...source.schemas.PublicStream.examples[0], title: 'Directo SSR fixture' };
let session = source.schemas.PublicSession.examples[0];
let channelUnavailable = false;
const requests = [];
const upstream = createServer((req, res) => {
  requests.push({ path: req.url, cookie: req.headers.cookie ?? '' });
  res.setHeader('Content-Type', 'application/json');
  const replies = {
    '/api/channels/by-handle/fixture_01': channel,
    '/api/channels/chn_fixture': channel,
    '/api/profile/me': profile,
    '/api/identity/public/users/usr_fan': { userId: 'usr_fan', handle: 'fan_fixture' },
    '/api/channels/chn_fixture/followers/me': {
      channelId: 'chn_fixture',
      following: true,
      followVersion: 1,
      followedAtUtc: '2026-10-08T20:00:00Z',
    },
  };
  if (channelUnavailable && req.url === '/api/channels/chn_fixture') {
    res.statusCode = 503;
    res.end('{}');
    return;
  }
  if (!replies[req.url]) res.statusCode = 404;
  res.end(JSON.stringify(replies[req.url] ?? { code: 'NOT_FOUND' }));
});
upstream.listen(0, '127.0.0.1');
await once(upstream, 'listening');
const streamingRequests = [];
const streaming = createServer((req, res) => {
  streamingRequests.push({ path: req.url, cookie: req.headers.cookie ?? '' });
  res.setHeader('Content-Type', 'application/json');
  const replies = {
    '/api/streams/str_fixture': stream,
    '/api/streams/sessions/ses_fixture': session,
    '/api/streams/str_offline': {
      ...stream,
      streamId: 'str_offline',
      sessionId: null,
      status: 'ENDED',
      availability: 'OFFLINE',
    },
  };
  if (!replies[req.url]) res.statusCode = req.url === '/api/streams/unavailable' ? 503 : 404;
  res.end(JSON.stringify(replies[req.url] ?? { code: 'NOT_FOUND' }));
});
streaming.listen(0, '127.0.0.1');
await once(streaming, 'listening');
const reservation = createServer();
reservation.listen(0, '127.0.0.1');
await once(reservation, 'listening');
const port = reservation.address().port;
await new Promise((resolve) => reservation.close(resolve));
const server = spawn(process.execPath, ['server.mjs'], {
  cwd: new URL('../', import.meta.url),
  env: {
    ...process.env,
    PORT: String(port),
    NODE_ENV: 'production',
    WEB_API_UPSTREAM: `http://127.0.0.1:${upstream.address().port}`,
    WEB_STREAMING_UPSTREAM: `http://127.0.0.1:${streaming.address().port}`,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let output = '';
server.stdout.on('data', (chunk) => {
  output += chunk;
});
server.stderr.on('data', (chunk) => {
  output += chunk;
});
const base = `http://127.0.0.1:${port}`;
try {
  for (let attempt = 0; ; attempt++) {
    try {
      await fetch(base);
      break;
    } catch {
      if (attempt === 100 || server.exitCode !== null)
        throw new Error(`SSR failed to start: ${output}`);
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  }
  const anonymous = await fetch(base + '/channels/fixture_01');
  assert.equal(anonymous.status, 200);
  assert.equal(anonymous.headers.get('cache-control'), 'no-store, private');
  const html = await anonymous.text();
  assert.match(html, /<h1>Fixture<\/h1>/);
  assert.match(html, /Inicia sesión para seguir/);
  assert.doesNotMatch(html, />Siguiendo</);
  assert.ok(requests.every((r) => !r.cookie));
  for (const path of ['/admin', '/notifications', '/studio', '/profile']) {
    const denied = await fetch(base + path, { redirect: 'manual' });
    assert.equal(denied.status, 302);
    assert.equal(denied.headers.get('location'), '/login');
  }
  const headers = {
    Cookie: 'stream_session=fixture-credential; XSRF-TOKEN=fixture; unrelated=private',
  };
  const authenticated = await fetch(base + '/channels/fixture_01', { headers });
  assert.match(await authenticated.text(), />Siguiendo</);
  assert.ok(requests.some((r) => r.cookie.includes('stream_session=fixture-credential')));
  assert.ok(requests.every((r) => !r.cookie.includes('unrelated')));
  const live = await fetch(base + '/watch/str_fixture', { headers });
  assert.equal(live.status, 200);
  assert.equal(live.headers.get('cache-control'), 'no-store, private');
  const liveHtml = await live.text();
  assert.match(liveHtml, /<h1>Directo SSR fixture<\/h1>/);
  assert.match(liveHtml, /<video[^>]*aria-label="Reproductor de Directo SSR fixture"/);
  assert.doesNotMatch(liveHtml, /<video[^>]*\ssrc=|<select[^>]*>[^]*720p/);
  assert.doesNotMatch(liveHtml, /fixture-credential|127\.0\.0\.1/);
  assert.deepEqual(
    streamingRequests.map((r) => r.path),
    ['/api/streams/str_fixture', '/api/streams/sessions/ses_fixture'],
  );
  assert.ok(streamingRequests.every((r) => !r.cookie));
  assert.ok(requests.every((r) => !r.path.startsWith('/api/streams/')));
  const offline = await fetch(base + '/watch/str_offline');
  assert.equal(offline.status, 200);
  const offlineHtml = await offline.text();
  assert.match(offlineHtml, /El canal está offline/);
  assert.doesNotMatch(offlineHtml, /<video/);
  channelUnavailable = true;
  const partial = await fetch(base + '/watch/str_fixture');
  assert.equal(partial.status, 200);
  assert.match(await partial.text(), /Reproductor de Directo SSR fixture/);
  channelUnavailable = false;
  session = { ...session, streamId: 'str_other' };
  assert.equal((await fetch(base + '/watch/str_fixture')).status, 503);
  session = source.schemas.PublicSession.examples[0];
  assert.equal((await fetch(base + '/watch/unavailable')).status, 503);
  assert.equal((await fetch(base + '/watch/missing')).status, 404);
  for (const path of ['/admin', '/notifications']) {
    const page = await fetch(base + path, { headers });
    assert.equal(page.status, 200);
    assert.equal(page.headers.get('cache-control'), 'no-store, private');
    assert.match(
      await page.text(),
      path === '/admin' ? /<h1[^>]*>Administración<\/h1>/ : /<h1>Notificaciones<\/h1>/,
    );
  }
  for (const path of ['/channels/missing', '/missing'])
    assert.equal((await fetch(base + path)).status, 404);
  const again = await (await fetch(base + '/channels/fixture_01')).text();
  assert.doesNotMatch(again, /usr_fan|fixture-credential|>Siguiendo</);
  console.log(
    'PASS SSR: channel/watch HTML, public Streaming isolation, partial failure, session consistency, administration/notifications and 404',
  );
} finally {
  const closed = once(server, 'exit');
  server.kill('SIGTERM');
  if (server.exitCode === null) await closed;
  await new Promise((resolve) => upstream.close(resolve));
  await new Promise((resolve) => streaming.close(resolve));
}
