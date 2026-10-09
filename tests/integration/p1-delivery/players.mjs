// Real Firefox video decoding. This executable is an isolated load fixture, never application code.
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const state = resolve(root, 'infra/p1/.state');
const require = createRequire(resolve(root, 'apps/web/package.json'));
const { firefox } = require('@playwright/test');
const hlsScript = require.resolve('hls.js/dist/hls.js');
const config = JSON.parse(readFileSync(resolve(state, 'load-players.json'), 'utf8'));
if (!/^https:\/\/localhost:\d+$/.test(config.origin) || ![5, 100].includes(config.players))
  throw new Error('Only the own loopback P1 fixture is supported');
const sleep = (ms) => new Promise((r) => setTimeout(r, Math.max(ms, 0)));
const report = { browser: '', firstFramesMs: [], failures: [], hlsRequests: 0, hlsBytes: 0,
  leaseCreates: 0, heartbeats: 0, leaseDeletes: 0, decodedFrames: 0, droppedFrames: 0, players: [],
  driverMemorySamples: [],
  network: { perPlayerDownMbps: 15, deliveryRttMs: 20, configuredLossPercent: 0,
    mechanism: 'serialized per-page HLS response delivery; loopback upstream; buffered application shaping' } };
let measuredStart = Infinity;
let measuredEnd = Infinity;
const measured = () => Date.now() >= measuredStart && Date.now() < measuredEnd;
let context;
const memoryTimer = setInterval(() => { if (measured()) report.driverMemorySamples.push({ elapsedSeconds: (Date.now() - measuredStart) / 1000, ...process.memoryUsage() }); }, 10000);
try {
  context = await firefox.launchPersistentContext(resolve(state, 'firefox-load-profile'), {
    headless: true, baseURL: config.origin, viewport: { width: 640, height: 480 },
    firefoxUserPrefs: { 'media.suspend-bkgnd-video.enabled': false },
  });
  report.browser = context.browser()?.version() ?? 'Firefox persistent context';
  const pages = [];
  for (let i = 0; i < config.players; i++) {
    const page = await context.newPage();
    let nextDelivery = 0;
    await page.route('**/hls/**', async (route) => {
      let response;
      const requestedAt = Date.now();
      const requestMeasured = requestedAt >= measuredStart && requestedAt < measuredEnd;
      try {
        response = await route.fetch({ timeout: 5000, maxRetries: 0 });
        const body = await response.body();
        // All parallel HLS transfers of a player share one 15 Mbit/s delivery queue.
        nextDelivery = Math.max(nextDelivery, Date.now()) + 20 + body.length * 8 / 15000;
        await sleep(nextDelivery - Date.now());
        if (requestMeasured) {
          report.hlsRequests++; report.hlsBytes += body.length;
          if (!response.ok()) report.failures.push({ operation: 'hls', status: response.status(), player: i, file: new URL(route.request().url()).pathname.split('/').pop(), elapsedSeconds: (Date.now() - measuredStart) / 1000 });
        }
        await route.fulfill({ response, body });
      } catch {
        if (requestMeasured) report.failures.push({ operation: 'hls', error: 'transport/deadline', player: i });
        await route.abort().catch(() => {});
      } finally {
        // APIRequestContext retains every fetched body until explicitly disposed.
        await response?.dispose().catch(() => {});
      }
    });
    await page.goto('/design-system'); // CA/SAN validation is required; never ignore HTTPS errors.
    if (i === 0) {
      report.browserProbe = await page.evaluate(async (origin) => {
        const result = { origin: location.origin, secure: isSecureContext, timeoutApi: typeof AbortSignal.timeout };
        try {
          result.get = (await fetch(origin + '/api/taxonomy')).status;
          result.post = (await fetch(origin + '/api/streams/sessions/fixture-missing/viewer-leases', {
            method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, signal: AbortSignal.timeout(5000),
          })).status;
        } catch (error) { result.error = error.name + ': ' + error.message; }
        return result;
      }, config.origin);
      if (report.browserProbe.error) throw new Error('Browser API preflight failed');
    }
    await page.evaluate(() => {
      const video = document.createElement('video');
      video.id = 'p1-load-video'; video.muted = true; video.autoplay = true; video.playsInline = true;
      video.width = 640; video.height = 360; document.body.append(video);
    });
    await page.addScriptTag({ path: hlsScript });
    await page.exposeFunction('p1Metric', (kind, value) => {
      if (kind === 'first-frame') report.firstFramesMs.push(value);
      else if (kind === 'failure' && measured()) report.failures.push({ ...value, player: i });
      else if (['leaseCreates', 'leaseDeletes'].includes(kind) || (measured() && kind in report)) report[kind]++;
    });
    pages.push(page);
  }
  writeFileSync(resolve(state, 'load-players-ready.json'), JSON.stringify({ browser: report.browser }), { mode: 0o600 });
  while (!existsSync(resolve(state, 'load-start.json'))) await sleep(100);
  const { warmupStartUtcMs, startUtcMs, durationSeconds } = JSON.parse(readFileSync(resolve(state, 'load-start.json'), 'utf8'));
  measuredStart = startUtcMs; measuredEnd = startUtcMs + durationSeconds * 1000;
  const startPlayer = async (i) => {
    await pages[i].evaluate(({ sid, origin }) => {
      const video = document.querySelector('#p1-load-video');
      video.muted = true;
      const begin = performance.now();
      let first = false;
      let lease;
      let timer;
      let active = true;
      let heartbeatCount = 0; let maxHeartbeatGapMs = 0; let lastHeartbeat;
      let maxPlaybackStallMs = 0; let lastTime = 0; let lastProgress = performance.now();
      const progressTimer = setInterval(() => {
        if (video.currentTime > lastTime) { lastTime = video.currentTime; lastProgress = performance.now(); }
        else if (first) maxPlaybackStallMs = Math.max(maxPlaybackStallMs, performance.now() - lastProgress);
      }, 1000);
      const fail = (operation, status) => window.p1Metric('failure', { operation, status });
      const call = async (url, options) => {
        try {
          const response = await fetch(origin + url, { ...options, signal: AbortSignal.timeout(5000) });
          if (!response.ok) { fail('lease', response.status); return null; }
          return response;
        } catch (error) { fail('lease', error.name + ': ' + error.message); return null; }
      };
      video.requestVideoFrameCallback(async () => {
        first = true;
        window.p1Metric('first-frame', performance.now() - begin);
        const response = await call(`/api/streams/sessions/${sid}/viewer-leases`, {
          method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        });
        if (!response) return;
        lease = await response.json();
        window.p1Metric('leaseCreates'); lastHeartbeat = performance.now();
        const heartbeat = async () => {
          if (!active) return;
          if (await call(`/api/streams/viewer-leases/${lease.leaseId}/heartbeat`, {
            method: 'PUT', headers: { Authorization: `ViewerLease ${lease.leaseToken}` },
          })) {
            const now = performance.now(); maxHeartbeatGapMs = Math.max(maxHeartbeatGapMs, now - lastHeartbeat);
            lastHeartbeat = now; heartbeatCount++; window.p1Metric('heartbeats');
          }
          if (active) timer = setTimeout(heartbeat, 10000);
        };
        timer = setTimeout(heartbeat, 10000);
      });
      setTimeout(() => { if (!first) fail('first-frame', 'deadline-5s'); }, 5000);
      const hls = new window.Hls({ enableWorker: true, backBufferLength: 30 });
      hls.on(window.Hls.Events.ERROR, (_event, data) => { if (data.fatal) fail('hls-player', data.type); });
      hls.loadSource(`${origin}/hls/${sid}/index.m3u8`);
      hls.attachMedia(video);
      video.play().catch(() => fail('play', 'autoplay'));
      window.p1Stop = async () => {
        const q = video.getVideoPlaybackQuality();
        active = false; clearTimeout(timer); clearInterval(progressTimer); hls.destroy(); video.pause();
        if (lastHeartbeat) maxHeartbeatGapMs = Math.max(maxHeartbeatGapMs, performance.now() - lastHeartbeat);
        if (lease && await call(`/api/streams/viewer-leases/${lease.leaseId}`, {
          method: 'DELETE', headers: { Authorization: `ViewerLease ${lease.leaseToken}` },
        })) window.p1Metric('leaseDeletes');
        return { total: q.totalVideoFrames, dropped: q.droppedVideoFrames, heartbeatCount, maxHeartbeatGapMs, maxPlaybackStallMs, leaseCreated: !!lease };
      };
    }, { sid: config.sessions[i % 5], origin: config.origin });
  };
  if (warmupStartUtcMs) {
    await sleep(warmupStartUtcMs - Date.now());
    for (let i = 0; i < 5; i++) await startPlayer(i);
    await sleep(measuredStart - Date.now() - 500);
    for (let i = 0; i < 5; i++) await pages[i].evaluate(() => window.p1Stop());
    report.warmupPlayers = { firstFramesMs: [...report.firstFramesMs], leaseCreates: report.leaseCreates, leaseDeletes: report.leaseDeletes };
    if (report.firstFramesMs.length !== 5 || report.leaseCreates !== 5 || report.leaseDeletes !== 5)
      throw new Error('Video warm-up failed');
    report.firstFramesMs = []; report.leaseCreates = 0; report.leaseDeletes = 0;
  }
  // The required 100-player ramp occurs in the first 60 measured seconds, one per source every 3 s.
  for (let i = 0; i < pages.length; i++) {
    await sleep(measuredStart + Math.floor(i / 5) * 3000 - Date.now());
    await startPlayer(i);
  }
  // Let requests begun inside the measured window reach their 5 s deadline before cleanup.
  await sleep(measuredEnd + 5000 - Date.now());
  for (const page of pages) {
    const quality = await page.evaluate(() => window.p1Stop()).catch(() => ({ total: 0, dropped: 0 }));
    report.players.push(quality);
    report.decodedFrames += quality.total; report.droppedFrames += quality.dropped;
  }
} catch {
  report.failures.push({ operation: 'player-driver', error: 'setup/runtime failure; inspect private diagnostics' });
} finally {
  clearInterval(memoryTimer);
  report.leaseContinuityPass = report.players.length === config.players && report.players.every(p =>
    p.leaseCreated && p.heartbeatCount > 0 && p.maxHeartbeatGapMs <= 15000 && p.maxPlaybackStallMs <= 5000) && report.leaseDeletes === config.players;
  await context?.close().catch(() => {});
  writeFileSync(resolve(state, 'load-players-report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
}
