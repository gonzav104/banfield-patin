import { defineConfig, devices } from '@playwright/test';

/**
 * REAL end-to-end tests: the browser talks to the Vite dev server, which proxies /api to a running backend
 * (default http://127.0.0.1:8080) backed by PostgreSQL. Nothing about the auth flow is mocked here, except the
 * explicitly labelled "simulated outage" tests. See frontend/README.md for how to start the backend.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],
  outputDir: 'test-results',
  // H-02: no traces, automatic screenshots or videos. They record request bodies and page text (passwords, the TOTP
  // secret during enrolment, invitation links). Evidence screenshots are taken explicitly, with sensitive areas masked,
  // and the teardown redacts/verifies every text artifact (see e2e/support/artifact-check.ts).
  globalTeardown: './e2e/support/artifact-check.ts',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173/login',
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
