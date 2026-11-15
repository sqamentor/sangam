// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: an expired credential ends the session (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix row and the clause it gives evidence for:
 * - W-09 (the expiry part only): once the bearer token has expired the server refuses it (401) and the UI drops back to
 *   the sign-in screen instead of going on with stale data (access control over computer resources, DPDP Rules
 *   r.6(1)(b)). Not covered and listed for the owner: an idle timeout, which the UI does not have and whose period no
 *   text read so far sets (UNVERIFIED in the matrix).
 * The token is a real API token with a short lifetime, issued by the development administrator through the product
 * API (support/identities.ts createIdentity; ApiTokenService refuses it once expires_at has passed, by the database
 * clock). The UI's 401 handling is AuthProvider.tsx (loadMe, setUnauthenticatedHandler).
 */
import { randomUUID } from 'node:crypto';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';
import { IDENTITY_SCOPES, createIdentity } from './support/identities';

const LIFETIME_MS = 30_000;

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
