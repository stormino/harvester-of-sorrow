import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  globalSetup: './helpers/global-setup.ts',
  globalTeardown: './helpers/global-teardown.ts',

  // Tests share a single booted app and SQLite DB — no parallel execution
  fullyParallel: false,
  workers: 1,

  retries: process.env.CI ? 1 : 0,

  // Real downloads take real minutes; worst-quality movies can take 15–20 min
  timeout: 25 * 60 * 1000,

  reporter: [
    ['list'],
    ['html', { outputFolder: 'test-results/html-report', open: 'never' }],
  ],

  use: {
    baseURL: 'http://localhost:8089',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },

  outputDir: 'test-results/artifacts',

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
