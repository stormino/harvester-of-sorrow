import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  globalSetup: './helpers/app-lifecycle.ts',
  globalTeardown: './helpers/app-lifecycle.ts',

  // Tests share a single booted app and SQLite DB — no parallel execution
  fullyParallel: false,
  workers: 1,

  retries: process.env.CI ? 1 : 0,

  // Real downloads take real minutes
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
