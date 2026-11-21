// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: what the browser keeps and what URLs carry (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for (reasonable security safeguards, DPDP Act s.8(5); DPDP Rules
 * r.6(1)(a)-(b)):
 * - W-07: the bearer token lives in sessionStorage only and sign-out removes it and the seat; after sign-out no web
 *   storage holds the token, an API token or a JWT. The saved inbox filters stay in localStorage after sign-out (P35,
 *   an intended feature), but, by owner decision 337, without the free-text search and under a hash of the user, never
 *   the user id (savedFilters.ts);
 * - W-08 (partial): during the data principal's grievance flow no request URL and no address-bar URL carries the token or
 *   the text the data principal typed. Not covered and listed for the owner: the compliance register filters, which
 *   put the data-principal reference in the query string (the REST contract has only a `subject` query parameter).
 * Signed in through the sign-in screen (not the fixture's init script), so that sign-out really ends the session.
 */
import { randomUUID } from 'node:crypto';
import type { Page } from '@playwright/test';
import { expect, test } from './support/fixtures';
import { t } from './support/env';

const JWT = /eyJ[\w-]+\.[\w-]+\.[\w-]*/;

async function webStorage(page: Page): Promise<{ local: Record<string, string>; session: Record<string, string> }> {
  return page.evaluate(() => {
    const dump = (s: Storage) => Object.fromEntries(Array.from({ length: s.length }, (_, i) => s.key(i)!).map((k) => [k, s.getItem(k) ?? '']));
    return { local: dump(window.localStorage), session: dump(window.sessionStorage) };
  });
}

test('after sign-out no storage holds a token, and no URL carried the token or what the data principal typed (W-07, W-08)', async ({ page, seed }) => {
  const citizen = seed.identities.citizen;
  const marker = `E2E-MARKER-${randomUUID().slice(0, 8)}`;
  const urls: string[] = [];
  page.on('request', (r) => urls.push(r.url()));
  page.on('framenavigated', (f) => {
    if (f === page.mainFrame()) urls.push(f.url());
  });

  await page.goto('/');
  await page.getByLabel(t('auth.devToken')).fill(citizen.token);
  await page.getByRole('button', { name: t('auth.signIn') }).click();
  await expect(page.getByRole('button', { name: t('auth.signOut') })).toBeVisible();
  // while signed in: the token is in sessionStorage, never in localStorage
  const during = await webStorage(page);
  expect(during.session['sangam.devToken']).toBe(citizen.token);
  expect(JSON.stringify(during.local)).not.toContain(citizen.token);

  await page.goto('/portal/grievances');
  await page.getByLabel(t('task.subject')).fill(`Subject ${marker}`);
  await page.getByLabel(t('portal.details')).fill(`Details ${marker}`);
  await page.getByRole('button', { name: t('common.submit') }).click();
  await expect(page.getByRole('row').filter({ hasText: marker })).toBeVisible();

  await page.getByRole('button', { name: t('auth.signOut') }).click();
  await expect(page.getByRole('heading', { name: t('auth.title') })).toBeVisible();

  const after = await webStorage(page);
  expect(Object.keys(after.session).filter((k) => k === 'sangam.devToken' || k === 'sangam.seat')).toEqual([]);
  const everything = JSON.stringify(after);
  expect(everything).not.toContain(citizen.token);
  expect(everything).not.toContain('sgm_');
  expect(everything).not.toMatch(JWT);
  expect(everything).not.toContain(marker);

  for (const url of urls) {
    expect(url, 'URL carries the token').not.toContain(citizen.token);
    expect(url, 'URL carries an API token').not.toContain('sgm_');
    expect(url, 'URL carries what the data principal typed').not.toContain(marker);
  }
});

test('a saved inbox filter keeps neither the search text nor the user id in localStorage (W-07, decision 337)', async ({ signedIn: page, api }) => {
  const me = (await (await api.ctx.get('me')).json()) as { userId: string };
  expect(me.userId).toBeTruthy();
  const marker = `E2E-SEARCH-${randomUUID().slice(0, 8)}`;
  const filterName = `E2E filter ${randomUUID().slice(0, 8)}`;

  await page.goto('/tasks');
  const search = page.getByRole('searchbox', { name: t('task.search'), exact: true });
  await search.fill(marker);
  await search.press('Enter');
  await page.getByRole('button', { name: t('task.saveFilter') }).click();
  const side = page.getByRole('complementary', { name: t('task.filterSidebar') });
  await side.getByLabel(t('common.name')).fill(filterName);
  await side.getByRole('button', { name: t('common.save') }).click();
  await expect(side.getByRole('button', { name: filterName, exact: true })).toBeVisible();

  await expect.poll(async () => JSON.stringify((await webStorage(page)).local)).toContain(filterName);
  const { local } = await webStorage(page);
  const keys = Object.keys(local).filter((k) => k.startsWith('sangam.inbox.'));
  expect(keys.length, 'one saved-filter key').toBeGreaterThan(0);
  for (const k of keys) expect(k).toMatch(/^sangam\.inbox\.savedFilters\.[0-9a-f]{64}$/);
  expect(Object.keys(local).filter((k) => k.includes(me.userId)), 'a storage key names the user').toEqual([]);
  expect(JSON.stringify(local), 'the search text is stored').not.toContain(marker);

  // the filter survives a reload (it is kept), and applying it does not bring the search text back
  await page.reload();
  await side.getByRole('button', { name: filterName, exact: true }).click();
  await expect(page.getByRole('searchbox', { name: t('task.search'), exact: true })).toHaveValue('');
});
