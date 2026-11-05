import { readdir, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

import pg from 'pg';

export type Db = pg.Pool;
export type Tx = pg.PoolClient;

export function createPool(url: string, ca: string | null): Db {
  // sslmode in the URL is ignored when ssl is an object; the CA pins the local certificate authority.
  const parsed = new URL(url);
  const tls = parsed.searchParams.get('sslmode') === 'verify-full';
  parsed.searchParams.delete('sslmode');
  return new pg.Pool({
    connectionString: parsed.toString(),
    max: 10,
    ...(tls ? { ssl: { rejectUnauthorized: true, ...(ca ? { ca } : {}) } } : {}),
  });
}

export async function transaction<T>(db: Db | Tx, work: (tx: Tx) => Promise<T>): Promise<T> {
  const client = 'release' in db ? db : await db.connect();
  try {
    await client.query('BEGIN');
    const result = await work(client);
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw error;
  } finally {
    if (client !== db) client.release();
  }
}

/** Applies migrations/*.sql in name order, once, serialized by an advisory lock across replicas. */
export async function migrate(db: Db): Promise<void> {
  const directory = fileURLToPath(new URL('../migrations/', import.meta.url));
  const files = (await readdir(directory)).filter((name) => name.endsWith('.sql')).sort();
  await transaction(db, async (tx) => {
    await tx.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', ['notifications.migrations']);
    await tx.query(
      'CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY, applied_at_utc TIMESTAMPTZ NOT NULL DEFAULT now())',
    );
    for (const file of files) {
      const done = await tx.query('SELECT 1 FROM schema_migrations WHERE version = $1', [file]);
      if (done.rowCount) continue;
      await tx.query(await readFile(directory + file, 'utf8'));
      await tx.query('INSERT INTO schema_migrations (version) VALUES ($1)', [file]);
    }
  });
}
