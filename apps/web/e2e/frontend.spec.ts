import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('explorar, buscar y refrescar una ruta profunda', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Gran Final');
  await page.getByRole('searchbox').fill('Programación');
  await page.getByRole('button', { name: 'Buscar', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Programación');
  await page
    .getByRole('link', {
      name: 'Ver Programación: creando un motor de juegos en Rust',
      exact: true,
    })
    .click();
  await expect(page).toHaveURL(/watch\/rust-engine/);
  await page.reload();
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Rust');
});

test('el acceso al chat lleva el foco al destino', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: 'Entrar al chat', exact: true }).click();
  await expect(page).toHaveURL(/#chat$/);
  await expect(page.locator('#chat')).toBeFocused();
});

test('filtros compartidos y resultados vacíos', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Categoría', { exact: true }).selectOption('science');
  await page.getByLabel('Etiqueta', { exact: true }).selectOption('programming');
  await expect(page.getByRole('status').filter({ hasText: 'transmisiones' })).toContainText(
    '1 transmisiones',
  );
  await page.getByLabel('Categoría', { exact: true }).selectOption('music');
  await expect(page.getByRole('heading', { name: 'No encontramos transmisiones' })).toBeVisible();
  await page.getByRole('button', { name: 'Limpiar filtros' }).click();
  await expect(page).toHaveURL('/');
});

test('sesión demo, metadatos, perfil y cierre', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Handle de demostración').fill('demo_01');
  await page.getByLabel('Contraseña', { exact: true }).fill('demopassword12');
  await page.getByRole('button', { name: 'Iniciar sesión de demostración' }).click();
  await expect(page).toHaveURL('/studio');
  await page.getByLabel('Título de la transmisión').fill('Un nuevo directo de ejemplo');
  await page.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect(page.getByText('Metadatos guardados en esta demostración.')).toBeVisible();
  await page.getByRole('button', { name: 'Terminar demostración' }).click();
  await expect(page.getByRole('heading', { name: 'La emisión ha finalizado' })).toBeVisible();
  await page.getByRole('button', { name: 'Rotar clave de ejemplo' }).click();
  await expect(page.getByLabel('Clave de ejemplo', { exact: true })).toHaveValue(
    'DEMO-NOT-A-REAL-KEY-2',
  );
  await page.getByRole('main').getByRole('link', { name: 'Mi perfil', exact: true }).click();
  await page.getByLabel('Nombre visible').fill('Creador Demo');
  await page.getByRole('button', { name: 'Guardar cambios', exact: true }).click();
  await expect(page.getByText('Perfil actualizado en esta demostración.')).toBeVisible();
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Guardar cambios', exact: true })).toBeDisabled();
  await page.reload();
  await expect(
    page.getByText('Estás viendo un perfil de ejemplo.', { exact: false }),
  ).toBeVisible();
});

test('chat falla independientemente y el autoscroll se puede pausar', async ({ page }) => {
  await page.goto('/watch/esports-championship');
  await page.getByRole('button', { name: 'Pausar autodesplazamiento' }).click();
  await expect(page.getByRole('log')).toHaveAttribute('aria-live', 'off');
  await page.getByRole('button', { name: 'Simular chat no disponible' }).click();
  await expect(page.getByRole('alert')).toContainText('Chat no disponible');
  await expect(page.getByRole('button', { name: 'Pausar vista previa' })).toBeEnabled();
  await page.getByRole('button', { name: 'Reconectando', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Reconectando la señal' })).toBeVisible();
  await page.getByRole('button', { name: 'Error', exact: true }).click();
  await page.getByRole('button', { name: 'Reintentar', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Pausar vista previa' })).toBeEnabled();
});

test('teclado, menú móvil y dimensiones sin desbordamiento', async ({ page }, testInfo) => {
  await page.goto('/');
  await page.keyboard.press('Control+k');
  await expect(page.getByRole('searchbox')).toBeFocused();
  if (testInfo.project.name === 'mobile') {
    await page.getByRole('button', { name: 'Abrir navegación' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).not.toBeVisible();
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
});

for (const route of [
  '/',
  '/search?q=Elena',
  '/watch/esports-championship',
  '/channels/elena_algoritmos',
  '/studio',
  '/studio/channel',
  '/register',
  '/profile',
  '/design-system',
  '/design-system/components',
  '/prototype',
  '/ruta-inexistente',
]) {
  test(`ruta ${route}: semántica y accesibilidad automática`, async ({ page }, testInfo) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(route);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.locator('img')).not.toHaveCount(0);
    await expect
      .poll(() =>
        page
          .locator('img:visible')
          .evaluateAll(
            (images) =>
              images.filter(
                (image) =>
                  image instanceof HTMLImageElement &&
                  image.getBoundingClientRect().top < window.innerHeight &&
                  image.getBoundingClientRect().bottom > 0 &&
                  (!image.complete || image.naturalWidth === 0),
              ).length,
          ),
      )
      .toBe(0);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    const accessibility = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze();
    await page.screenshot({ path: testInfo.outputPath('page.png'), fullPage: true });
    expect(
      accessibility.violations.map((violation) => ({
        id: violation.id,
        nodes: violation.nodes.map((node) => ({ html: node.html, summary: node.failureSummary })),
      })),
    ).toEqual([]);
    expect(errors).toEqual([]);
  });
}
