// Structured logs with identifiers only: no addresses, tokens, links or session credentials.
export interface Log {
  info(event: string, fields: Record<string, unknown>): void;
  warn(event: string, fields: Record<string, unknown>): void;
}

export const consoleLog: Log = {
  info: (event, fields) => console.log(JSON.stringify({ level: 'info', component: 'notifications', event, ...fields })),
  warn: (event, fields) => console.warn(JSON.stringify({ level: 'warn', component: 'notifications', event, ...fields })),
};

export const silentLog: Log = { info: () => undefined, warn: () => undefined };
