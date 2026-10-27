// Production SSR HTTP checks with a fake domain API; no real accounts or credentials.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';

const source = JSON.parse(await readFile(new URL('../../../contracts/p1.json', import.meta.url)));
const channel = source.schemas.ChannelBootstrap.examples[0];
const profile = { ...source.schemas.Profile.examples[0], userId: 'usr_fan' };
const requests = [];
const upstream = createServer((req, res) => {
  requests.push({ path: req.url, cookie: req.headers.cookie ?? '' });
  res.setHeader('Content-Type', 'application/json');
  const replies = {
    '/api/channels/by-handle/fixture_01': channel,
    '/api/profile/me': profile,
    '/api/identity/public/users/usr_fan': { userId: 'usr_fan', handle: 'fan_fixture' },
    '/api/channels/chn_fixture/followers/me': {
      channelId: 'chn_fixture',
      following: true,
      followVersion: 1,
      followedAtUtc: '2026-10-08T20:00:00Z',
    },
  };
  if (!replies[req.url]) res.statusCode = 404;
  res.end(JSON.stringify(replies[req.url] ?? { code: 'NOT_FOUND' }));
});
upstream.listen(0, '127.0.0.1');
await once(upstream, 'listening');
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
    'PASS SSR: HTML, session isolation, cookie filtering, administration/notifications and 404',
  );
} finally {
  const closed = once(server, 'exit');
  server.kill('SIGTERM');
  if (server.exitCode === null) await closed;
  await new Promise((resolve) => upstream.close(resolve));
}
