import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fixture as f } from './fixture.mjs';
import { randomUUID } from 'node:crypto';
import { replayAvailable, replayDenied } from './replay_checks.mjs';

const require = createRequire(new URL('../../../apps/web/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { default: AxeBuilder } = require('@axe-core/playwright');
if (!/^https:\/\/(127\.0\.0\.1|localhost):\d+$/.test(f.origin) || !/^streaming-vod-web-[a-f0-9]{10}$/.test(f.prefix)) throw new Error('Use run.py or full_stack.py');
const container = (role) => f.containers?.[role] ?? f.prefix + '-' + role;
const browser = await chromium.launch();
try {
  const owner = await browser.newContext({ baseURL: f.origin, ignoreHTTPSErrors: true });
  const admin = await browser.newContext({ baseURL: f.origin, ignoreHTTPSErrors: true });
  const visitor = await browser.newContext({ baseURL: f.origin, ignoreHTTPSErrors: true });
  await owner.addCookies(f.cookies);
  const [removedVod, publicVod] = f.vods;
  await replayAvailable(f, visitor, 0, expect);
  await replayAvailable(f, visitor, 1, expect);
  async function mutate(context, path, data, method = 'POST', key = randomUUID()) {
    const csrf = await (await context.request.get('/api/identity/csrf')).json();
    return context.request.fetch(path, { method, data, headers: { Origin: f.origin, [csrf.headerName]: csrf.token, 'Idempotency-Key': key } });
  }
  const handle = 'admin_' + randomUUID().replaceAll('-', '').slice(0, 8);
  const password = 'fixture-only administrator password';
  const registration = await mutate(admin, '/api/identity/registrations', { handle, email: handle + '@example.test', password });
  expect(registration.status()).toBe(201);
  const actor = await registration.json();
  // Explicit Core-owned administrative provisioning, never public role assignment.
  execFileSync('docker', ['exec', '-i', container('core-db'), 'psql', '-U', f.dbUser ?? 'fixture', '-d', 'core', '-v', 'target_user=' + actor.userId],
    { input: readFileSync(new URL('../../../services/core/scripts/provision-admin.sql', import.meta.url)), stdio: ['pipe', 'pipe', 'pipe'] });
  expect((await mutate(admin, '/api/identity/sessions', { login: handle, password })).status()).toBe(200);
  const tracks = [];
  for (const vod of f.vods) {
    const csrf = await (await owner.request.get('/api/identity/csrf')).json();
    const uploaded = await owner.request.post(`/api/vods/${vod.vodId}/subtitles`, {
      headers: { Origin: f.origin, [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() },
      multipart: {
        metadata: { name: 'metadata.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ language: 'es', label: 'Español', expectedMappingVersion: vod.mappingVersion })) },
        file: { name: 'es.vtt', mimeType: 'text/vtt', buffer: Buffer.from('WEBVTT\n\n00:00.000 --> 01:04.000\nContenido de prueba\n') },
      },
    });
    expect(uploaded.status()).toBe(201);
    tracks.push(await uploaded.json());
  }
  const removal = { resourceType: 'VOD', resourceId: removedVod.vodId, expectedVersion: removedVod.version, reason: 'Fixture removal' };
  expect((await visitor.request.post('/api/admin/content-removals', { data: removal })).status()).toBe(401);
  expect((await admin.request.post('/api/admin/content-removals', { data: removal })).status()).toBe(403);
  expect((await mutate(owner, '/api/admin/content-removals', removal)).status()).toBe(403);

  const page = await admin.newPage();
  async function assertAccessible() {
    const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
    expect(axe.violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) }))).toEqual([]);
  }
  await page.goto('/admin');
  await page.getByLabel('Identificador del VOD').fill(removedVod.vodId);
  await page.getByRole('button', { name: 'Consultar grabación', exact: true }).click();
  await expect(page.getByRole('heading', { name: removedVod.title, exact: true })).toBeVisible();
  await expect(page.getByLabel('Motivo de la retirada')).toBeFocused();
  await assertAccessible();
  await page.getByLabel('Motivo de la retirada').fill('Retirada verificada con proveedores reales');
  await page.getByRole('checkbox', { name: 'Confirmo la retirada de esta grabación.' }).check();
  // Real concurrent owner edit must produce a version conflict through Gateway/VOD.
  expect((await mutate(owner, `/api/vods/${removedVod.vodId}`, { expectedVersion: removedVod.version, title: 'Título actualizado' }, 'PATCH')).status()).toBe(200);
  await page.getByRole('button', { name: 'Confirmar retirada', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Consulta de nuevo');
  await expect(page.getByLabel('Identificador del VOD')).toBeFocused();
  await assertAccessible();
  await page.getByRole('button', { name: 'Consultar grabación', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Título actualizado' })).toBeVisible();
  await page.getByLabel('Motivo de la retirada').fill('Retirada verificada con proveedores reales');
  await page.getByRole('checkbox', { name: 'Confirmo la retirada de esta grabación.' }).check();
  const keys = [];
  const bodies = [];
  let accepted;
  // Lose only the first response AFTER the provider really accepted the command.
  await page.route('**/api/admin/content-removals', async (route) => {
    keys.push(route.request().headers()['idempotency-key']);
    bodies.push(route.request().postData());
    const response = await route.fetch();
    expect(response.status()).toBe(202);
    accepted = await response.json();
    if (keys.length === 1) await route.abort('failed');
    else await route.fulfill({ response });
  });
  await page.getByRole('button', { name: 'Confirmar retirada', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Reintentar retirada' })).toBeVisible();
  await expect(page.getByLabel('Motivo de la retirada')).toBeDisabled();
  await assertAccessible();
  await page.getByRole('button', { name: 'Reintentar retirada' }).click();
  await expect(page.getByText(/Retirada aceptada/)).toBeVisible();
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect(bodies[0]).toBe(bodies[1]);
  await expect(page.getByLabel('Resultado de la retirada')).toBeFocused();
  expect((await visitor.request.get(removedVod.playbackPath)).status()).toBe(404);
  expect((await visitor.request.get(tracks[0].contentPath)).status()).toBe(404);
  expect((await visitor.request.get(`/api/vods/${removedVod.vodId}`)).status()).toBe(404);
  await replayDenied(f, visitor, 0, expect);
  await page.getByRole('button', { name: 'Consultar estado de la retirada' }).click();
  await expect(page.getByText(/El replay se gestiona en Chat/)).toBeVisible();
  expect((await admin.request.get(accepted.statusUri)).status()).toBe(200);
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  }
  await assertAccessible();

  // A fresh grant permits actual media before suspension. Its lifetime is 60 seconds.
  const manifest = await visitor.request.get(publicVod.playbackPath);
  expect(manifest.status()).toBe(200);
  const firstUri = (text) => text.split(/\r?\n/).find((line) => line.trim() && !line.startsWith('#'));
  const variantUrl = new URL(firstUri(await manifest.text()), new URL(publicVod.playbackPath, f.origin)).href;
  const variant = await visitor.request.get(variantUrl);
  expect(variant.status()).toBe(200);
  const segment = new URL(firstUri(await variant.text()), variantUrl).href;
  expect((await visitor.request.get(segment)).status()).toBe(200);
  const userPath = `/api/admin/users/${f.owner.userId}/status`;
  expect((await mutate(admin, userPath, { status: 'SUSPENDED', expectedVersion: 0, reason: 'Fixture suspension' }, 'PUT')).status()).toBe(200);
  expect((await owner.request.get('/api/profile/me')).status()).toBe(401);
  expect((await visitor.request.get(publicVod.playbackPath)).status()).toBe(404);
  expect((await visitor.request.get(tracks[1].contentPath)).status()).toBe(404);
  await replayDenied(f, visitor, 1, expect);
  await expect.poll(async () => (await visitor.request.get(segment)).status(), { timeout: 15000 }).toBe(404);
  expect((await mutate(admin, userPath, { status: 'ACTIVE', expectedVersion: 1, reason: 'Fixture reactivation' }, 'PUT')).status()).toBe(200);
  await expect.poll(async () => (await visitor.request.get(publicVod.playbackPath)).status(), { timeout: 15000 }).toBe(200);
  expect((await visitor.request.get(removedVod.playbackPath)).status()).toBe(404);
  expect((await visitor.request.get(tracks[0].contentPath)).status()).toBe(404);
  await replayAvailable(f, visitor, 1, expect);
  await replayDenied(f, visitor, 0, expect);
  expect((await owner.request.get('/api/profile/me')).status()).toBe(401);

  async function accountQueue(name = 'vod.account') {
    const response = await admin.request.get(f.broker.url + '/api/queues/streaming-p2/' + name,
      { headers: { Authorization: f.broker.authorization } });
    expect(response.status()).toBe(200);
    return response.json();
  }
  // Drain the two real account changes first. An enqueued event is not proof of consumption.
  await expect.poll(async () => {
    const queue = await accountQueue();
    return queue.messages_ready === 0 && queue.messages_unacknowledged === 0 && (queue.message_stats?.ack ?? 0) >= 2;
  }, { timeout: 30000 }).toBe(true);
  execFileSync('docker', ['stop', container('vod')], { stdio: 'pipe' });
  const beforeStale = await accountQueue();
  const failedQueues = ['vod.account.retry.1', 'vod.account.retry.5', 'vod.account.retry.30', 'vod.account.dlq'];
  const beforeFailures = await Promise.all(failedQueues.map(accountQueue));
  try {
    const stale = { eventId: randomUUID(), eventType: 'account.status.changed.v1', schemaVersion: 1, producer: 'core', aggregateId: 'account:' + f.owner.userId, sequence: 1, occurredAtUtc: new Date().toISOString(), correlationId: 'req_' + randomUUID().replaceAll('-', ''), payload: { userId: f.owner.userId, accountVersion: 1, status: 'SUSPENDED' } };
    const publish = await admin.request.post(f.broker.url + '/api/exchanges/streaming-p2/p2.events/publish', { headers: { Authorization: f.broker.authorization }, data: { properties: { content_type: 'application/json', delivery_mode: 2 }, routing_key: 'account.status.changed.v1', payload: JSON.stringify(stale), payload_encoding: 'string' } });
    expect(publish.status()).toBe(200);
    expect((await publish.json()).routed).toBe(true);
    await expect.poll(async () => (await accountQueue()).messages_ready, { timeout: 30000 }).toBe(1);
  } finally {
    // The database and broker survive this consumer process restart.
    execFileSync('docker', ['start', container('vod')], { stdio: 'pipe' });
  }
  await expect.poll(async () => {
    const queue = await accountQueue();
    return queue.messages_ready === 0 && queue.messages_unacknowledged === 0 &&
      (queue.message_stats?.ack ?? 0) > (beforeStale.message_stats?.ack ?? 0);
  }, { timeout: 30000 }).toBe(true);
  // A failed handler can ACK after routing to retry/DLQ. Also require no such publications.
  const afterFailures = await Promise.all(failedQueues.map(accountQueue));
  for (let index = 0; index < failedQueues.length; index++) {
    expect(afterFailures[index].message_stats?.publish ?? 0).toBe(beforeFailures[index].message_stats?.publish ?? 0);
    expect(afterFailures[index].messages).toBe(0);
  }
  await expect.poll(async () => (await visitor.request.get(publicVod.playbackPath)).status(), { timeout: 30000 }).toBe(200);
  expect((await visitor.request.get(segment)).status()).toBe(200);
  expect((await visitor.request.get(removedVod.playbackPath)).status()).toBe(404);
  // Lost authority does not grant new protected resources. Existing segment grants expire.
  await replayAvailable(f, visitor, 1, expect);
  await replayDenied(f, visitor, 0, expect);
  execFileSync('docker', ['stop', container('core')], { stdio: 'pipe' });
  try {
    expect((await visitor.request.get(publicVod.playbackPath)).status()).toBe(503);
    expect((await visitor.request.get(tracks[1].contentPath)).status()).toBe(503);
    await expect.poll(async () => (await visitor.request.get(segment)).status(), { timeout: 70000, intervals: [1000, 3000, 5000] }).toBe(401);
    if (f.replay) expect((await visitor.request.get(`/api/chat/vods/${publicVod.vodId}/messages?fromMs=0&toMs=10000`)).status()).toBe(503);
  } finally { execFileSync('docker', ['start', container('core')], { stdio: 'pipe' }); }
  await expect.poll(async () => (await visitor.request.get(publicVod.playbackPath)).status(), { timeout: 60000 }).toBe(200);
  expect((await visitor.request.get(removedVod.playbackPath)).status()).toBe(404);
  console.log('PASS: real HTTPS Web/Core/Gateway/VOD/S3/AMQP; role and CSRF; lookup/version conflict; accepted removal and idempotent lost response; status/focus/Axe/responsive; direct manifest/track/segment access; suspension, reactivation, stale event and VOD restart; Core outage and 60s grant expiry; retired content stays retired.');
  console.log('Pending: live source cutoff, other resource types and human assistive/visual acceptance. Replay exercised: ' + Boolean(f.replay));
} finally { await browser.close(); }
