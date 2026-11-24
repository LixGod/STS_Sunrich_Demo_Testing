import { defineConfig, devices } from '@playwright/test';

if (process.env['STS_REAL_E2E'] !== 'local') {
  throw new Error('Real-backend E2E is restricted to the local synthetic-data backend (STS_REAL_E2E=local).');
}

export default defineConfig({
  testDir: './e2e-real',
  outputDir: './test-results-real',
  timeout: 45_000,
  reporter: [['list']],
  use: { baseURL: 'http://127.0.0.1:4301', trace: 'retain-on-failure' },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile-chrome', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: 'npx ng serve --host 127.0.0.1 --port 4301',
    url: 'http://127.0.0.1:4301',
    reuseExistingServer: false,
    timeout: 180_000,
  },
});
