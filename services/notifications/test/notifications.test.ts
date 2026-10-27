// Integration tests against a real PostgreSQL (NOTIFICATIONS_TEST_DB_URL). Core, SMTP and the broker are fakes.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { after, before, beforeEach, describe, it } from 'node:test';

import { HandlerError, type IncomingMessage } from '../src/broker.ts';
import { CoreError, type CorePort, type RecipientsPage } from '../src/core.ts';
import { createPool, type Db, migrate } from '../src/db.ts';
import { deliveryHandler, type PushProvider } from '../src/deliveries.ts';
import { followHandler } from '../src/follow.ts';
import { apiHandler } from '../src/http.ts';
import { liveHandler } from '../src/live.ts';
import { silentLog } from '../src/log.ts';
import type { MailMessage, Mailer, SendOutcome } from '../src/mail.ts';
import { drainOutbox } from '../src/outbox.ts';
import { recoveryHandler } from '../src/recovery.ts';

const url = process.env.NOTIFICATIONS_TEST_DB_URL;
const SECRET = 'fixture-cursor-secret-at-least-32-bytes';
let db: Db;

class FakeCore implements CorePort {
  followers = new Map<string, string[]>();
  sessions = new Map<string, string>();
  emails = new Map<string, string>();
  recipientCalls = 0;
  failRecipients = false;
  claims: { attempt: number; key: string }[] = [];
  results: { attempt: number; status: string; key: string }[] = [];
  claimError: CoreError | null = null;
  async sessionUser(credential: string) { return this.sessions.get(credential) ?? null; }
  async recipients(channelId: string, _sessionId: string, cursor: string | null): Promise<RecipientsPage> {
    this.recipientCalls++;
    if (this.failRecipients) throw new CoreError(503, 'DOWN');
    const all = this.followers.get(channelId) ?? [];
    const start = cursor ? Number(cursor) : 0;
    const items = all.slice(start, start + 2).map((userId) => ({ userId }));
    return { snapshotId: 'rsn_fixture', items, nextCursor: start + 2 < all.length ? String(start + 2) : null };
  }
  async emailTarget(userId: string) {
    const email = this.emails.get(userId);
    if (!email) throw new CoreError(404, 'ACCOUNT_NOT_FOUND');
    return email;
  }
  async channel() { return { handle: 'canal_laura', displayName: 'Canal de Laura' }; }
  async claimRecovery(recoveryId: string, deliveryId: string, attempt: number, key: string) {
    this.claims.push({ attempt, key });
    if (this.claimError) throw this.claimError;
    return { recoveryId, deliveryId, attempt, recipient: 'fan@example.test',
      resetUrl: 'https://localhost:3445/reset-password#token=secret', expiresAtUtc: '2026-10-26T19:00:00Z' };
  }
  async reportRecovery(_r: string, _d: string, attempt: number, status: string, key: string) { this.results.push({ attempt, status, key }); }
}

class FakeMailer implements Mailer {
  sent: MailMessage[] = [];
  outcomes: SendOutcome[] = [];
  async send(message: MailMessage) {
    const outcome = this.outcomes.shift() ?? 'DELIVERED';
    if (outcome === 'DELIVERED') this.sent.push(message);
    return outcome;
  }
}

const json = (value: unknown): IncomingMessage => ({ body: JSON.stringify(value), contentType: 'application/json', routingKey: 'test' });

function liveEvent(eventId: string, sessionId = 'ses_1', channelId = 'chn_1') {
  return json({ eventId, eventType: 'stream.session.changed.v1', schemaVersion: 1, producer: 'streaming',
    aggregateId: `session:${sessionId}`, sequence: 2, occurredAtUtc: '2026-10-26T18:00:00Z', correlationId: 'req_live',
    payload: { streamId: 'str_1', sessionId, streamGeneration: 1, sessionVersion: 2, status: 'LIVE', availability: 'PLAYABLE',
      channelId, firstLiveAtUtc: '2026-10-26T18:00:00Z', initialLive: true } });
}

/** Turns each pending outbox job into the message its worker queue would receive. */
async function jobs(medium: string): Promise<IncomingMessage[]> {
  const rows = await db.query<{ payload: string }>(
    "SELECT payload FROM outbox WHERE routing_key=$1 AND published_at_utc IS NULL ORDER BY created_at_utc", [`notification.delivery.requested.v1.${medium}`]);
  await db.query('UPDATE outbox SET published_at_utc=now() WHERE routing_key=$1', [`notification.delivery.requested.v1.${medium}`]);
  return rows.rows.map((row) => ({ body: row.payload, contentType: 'application/json', routingKey: medium }));
}

async function count(sql: string, params: unknown[] = []): Promise<number> {
  return Number((await db.query<{ n: string }>(sql, params)).rows[0]!.n);
}

describe('notifications', { skip: !url }, () => {
  let core: FakeCore;
  let mailer: FakeMailer;
  let push: PushProvider & { calls: number; next: SendOutcome | 'INVALID_TOKEN' };
  let deps: Parameters<typeof deliveryHandler>[1];

  before(async () => {
    db = createPool(url!, null);
    await migrate(db);
    await migrate(db); // second run is a no-op
  });
  after(async () => db?.end());
  beforeEach(async () => {
    await db.query(`TRUNCATE inbox_events, live_sessions, notifications, deliveries, outbox, medium_preferences, channel_preferences,
      devices, follow_projection, recovery_attempts, idempotency CASCADE`);
    core = new FakeCore();
    core.followers.set('chn_1', ['usr_a', 'usr_b', 'usr_c']);
    core.emails.set('usr_a', 'a@example.test');
    mailer = new FakeMailer();
    push = { calls: 0, next: 'DELIVERED', async send() { this.calls++; return this.next; } };
    deps = { db, core, mailer, push, webOrigin: 'https://localhost:3445' };
  });

  it('creates one notice per follower and never selects again for the same session', async () => {
    const live = liveHandler(db, core);
    assert.equal(await live(liveEvent('evt_1')), 'SELECTED');
    assert.equal(await count('SELECT count(*) n FROM notifications'), 3);
    assert.equal(await live(liveEvent('evt_1')), 'DUPLICATE');
    core.followers.set('chn_1', ['usr_a', 'usr_b', 'usr_c', 'usr_late']);
    assert.equal(await live(liveEvent('evt_2')), 'ALREADY_SELECTED'); // reconnect/redelivery with another id
    assert.equal(await count('SELECT count(*) n FROM notifications'), 3);
    assert.equal(await count("SELECT count(*) n FROM deliveries WHERE medium='INBOX'"), 3);
    assert.equal(await count('SELECT count(*) n FROM outbox'), 3);
    const title = (await db.query<{ title: string; link_path: string }>('SELECT title, link_path FROM notifications LIMIT 1')).rows[0]!;
    assert.deepEqual(title, { title: 'Canal de Laura está en vivo', link_path: '/channels/canal_laura' });
  });

  it('keeps progress when Core fails mid-way and rejects invalid or conflicting events', async () => {
    const live = liveHandler(db, core);
    core.failRecipients = true;
    await assert.rejects(live(liveEvent('evt_1')), (e: unknown) => e instanceof HandlerError && !e.permanent);
    core.failRecipients = false;
    assert.equal(await live(liveEvent('evt_1')), 'SELECTED');
    assert.equal(await count('SELECT count(*) n FROM notifications'), 3);
    const notInitial = JSON.parse(liveEvent('evt_9').body);
    notInitial.payload.initialLive = false;
    await assert.rejects(live(json(notInitial)), (e: unknown) => e instanceof HandlerError && e.permanent);
    const changed = JSON.parse(liveEvent('evt_1').body);
    changed.correlationId = 'other';
    await assert.rejects(live(json(changed)), (e: unknown) => e instanceof HandlerError && e.reason === 'EVENT_ID_CONFLICT');
  });

  it('keeps the original medium selection when resuming an interrupted recipient page', async () => {
    const live = liveHandler(db, core);
    let fail = true;
    const recipients = core.recipients.bind(core);
    core.recipients = async (...args) => {
      if (args[2] && fail) throw new CoreError(503, 'DOWN');
      return recipients(...args);
    };
    await assert.rejects(live(liveEvent('evt_1')), (e: unknown) => e instanceof HandlerError && !e.permanent);
    assert.equal(await count('SELECT count(*) n FROM notifications'), 2);
    await db.query("INSERT INTO medium_preferences VALUES ('usr_a', true, false, 2)");
    await db.query("INSERT INTO channel_preferences VALUES ('usr_a', 'chn_1', true, false, 2)");
    // Simulate cursor expiry forcing a replay of the already committed first page.
    await db.query('UPDATE live_sessions SET recipients_cursor=NULL');
    fail = false;
    assert.equal(await live(liveEvent('evt_1')), 'SELECTED');
    assert.equal(await count('SELECT count(*) n FROM notifications'), 3);
    assert.equal(await count("SELECT count(*) n FROM deliveries WHERE medium='EMAIL'"), 0);
    assert.equal(await count('SELECT count(*) n FROM outbox'), 3);
  });

  it('delivers email only when the medium and the channel are enabled, and push failure keeps email and inbox', async () => {
    await db.query("INSERT INTO medium_preferences VALUES ('usr_a', true, true, 2)");
    await db.query("INSERT INTO channel_preferences VALUES ('usr_a', 'chn_1', true, true, 2)");
    await db.query("INSERT INTO medium_preferences VALUES ('usr_b', true, false, 2)"); // channel switch still off
    await db.query("INSERT INTO devices VALUES ('ins_a', 'usr_a', 'ANDROID', 'provider-token', true, 1)");
    await liveHandler(db, core)(liveEvent('evt_1'));
    assert.equal(await count("SELECT count(*) n FROM deliveries WHERE medium='EMAIL'"), 1);
    assert.equal(await count("SELECT count(*) n FROM deliveries WHERE medium='PUSH'"), 1);

    for (const message of await jobs('inbox')) assert.equal(await deliveryHandler('INBOX', deps)(message), 'DELIVERED');
    const [email] = await jobs('email');
    assert.equal(await deliveryHandler('EMAIL', deps)(email!), 'DELIVERED');
    assert.equal(mailer.sent[0]!.to, 'a@example.test');
    assert.match(mailer.sent[0]!.text, /https:\/\/localhost:3445\/channels\/canal_laura/);

    push.next = 'RETRYABLE_FAILURE';
    const [pushJob] = await jobs('push');
    await assert.rejects(deliveryHandler('PUSH', deps)(pushJob!), (e: unknown) => e instanceof HandlerError && !e.permanent);
    // Redelivering the email job (or the push retry) never sends the email again.
    assert.equal(await deliveryHandler('EMAIL', deps)(email!), 'ALREADY_DELIVERED');
    push.next = 'DELIVERED';
    assert.equal(await deliveryHandler('PUSH', deps)(pushJob!), 'DELIVERED');
    assert.equal(mailer.sent.length, 1);
    assert.equal(push.calls, 2);
  });

  it('classifies SMTP failures, interrupted attempts and invalid push tokens without loops', async () => {
    await db.query("INSERT INTO medium_preferences VALUES ('usr_a', true, true, 2)");
    await db.query("INSERT INTO channel_preferences VALUES ('usr_a', 'chn_1', true, true, 2)");
    await db.query("INSERT INTO devices VALUES ('ins_a', 'usr_a', 'ANDROID', 'provider-token', true, 1)");
    await liveHandler(db, core)(liveEvent('evt_1'));
    const [email] = await jobs('email');
    mailer.outcomes = ['RETRYABLE_FAILURE', 'PERMANENT_FAILURE'];
    await assert.rejects(deliveryHandler('EMAIL', deps)(email!), (e: unknown) => e instanceof HandlerError && !e.permanent);
    assert.equal(await deliveryHandler('EMAIL', deps)(email!), 'PERMANENT_FAILURE');
    assert.equal(await deliveryHandler('EMAIL', deps)(email!), 'ALREADY_PERMANENT_FAILURE');

    const [pushJob] = await jobs('push');
    await db.query("UPDATE deliveries SET state='IN_PROGRESS' WHERE medium='PUSH'"); // worker died mid-send
    assert.equal(await deliveryHandler('PUSH', deps)(pushJob!), 'UNKNOWN');
    assert.equal(push.calls, 0);

    await db.query("UPDATE deliveries SET state='PENDING' WHERE medium='PUSH'");
    push.next = 'INVALID_TOKEN';
    assert.equal(await deliveryHandler('PUSH', deps)(pushJob!), 'PERMANENT_FAILURE');
    assert.equal(await count("SELECT count(*) n FROM devices WHERE enabled"), 0);
  });

  it('completes interrupted inbox deliveries without external sends', async () => {
    await liveHandler(db, core)(liveEvent('evt_1'));
    const [job] = await jobs('inbox');
    const deliveryId = JSON.parse(job!.body).payload.deliveryId;
    await db.query("UPDATE deliveries SET state='IN_PROGRESS' WHERE delivery_id=$1", [deliveryId]);
    assert.equal(await deliveryHandler('INBOX', deps)(job!), 'DELIVERED');
    assert.equal(await deliveryHandler('INBOX', deps)(job!), 'ALREADY_DELIVERED');
    assert.equal(await count("SELECT count(*) n FROM deliveries WHERE delivery_id=$1 AND state='DELIVERED'", [deliveryId]), 1);
    assert.equal(mailer.sent.length, 0);
    assert.equal(push.calls, 0);
  });

  it('does not mark an active email send UNKNOWN when a duplicate arrives', async () => {
    await db.query("INSERT INTO medium_preferences VALUES ('usr_a', true, false, 2)");
    await db.query("INSERT INTO channel_preferences VALUES ('usr_a', 'chn_1', true, false, 2)");
    await liveHandler(db, core)(liveEvent('evt_1'));
    const [job] = await jobs('email');
    const entered = Promise.withResolvers<void>();
    const release = Promise.withResolvers<void>();
    const handler = deliveryHandler('EMAIL', { ...deps, mailer: { async send(message) {
      entered.resolve();
      await release.promise;
      return mailer.send(message);
    } } });
    const first = handler(job!);
    await entered.promise;
    try {
      await assert.rejects(handler(job!), (e: unknown) => e instanceof HandlerError && e.reason === 'DELIVERY_BUSY' && !e.permanent);
      assert.equal(await count("SELECT count(*) n FROM deliveries WHERE medium='EMAIL' AND state='IN_PROGRESS'"), 1);
    } finally { release.resolve(); }
    assert.equal(await first, 'DELIVERED');
    assert.equal(await handler(job!), 'ALREADY_DELIVERED');
    assert.equal(mailer.sent.length, 1);
  });

  it('does not disable a newly rotated push token after an old token fails', async () => {
    await db.query("INSERT INTO medium_preferences VALUES ('usr_a', false, true, 2)");
    await db.query("INSERT INTO channel_preferences VALUES ('usr_a', 'chn_1', false, true, 2)");
    await db.query("INSERT INTO devices VALUES ('ins_a', 'usr_a', 'ANDROID', 'old-token', true, 1)");
    await liveHandler(db, core)(liveEvent('evt_1'));
    const [job] = await jobs('push');
    const provider: PushProvider = { async send(token) {
      assert.equal(token, 'old-token');
      await db.query("UPDATE devices SET provider_token='new-token', version=2 WHERE installation_id='ins_a'");
      return 'INVALID_TOKEN';
    } };
    assert.equal(await deliveryHandler('PUSH', { ...deps, push: provider })(job!), 'PERMANENT_FAILURE');
    assert.equal(await count('SELECT count(*) n FROM devices WHERE enabled'), 1);
  });

  it('serializes recovery jobs across handlers and leaves other deliveries independent', async () => {
    const job = (n: string) => json({ eventType: 'credential.recovery.delivery.requested.v1', producer: 'core',
      aggregateId: `recovery:rec_${n.repeat(32)}`, payload: { recoveryId: `rec_${n.repeat(32)}`, deliveryId: `del_${n.repeat(32)}` } });
    const entered = Promise.withResolvers<void>();
    const release = Promise.withResolvers<void>();
    const slow: Mailer = { async send(message) { entered.resolve(); await release.promise; return mailer.send(message); } };
    const first = recoveryHandler(db, core, slow, silentLog)(job('a'));
    await entered.promise;
    try {
      await assert.rejects(recoveryHandler(db, core, mailer, silentLog)(job('a')),
        (e: unknown) => e instanceof HandlerError && e.reason === 'DELIVERY_BUSY' && !e.permanent);
      assert.equal(await recoveryHandler(db, core, mailer, silentLog)(job('b')), 'DELIVERED');
    } finally { release.resolve(); }
    assert.equal(await first, 'DELIVERED');
    assert.equal(await recoveryHandler(db, core, mailer, silentLog)(job('a')), 'ALREADY_REPORTED');
    assert.equal(mailer.sent.length, 2);
    assert.equal(core.claims.length, 2);
    assert.equal(core.results.length, 2);
  });

  it('applies follow changes by version and turns off channel switches on unfollow', async () => {
    const follow = followHandler(db);
    const event = (id: string, version: number, following: boolean) => json({ eventId: id, eventType: 'channel.follow.changed.v1',
      schemaVersion: 1, producer: 'core', aggregateId: 'follow:chn_1:usr_a', sequence: version, occurredAtUtc: '2026-10-26T18:00:00Z',
      correlationId: 'req', payload: { channelId: 'chn_1', followerUserId: 'usr_a', following, followVersion: version, changedAtUtc: '2026-10-26T18:00:00Z' } });
    await db.query("INSERT INTO channel_preferences VALUES ('usr_a', 'chn_1', true, false, 2)");
    assert.equal(await follow(event('11111111-1111-4111-8111-111111111111', 2, false)), 'APPLIED');
    assert.equal(await follow(event('22222222-2222-4222-8222-222222222222', 1, true)), 'STALE');
    assert.equal(await follow(event('11111111-1111-4111-8111-111111111111', 2, false)), 'DUPLICATE');
    assert.deepEqual((await db.query('SELECT email_enabled, push_enabled, version FROM channel_preferences')).rows,
      [{ email_enabled: false, push_enabled: false, version: '3' }]);
    assert.equal((await db.query('SELECT following FROM follow_projection')).rows[0].following, false);
  });

  it('delivers recovery email once, reports UNKNOWN after a crash while sending, and stops on expiry', async () => {
    const recovery = recoveryHandler(db, core, mailer, silentLog);
    const job = (n: string) => json({ eventId: '33333333-3333-4333-8333-333333333333', eventType: 'credential.recovery.delivery.requested.v1',
      schemaVersion: 1, producer: 'core', aggregateId: `recovery:rec_${n.repeat(32)}`, sequence: 1, occurredAtUtc: '2026-10-26T18:00:00Z',
      correlationId: 'req', payload: { recoveryId: `rec_${n.repeat(32)}`, deliveryId: `del_${n.repeat(32)}` } });
    assert.equal(await recovery(job('a')), 'DELIVERED');
    assert.equal(mailer.sent.length, 1);
    assert.deepEqual(core.results.map((r) => [r.attempt, r.status]), [[1, 'DELIVERED']]);
    assert.equal(await recovery(job('a')), 'ALREADY_REPORTED');
    const stored = JSON.stringify((await db.query('SELECT * FROM recovery_attempts')).rows);
    assert.doesNotMatch(stored, /fan@example|reset-password|secret/);

    await db.query(`INSERT INTO recovery_attempts (delivery_id, recovery_id, attempt, phase, claim_key)
      VALUES ('del_${'b'.repeat(32)}', 'rec_${'b'.repeat(32)}', 1, 'SENDING', gen_random_uuid())`);
    assert.equal(await recovery(job('b')), 'UNKNOWN');
    assert.equal(mailer.sent.length, 1);

    mailer.outcomes = ['RETRYABLE_FAILURE'];
    await assert.rejects(recovery(job('c')), (e: unknown) => e instanceof HandlerError && !e.permanent);
    assert.equal(await recovery(job('c')), 'DELIVERED');
    assert.deepEqual(core.results.slice(-2).map((r) => [r.attempt, r.status]), [[1, 'RETRYABLE_FAILURE'], [2, 'DELIVERED']]);

    core.claimError = new CoreError(410, 'RECOVERY_EXPIRED');
    assert.equal(await recovery(job('d')), 'EXPIRED');
    assert.equal(await recovery(job('d')), 'ALREADY_EXPIRED');
  });

  it('publishes outbox jobs only after confirmation', async () => {
    await liveHandler(db, core)(liveEvent('evt_1'));
    const failing = { publish: async () => { throw new Error('down'); } };
    assert.equal(await drainOutbox(db, failing, silentLog), 0);
    assert.equal(await count('SELECT count(*) n FROM outbox WHERE published_at_utc IS NULL'), 3);
    await db.query('UPDATE outbox SET next_attempt_at_utc=now()');
    const seen: string[] = [];
    assert.equal(await drainOutbox(db, { publish: async (key) => { seen.push(key); } }, silentLog), 3);
    assert.deepEqual(new Set(seen), new Set(['notification.delivery.requested.v1.inbox']));
  });

  describe('HTTP API', () => {
    let server: Server;
    let base: string;
    before(async () => {
      server = createServer((req, res) => void apiHandler({ db, core, cursorSecret: SECRET, log: silentLog })(req, res));
      await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
      base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    });
    after(() => new Promise<void>((resolve) => server.close(() => resolve())));

    const call = (method: string, path: string, credential: string | null, options: { body?: unknown; key?: string; csrf?: boolean } = {}) => {
      const headers: Record<string, string> = {};
      const cookies: string[] = [];
      if (credential) cookies.push(`stream_session=${credential}`);
      if (options.csrf !== false) { cookies.push('XSRF-TOKEN=fixture'); headers['X-XSRF-TOKEN'] = 'fixture'; }
      if (cookies.length) headers.Cookie = cookies.join('; ');
      if (options.key) headers['Idempotency-Key'] = options.key;
      if (options.body !== undefined) headers['Content-Type'] = 'application/json';
      return fetch(base + path, { method, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) });
    };

    it('lists only the caller inbox, paginates and marks read idempotently', async () => {
      core.sessions.set('cred_a', 'usr_a');
      core.sessions.set('cred_b', 'usr_b');
      for (const [event, session] of [['evt_1', 'ses_1'], ['evt_2', 'ses_2'], ['evt_3', 'ses_3']] as const) {
        await liveHandler(db, core)(liveEvent(event, session));
      }
      for (const message of await jobs('inbox')) await deliveryHandler('INBOX', deps)(message);
      assert.equal((await call('GET', '/api/notifications', null)).status, 401);
      assert.equal((await call('GET', '/api/notifications', 'cred_bad')).status, 401);
      const first = await call('GET', '/api/notifications?limit=2', 'cred_a');
      assert.equal(first.headers.get('cache-control'), 'no-store, private');
      const page = await first.json() as { items: { notificationId: string }[]; nextCursor: string };
      assert.equal(page.items.length, 2);
      const second = await (await call('GET', `/api/notifications?limit=2&cursor=${page.nextCursor}`, 'cred_a')).json() as { items: unknown[]; nextCursor: null };
      assert.equal(second.items.length, 1);
      assert.equal(second.nextCursor, null);
      assert.equal((await call('GET', `/api/notifications?cursor=${page.nextCursor}`, 'cred_b')).status, 400);
      assert.equal((await call('GET', '/api/notifications?userId=usr_b', 'cred_a')).status, 400);

      const id = page.items[0]!.notificationId;
      const key = '44444444-4444-4444-8444-444444444444';
      assert.equal((await call('PUT', `/api/notifications/${id}/read`, 'cred_a', { key, csrf: false })).status, 403);
      assert.equal((await call('PUT', `/api/notifications/${id}/read`, 'cred_a')).status, 400);
      const read = await (await call('PUT', `/api/notifications/${id}/read`, 'cred_a', { key })).json() as { readAtUtc: string };
      const again = await (await call('PUT', `/api/notifications/${id}/read`, 'cred_a', { key: '55555555-5555-4555-8555-555555555555' })).json() as { readAtUtc: string };
      assert.equal(again.readAtUtc, read.readAtUtc);
      assert.equal((await call('PUT', `/api/notifications/${id}/read`, 'cred_b', { key: '66666666-6666-4666-8666-666666666666' })).status, 404);
    });

    it('supports repeated versioned edits, no-ops, stale writes and replay on both preference routes', async () => {
      core.sessions.set('cred_a', 'usr_a');
      for (const path of ['/api/notifications/preferences', '/api/notifications/channels/chn_1/preference']) {
        const update = (emailEnabled: boolean, expectedVersion: number, key = randomUUID()) => call('PUT', path, 'cred_a',
          { body: { emailEnabled, pushEnabled: false, expectedVersion }, key });
        assert.equal((await update(true, 2)).status, 409);
        const key = randomUUID();
        const first = await update(true, 1, key);
        assert.equal(first.status, 200);
        const enabled = await first.json();
        assert.equal(enabled.version, 2);
        assert.equal((await update(true, 2)).status, 200);
        const off = await update(false, 2);
        assert.equal(off.status, 200);
        assert.equal((await off.json()).version, 3);
        assert.equal((await update(true, 2)).status, 409);
        assert.deepEqual(await (await update(true, 1, key)).json(), enabled);
        assert.equal((await (await call('GET', path, 'cred_a')).json()).emailEnabled, false);
        const race = await Promise.all([update(true, 3), update(true, 3)]);
        assert.deepEqual(race.map((r) => r.status).sort(), [200, 409]);
      }
    });

    it('completes more mutations than the pool size on one connection per mutation', { timeout: 5000 }, async () => {
      core.sessions.set('cred_a', 'usr_a');
      const replies = await Promise.all(Array.from({ length: 20 }, (_, i) => call('PUT', '/api/notifications/devices/ins_' + i, 'cred_a',
        { key: randomUUID(), body: { installationId: 'ins_' + i, platform: 'ANDROID', providerToken: 'fixture-token-' + i } })));
      assert.ok(replies.every((r) => r.status === 200));
      assert.equal(await count('SELECT count(*) n FROM devices'), 20);
      assert.equal(await count('SELECT count(*) n FROM idempotency'), 20);
    });

    it('rolls back the mutation if storing the idempotency response fails', async () => {
      core.sessions.set('cred_a', 'usr_a');
      await db.query(`CREATE FUNCTION fixture_fail_idempotency() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$`);
      await db.query('CREATE TRIGGER fixture_fail BEFORE INSERT ON idempotency FOR EACH ROW EXECUTE FUNCTION fixture_fail_idempotency()');
      const body = { emailEnabled: true, pushEnabled: false, expectedVersion: 1 };
      const key = randomUUID();
      try {
        assert.equal((await call('PUT', '/api/notifications/preferences', 'cred_a', { body, key })).status, 503);
        assert.equal(await count('SELECT count(*) n FROM medium_preferences'), 0);
      } finally {
        await db.query('DROP TRIGGER fixture_fail ON idempotency');
        await db.query('DROP FUNCTION fixture_fail_idempotency()');
      }
      const retry = await call('PUT', '/api/notifications/preferences', 'cred_a', { body, key });
      assert.equal(retry.status, 200);
      assert.equal((await retry.json()).version, 2);
    });

    it('updates preferences with versions and keeps provider tokens private', async () => {
      core.sessions.set('cred_a', 'usr_a');
      const initial = await (await call('GET', '/api/notifications/preferences', 'cred_a')).json();
      assert.deepEqual(initial, { emailEnabled: false, pushEnabled: false, version: 1 });
      const put = (body: unknown, key: string) => call('PUT', '/api/notifications/preferences', 'cred_a', { body, key });
      const updated = await (await put({ emailEnabled: true, pushEnabled: false, expectedVersion: 1 }, '77777777-7777-4777-8777-777777777777')).json();
      assert.deepEqual(updated, { emailEnabled: true, pushEnabled: false, version: 2 });
      assert.equal((await put({ emailEnabled: false, pushEnabled: false, expectedVersion: 1 }, '88888888-8888-4888-8888-888888888888')).status, 409);
      assert.equal((await put({ emailEnabled: false, pushEnabled: false, expectedVersion: 2 }, '77777777-7777-4777-8777-777777777777')).status, 409);
      const channel = await (await call('PUT', '/api/notifications/channels/chn_1/preference', 'cred_a',
        { body: { emailEnabled: true, pushEnabled: false, expectedVersion: 1 }, key: '99999999-9999-4999-8999-999999999999' })).json();
      assert.deepEqual(channel, { channelId: 'chn_1', emailEnabled: true, pushEnabled: false, version: 2 });

      const device = await call('PUT', '/api/notifications/devices/ins_1', 'cred_a',
        { body: { installationId: 'ins_1', platform: 'ANDROID', providerToken: 'very-private-token' }, key: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' });
      const text = await device.text();
      assert.equal(device.status, 200);
      assert.doesNotMatch(text, /very-private-token/);
      assert.equal((await call('PUT', '/api/notifications/devices/ins_1', 'cred_a',
        { body: { installationId: 'ins_2', platform: 'ANDROID', providerToken: 't' }, key: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' })).status, 400);
      core.sessions.set('cred_b', 'usr_b');
      await call('PUT', '/api/notifications/devices/ins_1', 'cred_b',
        { body: { installationId: 'ins_1', platform: 'ANDROID', providerToken: 'rotated' }, key: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' });
      assert.equal((await db.query("SELECT user_id FROM devices WHERE installation_id='ins_1'")).rows[0].user_id, 'usr_b');
      assert.equal((await call('DELETE', '/api/notifications/devices/ins_1', 'cred_b', { key: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd' })).status, 204);
      assert.equal(await count('SELECT count(*) n FROM devices'), 0);
    });
  });
});
