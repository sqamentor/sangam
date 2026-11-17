// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: untrusted text is rendered as text (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix row and the clause it gives evidence for:
 * - W-03: no unsafe DOM sink; markup a data principal or an administrator typed is shown literally and never becomes an
 *   element (OWASP WSTG client-side testing, the web application testing the CERT-In audit guidelines name; reasonable
 *   security safeguards, DPDP Act s.8(5)). Two paths: a grievance subject the data principal typed (portal grievances
 *   table) and a purpose name the administrator typed, which the server prints into the public privacy policy
 *   (ConsentNoticeService.privacyPolicy; the UI renders `text`, never `html`, NoticeView in compliance.tsx).
 * Each payload names a probe URL of its own: a payload that became an element would request it, add an element with an
 * event handler, raise a dialog, or trip the CSP (script-src 'self' blocks inline handlers); none of them may happen.
 */
import { randomUUID } from 'node:crypto';
import type { Page } from '@playwright/test';
import { expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';
import { definePurpose, tenantOf, uniqueCode } from './support/compliance';

function payload(probe: string): string {
  return `<img src="/${probe}.png" onerror="window.__e2eXss=1"><svg onload="window.__e2eXss=1"></svg>`;
}

/** Records what an executed payload would leave behind. */
async function watch(page: Page, probe: string): Promise<() => Promise<void>> {
  const dialogs: string[] = [];
  const probeRequests: string[] = [];
  page.on('dialog', (d) => {
    dialogs.push(d.message());
    void d.dismiss();
  });
  page.on('request', (r) => {
    if (r.url().includes(probe)) probeRequests.push(r.url());
  });
  await page.addInitScript(() => {
    const seen: string[] = [];
    (window as unknown as { __e2eCsp: string[] }).__e2eCsp = seen;
    // the directive with where it happened, so a failure names the sink (report-only Trusted Types included)
    document.addEventListener('securitypolicyviolation', (e) => seen.push(`${e.violatedDirective} ${e.disposition} ${e.sourceFile}:${e.lineNumber}:${e.columnNumber} ${e.sample}`));
  });
  return async () => {
    const dom = await page.evaluate((p) => ({
      img: document.querySelectorAll(`img[src*="${p}"]`).length,
      handlers: document.querySelectorAll('[onerror], [onload]').length,
      flag: (window as unknown as { __e2eXss?: number }).__e2eXss ?? 0,
      csp: (window as unknown as { __e2eCsp?: string[] }).__e2eCsp ?? [],
    }), probe);
    expect(dom).toEqual({ img: 0, handlers: 0, flag: 0, csp: [] });
    expect(dialogs).toEqual([]);
    expect(probeRequests).toEqual([]);
  };
}

test('a grievance subject with markup is shown as text in the portal (W-03)', async ({ page, seed }) => {
  const probe = `e2e-xss-probe-${randomUUID()}`;
  const subject = `Subject ${payload(probe)}`;
  const check = await watch(page, probe);
  await signInAs(page, seed.identities.citizen.token);
  await page.goto('/portal/grievances');
  await page.getByLabel(t('task.subject')).fill(subject);
  await page.getByLabel(t('portal.details')).fill(`Details ${payload(probe)}`);
  await page.getByRole('button', { name: t('common.submit') }).click();
  await expect(page.getByRole('cell', { name: subject, exact: true })).toBeVisible();
  // a fresh load renders the stored value, not only the one just typed
  await page.reload();
  await expect(page.getByRole('cell', { name: subject, exact: true })).toBeVisible();
  await check();
});

test('a purpose name with markup is shown as text on the public privacy policy (W-03)', async ({ page, api }) => {
  const probe = `e2e-xss-probe-${randomUUID()}`;
  const name = `E2E purpose ${payload(probe)}`;
  const code = uniqueCode('E2E_XSS');
  const tenant = await tenantOf(api.ctx);
  await definePurpose(api.ctx, code, { name, description: 'Checks that the privacy policy prints names as text.' }, true);
  try {
    const check = await watch(page, probe);
    await page.goto(`/privacy/${encodeURIComponent(tenant)}`);
    await expect(page.locator('.notice-text')).toContainText(name);
    await check();
  } finally {
    // off the tenant's public policy again
    await definePurpose(api.ctx, code, { name: 'E2E purpose (retired)', description: 'Retired by the e2e suite.' }, false);
  }
});
