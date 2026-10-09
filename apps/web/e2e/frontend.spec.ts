import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import AxeBuilder from '@axe-core/playwright';
import type {
  ChannelBootstrap,
  ChatHistory,
  PublicSession,
  StreamConfig,
  Taxonomy,
} from '@contracts/p1';
import { expect, test } from '@playwright/test';

const integrated = process.env.P1_WEB_INTEGRATION === '1';
const composeFile = process.env.P1_COMPOSE_FILE ?? '';
function compose(...args: string[]) {
  if (!integrated || process.env.P1_COMPOSE_PROJECT !== 'streaming-p1-domains' || !composeFile)
    throw new Error('Use the isolated real-provider fixture.');
  return execFileSync(
    'docker',
    ['compose', '-p', 'streaming-p1-domains', '-f', composeFile, ...args],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
}
test.skip(
  !integrated,
  'Run tests/integration/p1-domains/run.py --web; mocks do not count as P1 integration.',
);

test('real P1 owner/viewer flow, HTTP/WS/HLS, uploads, failures and keyboard', async ({
  page,
}, testInfo) => {
  const handle = `web_${crypto.randomUUID().replaceAll('-', '').slice(0, 12)}`;
  const password = 'fixture-only browser password';
  let publisher: ReturnType<typeof spawn> | null = null;
  try {
    await page.goto('/register');
    await expect(page.getByRole('navigation', { name: 'Canales destacados' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Crear cuenta', exact: true }).click();
    await expect(page.getByLabel('Correo electrónico')).toBeFocused();
    await page.getByLabel('Correo electrónico').fill(`${handle}@example.test`);
    await page.getByLabel('Handle', { exact: true }).fill(handle);
    await page.getByLabel('Contraseña', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Crear cuenta', exact: true }).click();
    await expect(page.getByText('Cuenta creada. Ya puedes iniciar sesión.')).toBeVisible();
    await page.getByRole('link', { name: 'Ir a iniciar sesión →' }).click();
    await page.getByLabel('Handle o correo').fill(handle);
    await page.getByLabel('Contraseña', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
    await expect(page).toHaveURL('/studio');
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Estudio de emisión' })).toBeVisible();
    await page.goto('/profile');
    await page.getByLabel('Nombre visible').fill(`Autora ${handle}`);
    await page.getByLabel('Biografía', { exact: true }).fill('Integración de prototipo uno.');
    const image = fileURLToPath(new URL('../public/images/studio-1.jpg', import.meta.url));
    await page.getByLabel('Avatar', { exact: true }).setInputFiles(image);
    await page.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
    await expect(page.getByText('Perfil actualizado.', { exact: true })).toBeVisible();
    await page.goto('/studio/channel');
    await page
      .getByLabel('Descripción del canal', { exact: true })
      .fill('Descripción pública integrada.');
    await page.getByLabel('Portada', { exact: true }).setInputFiles(image);
    await page.getByRole('button', { name: 'Guardar canal', exact: true }).click();
    await expect(page.getByText('Canal actualizado.', { exact: true })).toBeVisible();
    await page.goto(`/channels/${handle.toUpperCase()}`);
    await expect(page).toHaveURL(`/channels/${handle}`);
    await expect(page.getByText('Descripción pública integrada.')).toBeVisible();
    const bootstrap = (await (
      await page.request.get(`/api/channels/by-handle/${handle}`)
    ).json()) as ChannelBootstrap;
    expect(bootstrap.profile.avatarUri).toMatch(/^\/api\/profile\/avatars\//);
    expect(bootstrap.channel.bannerUri).toMatch(/^\/api\/channels\/banners\//);
    expect(
      (await page.request.get(bootstrap.profile.avatarUri ?? '/invalid-avatar')).headers()[
        'content-type'
      ],
    ).toMatch(/^image\//);
    // Catalog fixtures are created only in this disposable owner's DB; runtime reads still use HTTP.
    const categoryId = `cat_${handle}`,
      tagId = `tag_${handle}`,
      categoryName = `Categoría ${handle}`,
      tagName = `Etiqueta ${handle}`;
    compose(
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      'core',
      '-d',
      'core',
      '-v',
      'ON_ERROR_STOP=1',
      '-c',
      `INSERT INTO taxonomy.categories(id,name,active) VALUES ('${categoryId}','${categoryName}',true); INSERT INTO taxonomy.tags(id,name,active) VALUES ('${tagId}','${tagName}',true);`,
    );
    const catalog = (await (await page.request.get('/api/taxonomy')).json()) as Taxonomy;
    await page.goto('/studio');
    await page.getByLabel('Título de la transmisión').fill(`Directo ${handle}`);
    await page.getByLabel('Categoría obligatoria').selectOption(categoryId);
    await page.getByRole('button', { name: tagName, exact: true }).click();
    await page.getByRole('button', { name: 'Configurar emisión', exact: true }).click();
    await expect(page.getByLabel('Servidor RTMP', { exact: true })).toBeVisible();
    const key = await page
      .getByLabel('Clave de emisión (solo se muestra en esta respuesta)', { exact: true })
      .inputValue();
    const config = (await (
      await page.request.get(`/api/channels/${bootstrap.channel.channelId}/streams`)
    ).json()) as StreamConfig;
    const url = `rtmp://mediamtx:1935/live/${config.streamId}?user=publisher&pass=${encodeURIComponent(key)}`;
    publisher = spawn(
      'docker',
      [
        'compose',
        '-p',
        'streaming-p1-domains',
        '-f',
        composeFile,
        'exec',
        '-T',
        'streaming',
        'sh',
        '-c',
        'echo $$ > /tmp/p1-web-source.pid; exec "$@"',
        'p1',
        'ffmpeg',
        '-hide_banner',
        '-loglevel',
        'error',
        '-re',
        '-f',
        'lavfi',
        '-i',
        'testsrc2=size=320x180:rate=25',
        '-f',
        'lavfi',
        '-i',
        'sine=frequency=1000:sample_rate=48000',
        '-c:v',
        'libx264',
        '-preset',
        'ultrafast',
        '-tune',
        'zerolatency',
        '-g',
        '25',
        '-pix_fmt',
        'yuv420p',
        '-c:a',
        'aac',
        '-f',
        'flv',
        url,
      ],
      { env: process.env, stdio: 'ignore' },
    );
    await expect
      .poll(
        async () =>
          (
            (await (await page.request.get(`/api/streams/${config.streamId}`)).json()) as {
              availability: string;
            }
          ).availability,
        { timeout: 30000 },
      )
      .toBe('PLAYABLE');
    // The real server commits the first message; drop only its ACK to exercise ambiguous retry.
    let dropped = false;
    await page.routeWebSocket('**/realtime/chat/**', (ws) => {
      const server = ws.connectToServer();
      server.onMessage((message) => {
        if (
          typeof message === 'string' &&
          (JSON.parse(message) as { type?: string }).type === 'message.accepted' &&
          !dropped
        ) {
          dropped = true;
          return;
        }
        ws.send(message);
      });
    });
    await page.goto(`/watch/${config.streamId}`);
    await expect
      .poll(
        () =>
          page
            .locator('video')
            .evaluate(
              (video: HTMLVideoElement) =>
                video.readyState >= 2 && video.videoWidth > 0 && video.currentTime > 0,
            ),
        { timeout: 30000 },
      )
      .toBe(true);
    await expect(page.getByLabel('Mensaje al canal')).toBeEnabled();
    await page.getByLabel('Mensaje al canal').fill(`Hola ${handle}`);
    await page.getByRole('button', { name: 'Enviar', exact: true }).click();
    await expect(page.getByRole('log')).toContainText(`Hola ${handle}`);
    await page.getByRole('button', { name: 'Reintentar mensaje', exact: true }).click();
    await expect(page.getByText('Esperando confirmación del mensaje.')).not.toBeVisible();
    const live = (await (await page.request.get(`/api/streams/${config.streamId}`)).json()) as {
      sessionId: string;
    };
    const historyPath = `/api/chat/sessions/${live.sessionId}/messages`;
    const history = (await (await page.request.get(historyPath)).json()) as ChatHistory;
    expect(history.items).toHaveLength(1);
    expect(history.snapshotSequence).toBe(1);
    expect(dropped).toBe(true);
    await expect
      .poll(
        async () =>
          (
            (await (
              await page.request.get(`/api/streams/sessions/${live.sessionId}`)
            ).json()) as PublicSession
          ).viewerCount,
        { timeout: 15000 },
      )
      .toBe(1);
    await page.getByRole('button', { name: 'Pausar autodesplazamiento', exact: true }).click();
    await expect(page.getByRole('log')).toHaveAttribute('aria-live', 'off');
    await page.getByRole('log').focus();
    await page.keyboard.press('ArrowUp');
    await expect(page.getByRole('log')).toBeFocused();
    compose('stop', 'chat');
    await expect(page.getByText(/Chat desconectado/)).toBeVisible({ timeout: 15000 });
    const currentTime = await page
      .locator('video')
      .evaluate((video: HTMLVideoElement) => video.currentTime);
    await expect
      .poll(() => page.locator('video').evaluate((video: HTMLVideoElement) => video.currentTime), {
        timeout: 15000,
      })
      .toBeGreaterThan(currentTime + 1);
    compose('start', 'chat');
    await expect(page.getByLabel('Mensaje al canal')).toBeEnabled({ timeout: 30000 });
    await expect(page.getByRole('log')).toContainText(`Hola ${handle}`);
    await page.goto(`/search?q=${handle}`);
    await page.getByLabel('Categoría', { exact: true }).selectOption(categoryId);
    await expect(page.getByRole('link', { name: `Directo ${handle}`, exact: true })).toBeVisible({
      timeout: 15000,
    });
    await page
      .getByLabel('Etiqueta', { exact: true })
      .selectOption(catalog.tags.find((item) => item.id !== tagId)!.id);
    await expect(page.getByRole('heading', { name: 'No encontramos transmisiones' })).toBeVisible();
    compose(
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      'core',
      '-d',
      'core',
      '-v',
      'ON_ERROR_STOP=1',
      '-c',
      `UPDATE taxonomy.categories SET active=false WHERE id='${categoryId}'; UPDATE taxonomy.tags SET active=false WHERE id='${tagId}';`,
    );
    await page.goto('/studio');
    await expect(
      page.getByRole('option', { name: `${categoryName} (no activa)`, exact: true }),
    ).toBeAttached();
    await expect(
      page.getByRole('button', { name: `${tagName} (no activa)`, exact: true }),
    ).toBeVisible();
    await page.getByLabel('Título de la transmisión').fill(`Editado ${handle}`);
    await page.getByRole('button', { name: 'Guardar metadatos', exact: true }).click();
    await expect
      .poll(
        async () =>
          (
            (await (await page.request.get(`/api/streams/${config.streamId}`)).json()) as {
              title: string;
            }
          ).title,
      )
      .toBe(`Editado ${handle}`);
    await page.getByRole('button', { name: 'Terminar emisión', exact: true }).click();
    await expect
      .poll(
        async () =>
          (
            (await (
              await page.request.get(`/api/streams/sessions/${live.sessionId}`)
            ).json()) as PublicSession
          ).status,
      )
      .toBe('ENDED');
    compose('exec', '-T', 'streaming', 'sh', '-c', 'kill -INT "$(cat /tmp/p1-web-source.pid)"');
    publisher = null;
    await page.getByRole('button', { name: 'Rotar clave', exact: true }).click();
    await expect(
      page.getByLabel('Clave de emisión (solo se muestra en esta respuesta)', { exact: true }),
    ).toBeVisible();
    await page.goto(`/watch/${config.streamId}`);
    await expect(page.getByText('El chat está en modo lectura.')).toBeVisible();
    await expect(page.getByRole('log')).toContainText(`Hola ${handle}`);
    await page.goto(`/channels/${handle}`);
    compose('stop', 'streaming');
    await expect(page.getByText(/El estado de la emisión no está disponible/)).toBeVisible({
      timeout: 15000,
    });
    await expect(page.getByText('Descripción pública integrada.')).toBeVisible();
    compose('start', 'streaming');
    for (const route of [
      '/profile',
      '/studio/channel',
      '/register',
      '/login',
      `/channels/${handle}`,
      '/search',
    ]) {
      await page.goto(route);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      const accessibility = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
        .analyze();
      expect(
        accessibility.violations.map((item) => ({ id: item.id, impact: item.impact })),
      ).toEqual([]);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
    }
    await page.goto('/profile');
    await page.getByRole('button', { name: 'Salir', exact: true }).click();
    await expect(page).toHaveURL('/login');
    await page.goto('/profile');
    await expect(page).toHaveURL('/login');
    expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
    await page.goto('/');
    await expect(
      page.getByRole('heading', { name: 'Explorar transmisiones', exact: true }),
    ).toBeVisible();
    await page.keyboard.press('Control+k');
    await expect(page.getByRole('searchbox')).toBeFocused();
    if (testInfo.project.name === 'mobile') {
      await page.getByRole('button', { name: 'Abrir navegación', exact: true }).click();
      await expect(page.getByRole('dialog')).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).not.toBeVisible();
    }
  } finally {
    if (publisher) {
      try {
        compose('exec', '-T', 'streaming', 'sh', '-c', 'kill -INT "$(cat /tmp/p1-web-source.pid)"');
      } catch {
        publisher.kill();
      }
    }
    compose('start', 'core', 'streaming', 'chat');
  }
});

test('proxy preserves deep links and never returns SPA HTML for API/private paths', async ({
  page,
}) => {
  for (const path of [
    '/',
    '/search?q=fixture',
    '/channels/p1_fixture',
    '/register',
    '/login',
    '/design-system/components',
  ]) {
    const response = await page.goto(path);
    expect(response?.status()).toBe(200);
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  }
  for (const path of [
    '/api/missing',
    '/api/identity/missing',
    '/internal/core/chat/sessions/ses_fixture',
    '/actuator/health',
    '/realtime/missing',
  ]) {
    const response = await page.request.get(path);
    expect(response.status()).toBeGreaterThanOrEqual(400);
    expect(await response.text()).not.toContain('<html');
  }
});
