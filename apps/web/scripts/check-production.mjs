import { readdir, readFile } from 'node:fs/promises';

// Verify the emitted product, not just the DEV condition in source code.
const assets = new URL('../dist/assets/', import.meta.url);
for (const name of await readdir(assets)) {
  if (!name.endsWith('.js')) continue;
  const source = await readFile(new URL(name, assets), 'utf8');
  if (['/design-system', '/prototype', 'Sistema de diseño'].some((value) => source.includes(value)))
    throw new Error(`Development-only pages leaked into production: ${name}`);
}
