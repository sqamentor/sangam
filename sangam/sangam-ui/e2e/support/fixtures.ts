// SPDX-License-Identifier: Apache-2.0
/**
 * M-048 fixtures:
 * - `signedIn`: a page already signed in with the test-mode dev token (the dev sign-in stores it in sessionStorage
 *   'sangam.devToken', AuthProvider.tsx DEV_TOKEN_KEY); the sign-in screen itself is covered by shell.e2e.ts;
 * - `api`: an API client with the same token, for data a spec needs of its own (a fresh task per spec, so specs never
 *   depend on each other's order);
 * - `offOrigin`: every request the page sent to another origin, for the air-gapped rule (R56).
 */
import { randomUUID } from 'node:crypto';
import { test as base, expect, request, type APIRequestContext, type Page } from '@playwright/test';
import { API_BASE, BASE_URL, devToken, readSeed, type Seed } from './env';

export interface SangamApi {
  ctx: APIRequestContext;
  /** Starts an instance of the seeded process and returns its user task id. */
  newTask(): Promise<{ processInstanceId: string; taskId: string; businessKey: string }>;
}

async function signIn(page: Page): Promise<void> {
  await signInAs(page, devToken());
}

/**
 * Signs `page` in with `token` before its first navigation (the dev sign-in's sessionStorage key). The init script runs
 * on every navigation of the page, so a spec that signs out uses the sign-in screen instead (client-storage spec).
 */
export async function signInAs(page: Page, token: string): Promise<void> {
  await page.addInitScript((tok) => window.sessionStorage.setItem('sangam.devToken', tok), token);
}

export const test = base.extend<{ signedIn: Page; api: SangamApi; offOrigin: string[]; seed: Seed }>({
  seed: async ({}, use) => {
    await use(readSeed());
  },
  offOrigin: async ({ page }, use) => {
    const origin = new URL(BASE_URL).origin;
    const seen: string[] = [];
    page.on('request', (r) => {
      const u = new URL(r.url());
      if (u.protocol === 'data:' || u.protocol === 'blob:') return;
      if (u.origin !== origin) seen.push(r.url());
    });
    await use(seen);
  },
  signedIn: async ({ page }, use) => {
    await signIn(page);
    await use(page);
  },
  api: async ({ seed }, use) => {
    // the module's `request` (APIRequest): a test object has no `request` member, so `base.request` was undefined at
    // run time (found by type-checking the suite, CERT-In / DPDP lane)
    const ctx = await request.newContext({
      baseURL: `${API_BASE.replace(/\/$/, '')}/`,
      extraHTTPHeaders: { Authorization: `Bearer ${devToken()}`, Accept: 'application/json, application/problem+json' },
    });
    const newTask = async () => {
      const businessKey = `E2E-${randomUUID()}`;
      const res = await ctx.post('process-instances', {
        headers: { 'Idempotency-Key': randomUUID() },
        data: { key: seed.definitionKey, businessKey, variables: { purpose: { type: 'String', value: 'e2e' } } },
      });
      expect(res.ok(), await res.text()).toBe(true);
      const processInstanceId = ((await res.json()) as { id: string }).id;
      let taskId: string | undefined;
      await expect
        .poll(async () => {
          const r = await ctx.get('tasks', { params: { processInstanceId } });
          const body = (await r.json()) as { id: string }[] | { items?: { id: string }[] };
          taskId = (Array.isArray(body) ? body : (body.items ?? []))[0]?.id;
          return taskId;
        })
        .toBeTruthy();
      return { processInstanceId, taskId: taskId!, businessKey };
    };
    await use({ ctx, newTask });
    await ctx.dispose();
  },
});

export { expect };
export { apiAs } from './identities';
