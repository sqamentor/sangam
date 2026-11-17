// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: an expired credential ends the session (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix row and the clause it gives evidence for:
 * - W-09: once the bearer token has expired the server refuses it (401) and the UI drops back to the sign-in screen
 *   instead of going on with stale data (access control over computer resources, DPDP Rules r.6(1)(b));
 * - W-09, idle part (decision 349): the UI idle period is deployment configuration (/config.json session.idleMinutes,
 *   SANGAM_UI_IDLE_MINUTES of the UI image, default 15); a warning with "Stay signed in" comes first, and on idle the
 *   session ends: the token leaves sessionStorage and the user must sign in again. The legal source of a specific
 *   period stays UNVERIFIED in the matrix. The idle tests shorten the period to one minute by serving the page a
 *   /config.json with session.idleMinutes 1 (the deployment setting the UI reads), so the run does not wait 15 minutes.
 * The token is a real API token with a short lifetime, issued by the development administrator through the product
 * API (support/identities.ts createIdentity; ApiTokenService refuses it once expires_at has passed, by the database
 * clock). The UI's 401 handling is AuthProvider.tsx (loadMe, setUnauthenticatedHandler).
 */
import { randomUUID } from 'node:crypto';
import type { Page } from '@playwright/test';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { devToken, t } from './support/env';
import { IDENTITY_SCOPES, createIdentity } from './support/identities';

const LIFETIME_MS = 30_000;
/** The shortened idle period of the idle tests: the warning shows after 30 s (half of it), the end comes at 60 s. */
const IDLE_MINUTES = 1;

/** Serves the page the image's own /config.json with session.idleMinutes replaced, then signs in on the sign-in screen. */
async function signInWithIdle(page: Page, minutes: number): Promise<void> {
  await page.route('**/config.json', async (route) => {
    const res = await route.fetch();
    const body = (await res.json()) as Record<string, unknown>;
    await route.fulfill({ response: res, json: { ...body, session: { idleMinutes: minutes } } });
  });
  await page.goto('/');
  await page.getByLabel(t('auth.devToken')).fill(devToken());
  await page.getByRole('button', { name: t('auth.signIn') }).click();
  await expect(page.getByRole('button', { name: t('auth.signOut') })).toBeVisible();
}

const devTokenInSession = (page: Page) => page.evaluate(() => window.sessionStorage.getItem('sangam.devToken'));

test('the UI image serves the idle period as deployment configuration, 15 minutes by default (W-09, decision 349)', async ({ request }) => {
  const res = await request.get('/config.json');
  expect(res.status()).toBe(200);
  const body = (await res.json()) as { session?: { idleMinutes?: unknown } };
  expect(body.session?.idleMinutes).toBe(15);
});

test('after the idle period without input the session ends and the user must sign in again (W-09, decision 349)', async ({ page }) => {
  test.setTimeout(150_000);
  await signInWithIdle(page, IDLE_MINUTES);
  await page.goto('/tasks');
  await expect(page.getByRole('button', { name: t('auth.signOut') })).toBeVisible();
  expect(await devTokenInSession(page)).not.toBeNull();

  // no input from here on: first the warning, then the end of the session
  const warning = page.getByRole('alertdialog', { name: t('auth.idleWarningTitle') });
  await expect(warning).toBeVisible({ timeout: 45_000 });
  await expect(page.getByRole('heading', { name: t('auth.title') })).toBeVisible({ timeout: 45_000 });
  await expect(page.getByRole('status').filter({ hasText: t('auth.idleSignedOut', { minutes: IDLE_MINUTES }) })).toBeVisible();
  await expect(page.getByRole('button', { name: t('auth.signOut') })).toHaveCount(0);
  expect(await devTokenInSession(page), 'the token is gone').toBeNull();

  // a reload does not bring the session back: the user must sign in again
  await page.reload();
  await expect(page.getByRole('heading', { name: t('auth.title') })).toBeVisible();
  await expect(page.getByRole('button', { name: t('auth.signOut') })).toHaveCount(0);
});

test('"Stay signed in" on the idle warning keeps the session (W-09, decision 349)', async ({ page }) => {
  test.setTimeout(150_000);
  await signInWithIdle(page, IDLE_MINUTES);
  await page.goto('/tasks');
  const warning = page.getByRole('alertdialog', { name: t('auth.idleWarningTitle') });
  await expect(warning).toBeVisible({ timeout: 45_000 });
  // The button has the focus (autoFocus) and is pressed from the keyboard. A mouse click first moves the pointer, and
  // any input, a pointer move included, already keeps the session and closes the warning (idle.ts ACTIVITY_EVENTS):
  // in Firefox the warning was gone before the click landed ("<html> intercepts pointer events", run 44124788893).
  const stay = warning.getByRole('button', { name: t('auth.idleStay') });
  await expect(stay).toBeFocused();
  await stay.press('Enter');
  await expect(warning).toBeHidden();
  // past the end of the first period (the click started a new one): still signed in
  await page.waitForTimeout(35_000);
  await expect(page.getByRole('button', { name: t('auth.signOut') })).toBeVisible();
  expect(await devTokenInSession(page)).not.toBeNull();
});

test('an expired token is refused and the UI returns to the sign-in screen (W-09)', async ({ page, api }) => {
  test.setTimeout(150_000);
  const expiresAt = new Date(Date.now() + LIFETIME_MS);
  const short = await createIdentity(api.ctx, `e2e-short-lived-${randomUUID().slice(0, 8)}`, IDENTITY_SCOPES.citizen, expiresAt);

  await signInAs(page, short.token);
  await page.goto('/portal/grievances');
  await expect(page.getByRole('heading', { name: t('portal.grievancesTitle') })).toBeVisible();

  const ctx = await apiAs(short.token);
  try {
    await expect(ctx.get('me').then((r) => r.status())).resolves.toBe(200);
    // the server decides with its own clock: wait until it refuses the token
    await expect.poll(async () => (await ctx.get('me')).status(), { timeout: LIFETIME_MS + 60_000, intervals: [2_000] }).toBe(401);
  } finally {
    await ctx.dispose();
  }

  // the page still holds the token in sessionStorage; the next call is refused and the UI shows sign-in
  await page.reload();
  await expect(page.getByRole('heading', { name: t('auth.title') })).toBeVisible();
  await expect(page.getByRole('heading', { name: t('portal.grievancesTitle') })).toHaveCount(0);
});
