// Reserve the classic scrollbar gutter even in headless Chromium. Its default
// --hide-scrollbars otherwise misses the 320px-window / 305px-content case.
export const browserOptions = { ignoreDefaultArgs: ['--hide-scrollbars'] };

export async function mobileNavigation(page, expect) {
  const viewport = page.viewportSize();
  await page.setViewportSize({ width: 320, height: 900 });
  await page.goto('/watch-parties');
  await expect(page.getByRole('heading', { level: 1, name: 'Salas de visualización conjunta' })).toBeVisible();
  await page.waitForLoadState('networkidle');
  const menu = page.getByRole('button', { name: 'Abrir navegación', exact: true });
  await menu.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'Navegación', exact: true });
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(menu).toBeFocused();
  await page.keyboard.press('Enter');
  await dialog.getByRole('link', { name: 'Mis grabaciones', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Mis grabaciones' })).toBeVisible();
  await expect(dialog).not.toBeVisible();
  if (viewport) await page.setViewportSize(viewport);
}

export async function responsive(page, expect, focusTarget) {
  const viewport = page.viewportSize();
  const style = await page.addStyleTag({ content: `
    html { overflow-y: scroll !important; }
    ::-webkit-scrollbar { width: 15px; height: 15px; }
  ` });
  try {
    for (const width of [320, 390, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      const dimensions = await page.evaluate(() => ({
        window: innerWidth, available: document.documentElement.clientWidth,
        content: document.documentElement.scrollWidth,
      }));
      expect(dimensions.available, 'The regression must reserve scrollbar space').toBe(width - 15);
      const outside = await page.locator('header button:visible, header a:visible, main button:visible, main input:visible, main select:visible, main textarea:visible')
        .evaluateAll((controls) => controls.filter((control) => {
          const box = control.getBoundingClientRect();
          return box.left < 0 || box.right > document.documentElement.clientWidth;
        }).map((control) => ({
          element: control.id || control.getAttribute('aria-label') || control.tagName,
          left: control.getBoundingClientRect().left, right: control.getBoundingClientRect().right,
        })));
      expect(dimensions.content, JSON.stringify({ ...dimensions, outside })).toBeLessThanOrEqual(dimensions.available);
      expect(outside, 'Controls must fit, not merely be clipped by an ancestor').toEqual([]);
      if (focusTarget) {
        await focusTarget.focus();
        await expect(focusTarget).toBeFocused();
        await expect(focusTarget).toBeInViewport();
        expect(await focusTarget.evaluate((control) => getComputedStyle(control).outlineStyle)).not.toBe('none');
      }
    }
  } finally {
    await style.evaluate((node) => node.remove());
    if (viewport) await page.setViewportSize(viewport);
  }
}
