// Same generated schemas and language vectors as Python; no VOD implementation is simulated.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';

const require = createRequire(new URL('../../apps/web/package.json', import.meta.url));
const Ajv = require('ajv/dist/2020').default;
const addFormats = require('ajv-formats');
const read = (relative) => JSON.parse(readFileSync(new URL(relative, import.meta.url), 'utf8'));
const schema = read('../../contracts/generated/p2/schema.json');
const cases = read('./fixtures/subtitles/languages.json');
const ajv = new Ajv({ strict: false, allErrors: true });
addFormats(ajv);
let assertions = 0;
for (const name of ['P2SubtitleUploadMetadata', 'P2SubtitleTrack']) {
  const validate = ajv.compile({ ...schema, $ref: '#/$defs/' + name });
  const example = schema.$defs[name].examples[0];
  for (const [group, expected] of [['valid', true], ['invalid', false]]) {
    for (const language of cases[group]) {
      assert.equal(validate({ ...example, language }), expected, `${name}: ${JSON.stringify(language)}`);
      assertions += 1;
    }
  }
}
console.log(`PASS: Ajv subtitle language schemas (${assertions} assertions)`);
