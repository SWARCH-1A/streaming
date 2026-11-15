import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { browserOptions, responsive } from './responsive.mjs';

const require = createRequire(new URL('../../../apps/web/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const browser = await chromium.launch(browserOptions);
try {
  const page = await browser.newPage();
  // Real shared styles; no backend is needed to exercise root viewport sizing.
  const css = readFileSync(new URL('../../../apps/web/src/styles/global.css', import.meta.url), 'utf8')
    .replace(/^@import.*$/m, '');
  await page.setContent(`<style>${css}</style><main style="min-height:1500px">
    <h1>Grabaciones y administración</h1><button>Continuar</button></main>`);
  await page.setViewportSize({ width: 320, height: 900 });
  await page.addStyleTag({ content: 'html { overflow-y: hidden; }' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(320);
  await responsive(page, expect);
  console.log('PASS shared CSS reflow: 320/390/768/1440 windows with a 15px scrollbar');
} finally {
  await browser.close();
}
