import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './samples/web',
  testMatch: 'browser.spec.mjs',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:8765',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'python3 -m http.server 8765 --bind 127.0.0.1 --directory samples/web/dist',
    url: 'http://127.0.0.1:8765',
    reuseExistingServer: false,
    timeout: 10_000,
  },
});
