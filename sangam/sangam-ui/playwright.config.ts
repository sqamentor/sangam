// SPDX-License-Identifier: Apache-2.0
/**
 * M-048: Playwright end-to-end suite (SANGAM.md test pyramid P3, "end-to-end (UI, Playwright)").
 *
 * Runs against a running stack, never against mocks: the UI image (or `vite preview`) at SANGAM_E2E_BASE_URL and the
 * server in test mode (deploy/docker/server-test-mode.Dockerfile: in-memory H2, dev token, dev bootstrap tenant). The
 * compose-backed CI job that starts both is owned by W4-C (ui.yml); see e2e/README section in README.md.
 *
 * Status: specs written in Wave 4, not installed and not run. @playwright/test and its browsers are approved by owner
 * decision 106 (Q-11) but are downloaded only in the test phase, with the approval line logged (DOWNLOADS log).
 * Spec files end in .e2e.ts so that vitest's default *.test / *.spec globs never pick them up.
 */
import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.SANGAM_E2E_BASE_URL ?? 'http://localhost:8080';

export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.e2e.ts',
  globalSetup: './e2e/support/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]] : 'list',
  outputDir: 'test-results',
  use: {
    baseURL,
    locale: 'en-GB',
    timezoneId: 'UTC',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
  ],
});
