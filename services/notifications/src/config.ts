// Every value comes from the environment; secrets are validated but never printed.
export interface Config {
  port: number;
  tlsCertFile: string | null;
  tlsKeyFile: string | null;
  caFile: string | null;
  databaseUrl: string;
  brokerUrl: string | null;
  recoveryBrokerUrl: string | null;
  coreInternalUrl: string;
  corePublicUrl: string;
  coreServiceToken: string;
  recoveryServiceToken: string | null;
  cursorSecret: string;
  webOrigin: string;
  smtp: { host: string; port: number; requireTls: boolean; from: string } | null;
  pushProvider: 'none';
}

function required(env: NodeJS.ProcessEnv, key: string): string {
  const value = env[key];
  if (!value) throw new Error(`${key} is required`);
  return value;
}

function secret(env: NodeJS.ProcessEnv, key: string): string {
  const value = required(env, key);
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(value)) throw new Error(`${key} must be a URL-safe secret of at least 32 characters`);
  return value;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const smtpHost = env.NOTIFICATIONS_SMTP_HOST;
  const tlsCertFile = env.NOTIFICATIONS_TLS_CERT_FILE || null;
  const tlsKeyFile = env.NOTIFICATIONS_TLS_KEY_FILE || null;
  if (Boolean(tlsCertFile) !== Boolean(tlsKeyFile)) throw new Error('TLS certificate and key must be configured together');
  for (const key of ['NOTIFICATIONS_BROKER_URL', 'NOTIFICATIONS_RECOVERY_BROKER_URL'] as const) {
    if (env[key] && !env[key].startsWith('amqps://')) throw new Error(`${key} must use AMQPS`);
  }
  return {
    port: Number(env.NOTIFICATIONS_PORT ?? 8095),
    tlsCertFile,
    tlsKeyFile,
    caFile: env.NOTIFICATIONS_CA_FILE || null,
    databaseUrl: required(env, 'NOTIFICATIONS_DB_URL'),
    brokerUrl: env.NOTIFICATIONS_BROKER_URL || null,
    recoveryBrokerUrl: env.NOTIFICATIONS_RECOVERY_BROKER_URL || null,
    coreInternalUrl: required(env, 'NOTIFICATIONS_CORE_INTERNAL_URL').replace(/\/$/, ''),
    corePublicUrl: required(env, 'NOTIFICATIONS_CORE_PUBLIC_URL').replace(/\/$/, ''),
    coreServiceToken: secret(env, 'NOTIFICATIONS_CORE_SERVICE_TOKEN'),
    recoveryServiceToken: env.NOTIFICATIONS_RECOVERY_SERVICE_TOKEN ? secret(env, 'NOTIFICATIONS_RECOVERY_SERVICE_TOKEN') : null,
    cursorSecret: secret(env, 'NOTIFICATIONS_CURSOR_SECRET'),
    webOrigin: required(env, 'WEB_ORIGIN').replace(/\/$/, ''),
    smtp: smtpHost
      ? {
          host: smtpHost,
          port: Number(env.NOTIFICATIONS_SMTP_PORT ?? 587),
          requireTls: env.NOTIFICATIONS_SMTP_REQUIRE_TLS !== 'false',
          from: env.NOTIFICATIONS_SMTP_FROM ?? 'Vault tv <no-reply@vault.local>',
        }
      : null,
    // P2-13: no push provider is provisioned yet; push deliveries end as an observable permanent failure.
    pushProvider: 'none',
  };
}
