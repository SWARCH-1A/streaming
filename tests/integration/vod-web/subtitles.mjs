import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const require = createRequire(new URL('../../../apps/web/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { default: AxeBuilder } = require('@axe-core/playwright');
const fixture = JSON.parse(readFileSync(process.env.VOD_WEB_FIXTURE, 'utf8'));
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(fixture.origin) || !/^streaming-vod-web-[a-f0-9]{10}$/.test(fixture.prefix)) throw new Error('Use run.py');
const browser = await chromium.launch();
const vtt = (language) => `WEBVTT\n\n00:00.000 --> 00:30.000\n${language} primera parte\n\n00:30.000 --> 01:04.000\n${language} segunda parte\n`;
try {
  // Only this disposable context accepts its ephemeral test certificate. The runner verifies it with its CA.
  const owner = await browser.newContext({ baseURL: fixture.origin, ignoreHTTPSErrors: true });
  const viewer = await browser.newContext({ baseURL: fixture.origin, ignoreHTTPSErrors: true });
  await owner.addCookies(fixture.cookies);
  const [vod, nextVod] = fixture.vods;
  expect(vod.durationMs).toBe(64000);
  const page = await owner.newPage();
  await page.goto('/studio/vods');
  const panel = page.getByRole('link', { name: vod.title, exact: true }).locator('..').locator('..');
  await panel.locator('summary').filter({ hasText: /^Subtítulos$/ }).click();
  await panel.getByRole('button', { name: 'Cargar pista' }).click();
  await expect(panel.getByLabel('Archivo WebVTT')).toBeFocused();
  await expect(panel.getByLabel('Archivo WebVTT')).toHaveAttribute('aria-invalid', 'true');
  for (const [language, label] of [['es', 'Español'], ['en', 'English']]) {
    await panel.getByLabel('Idioma', { exact: true }).fill(language);
    await panel.getByLabel('Nombre de la pista').fill(label);
    await panel.getByLabel('Archivo WebVTT').setInputFiles({ name: language + '.vtt', mimeType: 'text/vtt', buffer: Buffer.from(vtt(language)) });
    await panel.getByRole('button', { name: 'Cargar pista' }).click();
    await expect(panel.getByText(`Pista ${label} cargada.`, { exact: true })).toBeVisible();
  }
  const tracks = (await (await owner.request.get(`/api/vods/${vod.vodId}/subtitles`)).json()).items;
  expect(tracks).toHaveLength(2);
  const es = tracks.find((track) => track.language === 'es'), en = tracks.find((track) => track.language === 'en');
  async function upload(api, metadata, text = vtt('es')) {
    const csrf = await (await api.get('/api/identity/csrf')).json();
    return api.post(`/api/vods/${vod.vodId}/subtitles`, {
      headers: { Origin: fixture.origin, [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() },
      multipart: {
        metadata: { name: 'metadata.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(metadata)) },
        file: { name: 'test.vtt', mimeType: 'text/vtt', buffer: Buffer.from(text) },
      },
    });
  }
  const metadata = { language: 'es', label: 'Prueba', expectedMappingVersion: vod.mappingVersion };
  expect((await upload(owner.request, metadata, 'WEBVTT\n\n00:00.000 --> 02:00.000\nFuera del video\n')).status()).toBe(422);
  expect((await upload(owner.request, { ...metadata, expectedMappingVersion: vod.mappingVersion + 1 })).status()).toBe(409);
  expect((await upload(viewer.request, metadata)).status()).toBe(401);
  const identity = 'viewer_' + randomUUID().replaceAll('-', '').slice(0, 8);
  async function identityPost(path, data) {
    const csrf = await (await viewer.request.get('/api/identity/csrf')).json();
    return viewer.request.post(path, { data, headers: { Origin: fixture.origin, [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() } });
  }
  expect((await identityPost('/api/identity/registrations', { handle: identity, email: identity + '@example.test', password: 'fixture-only viewer password' })).status()).toBe(201);
  expect((await identityPost('/api/identity/sessions', { login: identity, password: 'fixture-only viewer password' })).status()).toBe(200);
  expect((await upload(viewer.request, metadata)).status()).toBe(403);
  const watch = await viewer.newPage();
  const authorizations = [];
  // The explicit authorization uses fetch; hls.js loads its initial manifest with XHR.
  // Count successful grants with timestamps, not all manifest requests (quality changes
  // and startup can otherwise masquerade as the periodic 45-second renewal).
  watch.on('response', (response) => {
    if (new URL(response.url()).pathname === vod.playbackPath &&
        response.request().resourceType() === 'fetch' && response.status() === 200) {
      const grant = response.headers()['x-vod-playback-grant'];
      if (grant) authorizations.push({ at: Date.now(), grant });
    }
  });
  await watch.goto(`/vod/${vod.vodId}`);
  const video = watch.locator('video');
  await expect.poll(() => video.evaluate((v) => v.readyState), { timeout: 30000 }).toBeGreaterThanOrEqual(2);
  await video.evaluate(async (v) => { v.muted = true; await v.play(); });
  const selector = watch.getByRole('combobox', { name: 'Subtítulos', exact: true });
  await expect(selector).toHaveValue('');
  await selector.focus();
  await selector.selectOption(es.trackId);
  await expect(selector).toBeFocused();
  await expect.poll(() => video.evaluate((v) => Array.from(v.textTracks).find((t) => t.mode === 'showing')?.activeCues?.[0]?.text), { timeout: 10000 }).toBe('es primera parte');
  await video.evaluate((v) => { v.currentTime = 35; });
  await expect.poll(() => video.evaluate((v) => Array.from(v.textTracks).find((t) => t.mode === 'showing')?.activeCues?.[0]?.text)).toBe('es segunda parte');
  await watch.route('**' + en.contentPath, (route) => route.abort('failed'));
  await selector.selectOption(en.trackId);
  await expect(watch.getByRole('button', { name: 'Reintentar subtítulos' })).toBeVisible();
  const before = await video.evaluate((v) => v.currentTime);
  await expect.poll(() => video.evaluate((v) => v.currentTime)).toBeGreaterThan(before + 0.5);
  expect(await video.evaluate((v) => v.paused)).toBe(false);
  await watch.unroute('**' + en.contentPath);
  await watch.getByRole('button', { name: 'Reintentar subtítulos' }).click();
  await expect.poll(() => video.evaluate((v) => Array.from(v.textTracks).find((t) => t.mode === 'showing')?.activeCues?.[0]?.text), { timeout: 10000 }).toBe('en segunda parte');
  await selector.selectOption('');
  expect(await video.evaluate((v) => Array.from(v.textTracks).every((t) => t.mode === 'disabled'))).toBe(true);
  await selector.selectOption(es.trackId);
  await watch.getByRole('combobox', { name: 'Calidad', exact: true }).selectOption('0');
  await expect(selector).toHaveValue(es.trackId);
  for (const width of [320, 390, 768, 1440]) {
    await watch.setViewportSize({ width, height: 900 });
    expect(await watch.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  }
  const axe = await new AxeBuilder({ page: watch }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
  expect(axe.violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) }))).toEqual([]);
  // Keep the page open beyond the 45-second grant renewal and verify playback can seek afterwards.
  await expect.poll(() => authorizations.length, { timeout: 55000 }).toBeGreaterThanOrEqual(2);
  expect(authorizations[1].at - authorizations[0].at).toBeGreaterThanOrEqual(40000);
  expect(authorizations[1].grant).not.toBe(authorizations[0].grant);
  await video.evaluate((v) => { v.currentTime = 50; });
  await expect.poll(() => video.evaluate((v) => Array.from(v.textTracks).find((t) => t.mode === 'showing')?.activeCues?.[0]?.text)).toBe('es segunda parte');
  await watch.goto(`/vod/${nextVod.vodId}`);
  await expect(watch.getByRole('combobox', { name: 'Subtítulos', exact: true })).toHaveValue('');
  await panel.getByRole('button', { name: 'Retirar pista Español (es)' }).click();
  await expect(panel.getByText('Pista Español retirada.', { exact: true })).toBeVisible();
  await expect(panel.locator('summary').filter({ hasText: /^Subtítulos$/ })).toBeFocused();
  expect((await viewer.request.get(es.contentPath)).status()).toBe(404);
  expect((await (await owner.request.get(`/api/vods/${vod.vodId}/subtitles`)).json()).items).toHaveLength(1);
  console.log('PASS: HTTPS real providers; 64s HLS; upload two WebVTT tracks; format/mapping/owner authorization; native cues and seek; select/off/retry without interrupting video; grant renewal; focus; 320/390/768/1440; Axe; withdrawal/direct URL');
  console.log('Pending: human screen reader/zoom/visual subtitle contrast, mobile and real multiple renditions; this fixture has one quality and no live capture/replay provider.');
} finally { await browser.close(); }
