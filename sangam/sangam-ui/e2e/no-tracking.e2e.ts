// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: no tracking and no third-party resources (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - W-04: every script, stylesheet, font and call is same-origin (air-gapped rule R56), so no third party sees who uses
 *   the service, and no subresource integrity is needed;
 * - D-24 (a) (the no-tracking part only; the children's guardian flow is listed for the owner): no tracking,
 *   behavioural monitoring or targeted advertising (DPDP Act s.9(3)): no request, beacon or WebSocket to another
 *   origin and no third-party cookie, on the portal, privacy, compliance, administration and operations routes.
 * Extends shell.e2e.ts (which covers the inbox and operations routes) with the routes a data principal and a compliance
 * officer use.
 */
import type { Page } from '@playwright/test';
import { expect, signInAs, test } from './support/fixtures';
import { BASE_URL } from './support/env';
import { tenantOf } from './support/compliance';

const ORIGIN = new URL(BASE_URL).origin;

/** Same-origin checks the `offOrigin` fixture does not make: WebSockets, beacons, the DOM's subresources, cookies. */
async function track(page: Page): Promise<() => Promise<void>> {
  const sockets: string[] = [];
  page.on('websocket', (ws) => {
    if (new URL(ws.url()).host !== new URL(ORIGIN).host) sockets.push(ws.url());
  });
  await page.addInitScript(() => {
    const beacons: string[] = [];
    (window as unknown as { __e2eBeacons: string[] }).__e2eBeacons = beacons;
    const original = navigator.sendBeacon?.bind(navigator);
    if (original) {
      navigator.sendBeacon = (url: string | URL, data?: BodyInit | null) => {
        beacons.push(String(url));
        return original(url, data);
      };
    }
  });
  return async () => {
    const dom = await page.evaluate((origin) => {
      const foreign = (u: string) => {
        try {
          return new URL(u, location.href).origin !== origin && !u.startsWith('data:') && !u.startsWith('blob:');
        } catch {
          return true;
        }
      };
      const srcs = [
        ...Array.from(document.querySelectorAll('script[src]'), (e) => (e as HTMLScriptElement).src),
        ...Array.from(document.querySelectorAll('link[href]'), (e) => (e as HTMLLinkElement).href),
        ...Array.from(document.querySelectorAll('img[src], iframe[src]'), (e) => (e as HTMLImageElement).src),
      ];
      const beacons = (window as unknown as { __e2eBeacons?: string[] }).__e2eBeacons ?? [];
      return { subresources: srcs.filter(foreign), beacons: beacons.filter(foreign) };
    }, ORIGIN);
    expect(dom).toEqual({ subresources: [], beacons: [] });
    expect(sockets).toEqual([]);
    const host = new URL(ORIGIN).hostname;
    const thirdParty = (await page.context().cookies()).filter((c) => c.domain.replace(/^\./, '') !== host);
    expect(thirdParty).toEqual([]);
  };
}

test('the compliance, administration and privacy routes reach no other origin (W-04, D-24)', async ({ signedIn: page, offOrigin, api }) => {
  const check = await track(page);
  const tenant = await tenantOf(api.ctx);
  for (const path of ['/m/compliance', '/admin/retention', '/admin/users', '/audit', `/privacy/${encodeURIComponent(tenant)}`, '/help/about']) {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    await check();
  }
  expect(offOrigin).toEqual([]);
});

test('the portal routes of a data principal reach no other origin (W-04, D-24)', async ({ page, offOrigin, seed }) => {
  const check = await track(page);
  await signInAs(page, seed.identities.citizen.token);
  for (const path of ['/portal', '/portal/applications', '/portal/grievances', '/privacy']) {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    await check();
  }
  expect(offOrigin).toEqual([]);
});

test('the public privacy page, signed out, reaches no other origin (W-04, D-24)', async ({ page, offOrigin, api }) => {
  const tenant = await tenantOf(api.ctx);
  const check = await track(page);
  await page.goto(`/privacy/${encodeURIComponent(tenant)}`);
  await page.waitForLoadState('networkidle');
  await check();
  expect(offOrigin).toEqual([]);
});
