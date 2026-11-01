import { readFileSync } from 'node:fs';

// The reduced runner supplies a local file; WSL passes private data over stdin.
export const fixture = JSON.parse(readFileSync(process.env.VOD_WEB_FIXTURE || 0, 'utf8'));
