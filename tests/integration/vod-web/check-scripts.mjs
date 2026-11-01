import { createRequire } from 'node:module';
import { readFileSync, readdirSync } from 'node:fs';

const require = createRequire(new URL('../../../apps/web/package.json', import.meta.url));
const { Linter } = require('eslint');
const globals = require('globals');
const config = {
  languageOptions: {
    ecmaVersion: 'latest', sourceType: 'module',
    // Playwright evaluate callbacks execute in the browser; orchestration runs in Node.
    globals: { ...globals.node, ...globals.browser },
  },
  rules: { 'no-undef': 'error' },
};
const linter = new Linter();
// Ensure the check catches the missing import that previously escaped Web's lint scope.
if (!linter.verify('readFileSync("fixture.sql");', config).some((m) => m.ruleId === 'no-undef')) {
  throw new Error('Runner preflight must detect missing imports');
}
let failures = 0;
for (const file of readdirSync(new URL('.', import.meta.url)).filter((name) => name.endsWith('.mjs'))) {
  const messages = linter.verify(readFileSync(new URL(file, import.meta.url), 'utf8'), config);
  for (const message of messages) {
    console.error(`${file}:${message.line}:${message.column} ${message.message}`);
    failures++;
  }
}
if (failures) process.exitCode = 1;
else console.log('PASS browser runner preflight: no undefined references');
