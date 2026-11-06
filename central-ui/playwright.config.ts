import { defineConfig } from '@playwright/test';

const docsSpec = /docs-screenshots\.spec\.ts/;

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
    headless: true,
  },
  reporter: 'list',
  // Playwright empties a project's outputDir before it runs. The docs generator writes into
  // test-results/docs-screenshots/, so it gets its own project with its own artifact folder: a
  // partial re-run (`-g`, `--last-failed`) of the generator keeps the pairs it does not retake.
  projects: [
    { name: 'console', testIgnore: docsSpec },
    { name: 'docs', testMatch: docsSpec, outputDir: 'test-results/docs-artifacts' },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    env: {
      PLAYWRIGHT: 'true',
    },
  },
});
