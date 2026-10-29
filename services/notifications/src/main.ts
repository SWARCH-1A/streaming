import { readFileSync } from 'node:fs';
import { createServer as createHttp } from 'node:http';
import { createServer as createHttps } from 'node:https';

import { BrokerConnection } from './broker.ts';
import { loadConfig } from './config.ts';
import { httpCore } from './core.ts';
import { createPool, migrate } from './db.ts';
import { deliveryHandler, unconfiguredPush } from './deliveries.ts';
import { accountHandler, resolveOwners } from './accounts.ts';
import { followHandler } from './follow.ts';
import { apiHandler } from './http.ts';
import { liveHandler } from './live.ts';
import { consoleLog as log } from './log.ts';
import { smtpMailer, unconfiguredMailer } from './mail.ts';
import { startOutbox } from './outbox.ts';
import { recoveryHandler } from './recovery.ts';

const config = loadConfig();
const ca = config.caFile ? readFileSync(config.caFile, 'utf8') : null;
const db = createPool(config.databaseUrl, ca);
await migrate(db);

const core = httpCore({
  internalUrl: config.coreInternalUrl,
  publicUrl: config.corePublicUrl,
  serviceToken: config.coreServiceToken,
  recoveryToken: config.recoveryServiceToken,
});
const mailer = config.smtp ? smtpMailer({ ...config.smtp, ca }) : unconfiguredMailer;
const deps = { db, core, mailer, push: unconfiguredPush, webOrigin: config.webOrigin };

const stops: (() => unknown)[] = [];
if (config.brokerUrl) {
  // Identity "notifications": live sessions, follows and its own per-medium delivery jobs.
  const broker = new BrokerConnection(config.brokerUrl, ca, log);
  broker.subscribe('notifications.live', liveHandler(db, core));
  broker.subscribe('notifications.follow', followHandler(db));
  broker.subscribe('notifications.account', accountHandler(db));
  broker.subscribe('notifications.delivery.inbox', deliveryHandler('INBOX', deps));
  broker.subscribe('notifications.delivery.email', deliveryHandler('EMAIL', deps));
  broker.subscribe('notifications.delivery.push', deliveryHandler('PUSH', deps));
  void broker.run();
  stops.push(startOutbox(db, broker, log), () => broker.stop());
}
if (config.recoveryBrokerUrl && config.recoveryServiceToken) {
  // Separate identity "recovery-email": its credential can only read the recovery queue (ADR-017).
  const recovery = new BrokerConnection(config.recoveryBrokerUrl, ca, log);
  recovery.subscribe('notifications.recovery.email', recoveryHandler(db, core, mailer, log));
  void recovery.run();
  stops.push(() => recovery.stop());
}

// Backfill owners of notices stored before migration 002 (suspension checks need them).
const owners = setInterval(() => void resolveOwners(db, core).catch(() => log.warn('owner backfill unavailable', {})), 60_000);
stops.push(() => clearInterval(owners));

const cleanup = setInterval(() => {
  db.query("DELETE FROM idempotency WHERE retain_until_utc<=now()").catch(() => undefined);
  db.query("DELETE FROM outbox WHERE published_at_utc<=now()-interval '7 days'").catch(() => undefined);
}, 5 * 60 * 1000);
stops.push(() => clearInterval(cleanup));

const handler = apiHandler({ db, core, cursorSecret: config.cursorSecret, log });
const server = config.tlsCertFile && config.tlsKeyFile
  ? createHttps({ cert: readFileSync(config.tlsCertFile), key: readFileSync(config.tlsKeyFile), minVersion: 'TLSv1.2' }, handler)
  : createHttp(handler);
server.requestTimeout = 10_000;
server.listen(config.port, () => log.info('listening', { port: config.port, tls: Boolean(config.tlsCertFile) }));

for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.once(signal, async () => {
    server.close();
    for (const stop of stops) await stop();
    await db.end();
    process.exit(0);
  });
}
