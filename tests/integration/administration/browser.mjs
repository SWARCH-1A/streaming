import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const require = createRequire(new URL('../../../apps/web/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const { default: AxeBuilder } = require('@axe-core/playwright');
const origin = process.env.ADMIN_TEST_ORIGIN;
const database = process.env.ADMIN_TEST_DB;
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(origin ?? '') || !/^streaming-admin-test-[a-f0-9]{10}-db$/.test(database ?? ''))
  throw new Error('Use run.py with its disposable fixture.');
const browser = await chromium.launch();
try {
  const context = await browser.newContext({ baseURL: origin });
  const visitor = await browser.newContext({ baseURL: origin });
  async function mutate(api, path, data, method = 'POST', key = randomUUID()) {
    const csrf = await (await api.get('/api/identity/csrf')).json();
    return api.fetch(path, { method, data, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': key, Origin: origin } });
  }
  const suffix = randomUUID().replaceAll('-', '').slice(0, 8);
  const adminHandle = 'admin_' + suffix, viewerHandle = 'viewer_' + suffix;
  const password = 'fixture-only administration password';
  const registration = await mutate(context.request, '/api/identity/registrations', { email: adminHandle + '@example.test', handle: adminHandle, password });
  expect(registration.status()).toBe(201);
  const actor = await registration.json();
  const target = await (await mutate(visitor.request, '/api/identity/registrations', { email: viewerHandle + '@example.test', handle: viewerHandle, password })).json();
  execFileSync('docker', ['exec', '-i', database, 'psql', '-U', 'fixture', '-d', 'core', '-v', 'target_user=' + actor.userId],
    { input: readFileSync(new URL('../../../services/core/scripts/provision-admin.sql', import.meta.url)), stdio: ['pipe', 'pipe', 'pipe'] });
  expect((await mutate(visitor.request, '/api/identity/sessions', { login: viewerHandle, password })).status()).toBe(200);
  expect((await visitor.request.get('/api/admin/users')).status()).toBe(403);
  const page = await context.newPage();
  // Direct navigation must retain Recovery and Administration after merging their routes.
  await page.goto('/forgot-password');
  await expect(page.getByRole('heading', { name: 'Recupera tu cuenta' })).toBeVisible();
  await page.getByLabel('Correo electrónico').fill('missing@example.test');
  await page.getByRole('button', { name: 'Enviar correo de recuperación' }).click();
  await expect(page.getByText('Si existe una cuenta asociada, recibirás un correo para restablecer la contraseña.')).toBeVisible();
  await page.goto('/reset-password');
  await expect(page.getByRole('heading', { name: 'Restablece tu contraseña' })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('El enlace no es válido');
  await page.goto('/admin');
  await page.waitForURL((url) => url.pathname === '/login');
  await page.goto('/login');
  await page.getByLabel('Handle o correo').fill(adminHandle);
  await page.getByLabel('Contraseña', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await page.waitForURL((url) => url.pathname !== '/login');
  await page.getByRole('link', { name: 'Administración', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Suspender ' + viewerHandle, exact: true })).toBeVisible();
  const path = '/api/admin/users/' + target.userId + '/status';
  const command = { status: 'SUSPENDED', expectedVersion: 0, reason: 'Fixture suspension' };
  expect((await context.request.put(path, { data: command, headers: { 'Idempotency-Key': randomUUID() } })).status()).toBe(403);
  const key = randomUUID();
  const first = await mutate(context.request, path, command, 'PUT', key);
  expect(first.status()).toBe(200);
  const replay = await mutate(context.request, path, command, 'PUT', key);
  expect(await replay.json()).toEqual(await first.json());
  expect((await visitor.request.get('/api/profile/me')).status()).toBe(401);
  expect((await mutate(context.request, path, { status: 'ACTIVE', expectedVersion: 1, reason: 'Fixture reactivation' }, 'PUT')).status()).toBe(200);
  expect((await visitor.request.get('/api/profile/me')).status()).toBe(401);
  await page.getByRole('button', { name: 'Actualizar listados' }).click();
  await page.getByRole('button', { name: 'Suspender ' + viewerHandle, exact: true }).click();
  await expect(page.getByLabel('Motivo')).toBeFocused();
  await page.getByRole('button', { name: 'Confirmar cambio' }).click();
  await expect(page.getByLabel('Motivo')).toHaveAttribute('aria-invalid', 'true');
  await page.getByLabel('Motivo').fill('Cambio desde el navegador');
  // Simulate a real concurrent administrator change, not a mocked HTTP response.
  expect((await mutate(context.request, path, { status: 'SUSPENDED', expectedVersion: 2, reason: 'Concurrent fixture' }, 'PUT')).status()).toBe(200);
  await page.getByRole('button', { name: 'Confirmar cambio' }).click();
  await expect(page.getByRole('alert')).toContainText('ACCOUNT_VERSION_CONFLICT');
  await expect(page.getByLabel('Motivo')).toHaveAttribute('aria-invalid', 'false');
  await page.getByRole('button', { name: 'Actualizar listados' }).click();
  await page.getByRole('button', { name: 'Reactivar ' + viewerHandle, exact: true }).click();
  await page.getByLabel('Motivo').fill('Reactivación desde el navegador');
  await page.getByRole('button', { name: 'Confirmar cambio' }).click();
  await expect(page.getByRole('heading', { name: 'Administración', exact: true })).toBeFocused();
  await expect(page.getByText('Estado guardado.', { exact: true })).toBeVisible();
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  }
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
  expect(result.violations.map(({ id }) => id)).toEqual([]);
  expect((await visitor.request.get('/internal/core/access-context')).status()).toBe(404);
  console.log('PASS: real Web/Core/Caddy, recovery routes/request, role/CSRF, suspension/reactivation, revoked sessions, idempotency, conflict, focus, responsive layout and Axe');
} finally { await browser.close(); }
