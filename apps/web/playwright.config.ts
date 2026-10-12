import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 180000,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: 'list',
  use: { baseURL: process.env.P1_WEB_ORIGIN ?? 'http://localhost:3000', trace: 'off' },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
  ],
  ...(process.env.P1_WEB_INTEGRATION === '1'
    ? {}
    : {
        webServer: {
          command: 'pnpm dev',
          url: 'http://127.0.0.1:3000',
          reuseExistingServer: !process.env.CI,
        },
      }),
});
