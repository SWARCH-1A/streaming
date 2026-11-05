import { execFileSync } from 'node:child_process';

export async function replayAvailable(fixture, context, index, expect) {
  if (!fixture.replay) return;
  const vod = fixture.vods[index];
  const response = await context.request.get(`/api/chat/vods/${vod.vodId}/messages?fromMs=0&toMs=${vod.durationMs}&limit=50`);
  expect(response.status()).toBe(200);
  const page = await response.json();
  expect(page.mappingVersion).toBe(vod.mappingVersion);
  expect(page.items.map((m) => m.messageId)).toEqual(fixture.replay[index].map((m) => m.messageId));
}

export async function replayDenied(fixture, context, index, expect) {
  if (!fixture.replay) return;
  const vod = fixture.vods[index];
  // Test a known URL whose messages were delivered before the restriction.
  const response = await context.request.get(`/api/chat/vods/${vod.vodId}/messages?fromMs=0&toMs=${vod.durationMs}&limit=50`);
  expect(response.status()).toBe(404);
}

export async function replayWithVideo(fixture, watch, expect) {
  if (!fixture.replay) return;
  const video = watch.locator('video');
  const message = fixture.replay[0][0];
  await video.evaluate((v) => v.pause());
  await video.evaluate((v, seconds) => { v.currentTime = seconds; }, (message.mediaOffsetMs + 600) / 1000);
  const history = watch.getByRole('log', { name: 'Mensajes del chat' });
  await expect(history).toContainText(message.text, { timeout: 15000 });
  // Seek before that message: it must not be shown before its mapped media time.
  await video.evaluate((v) => { v.currentTime = 0; });
  await expect(history).not.toContainText(message.text);
  execFileSync('docker', ['stop', fixture.containers.chat], { stdio: 'pipe' });
  try {
    await video.evaluate(async (v) => { v.currentTime = 20; await v.play(); });
    await expect(watch.getByRole('button', { name: 'Reintentar chat' })).toBeVisible({ timeout: 20000 });
    const before = await video.evaluate((v) => v.currentTime);
    await expect.poll(() => video.evaluate((v) => v.currentTime)).toBeGreaterThan(before + 0.5);
    expect(await video.evaluate((v) => v.paused)).toBe(false);
  } finally {
    execFileSync('docker', ['start', fixture.containers.chat], { stdio: 'pipe' });
  }
  await expect.poll(async () => (await watch.request.get(`/api/chat/vods/${fixture.vods[0].vodId}/messages?fromMs=0&toMs=10000`)).status(), { timeout: 30000 }).toBe(200);
  await watch.getByRole('button', { name: 'Reintentar chat' }).click();
  await expect(watch.getByRole('button', { name: 'Reintentar chat' })).toHaveCount(0);
  await video.evaluate((v) => { v.currentTime = 35; });
  console.log('PASS real replay: timeline, position callback, seek and Chat outage without interrupting HLS');
}
