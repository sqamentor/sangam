// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: the HTTP security headers of the UI image and its build output (docs/compliance/
 * CERTIN-DPDP-CONTROL-MATRIX.md). Runs against the nginx UI image the CI job starts (ui.yml, sangam-ui:e2e), never
 * with bypassCSP: the page runs under the real policy.
 *
 * Matrix rows and the clauses they give evidence for (the web application security testing the CERT-In audit
 * guidelines name, OWASP WSTG, and the reasonable security safeguards of DPDP Act s.8(5) / DPDP Rules r.6(1)):
 * - W-01: a strict Content-Security-Policy on every route (script-src 'self', object-src 'none', base-uri 'self',
 *   frame-ancestors 'none'), and the app runs under it without one violation;
 * - W-05: no framing: X-Frame-Options DENY and frame-ancestors 'none', on /teams.html too while Teams is not enabled
 *   (decisions 162, 213: the Teams exception exists only with SANGAM_TEAMS_ENABLED=true, which the e2e job leaves off);
 * - W-06: nosniff, Referrer-Policy, Permissions-Policy, Cross-Origin-Opener-Policy, and no server version;
 * - W-11: no source map is shipped: no sourceMappingURL comment in a served script or stylesheet and no .map file
 *   (fixed in this lane: vite.config.ts build.sourcemap false; scripts/build-sourcemaps.test.mjs).
 * The expected values are those of deploy/docker/ui/security-headers.inc.template.
 */
import type { APIRequestContext, APIResponse } from '@playwright/test';
import { expect, test } from './support/fixtures';
import { tenantOf } from './support/compliance';

const FIXED_CSP: Record<string, string[]> = {
  'default-src': ["'self'"],
  'script-src': ["'self'"],
  'style-src': ["'self'", "'unsafe-inline'"],
  'img-src': ["'self'", 'data:', 'blob:'],
  'font-src': ["'self'", 'data:'],
  'frame-ancestors': ["'none'"],
  'base-uri': ["'self'"],
  'object-src': ["'none'"],
};

function csp(res: APIResponse): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const part of (res.headers()['content-security-policy'] ?? '').split(';')) {
    const [name, ...values] = part.trim().split(/\s+/);
    if (name) out.set(name, values);
  }
  return out;
}

function expectSecurityHeaders(path: string, res: APIResponse): void {
  const h = res.headers();
  expect(h['x-content-type-options'], path).toBe('nosniff');
  expect(h['x-frame-options'], path).toBe('DENY');
  expect(h['referrer-policy'], path).toBe('strict-origin-when-cross-origin');
  expect(h['permissions-policy'], path).toBe('camera=(), microphone=(), geolocation=(), payment=()');
  expect(h['cross-origin-opener-policy'], path).toBe('same-origin');
  const policy = csp(res);
  for (const [directive, values] of Object.entries(FIXED_CSP)) expect(policy.get(directive), `${path} ${directive}`).toEqual(values);
  // the issuer is the only other origin the policy may name; the e2e stack has none (dev-token sign-in)
  for (const d of ['connect-src', 'frame-src', 'form-action']) expect(policy.get(d)?.[0], `${path} ${d}`).toBe("'self'");
  expect(policy.get('script-src'), path).not.toContain("'unsafe-inline'");
  expect(policy.get('script-src'), path).not.toContain("'unsafe-eval'");
  // server_tokens off: "nginx" at most, never a version
  expect(h['server'] ?? '', path).not.toMatch(/\d/);
}

/** The scripts and stylesheets index.html loads (Vite's hashed /assets files). */
async function builtAssets(request: APIRequestContext): Promise<string[]> {
  const html = await (await request.get('/')).text();
  const urls = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+\.(?:js|css))"/g)].map((m) => m[1]);
  expect(urls.length, 'index.html loads built assets').toBeGreaterThan(0);
  return [...new Set(urls)];
}

test('every route of the UI image carries the security headers (W-01, W-05, W-06)', async ({ request, api }) => {
  const tenant = await tenantOf(api.ctx);
  const paths = ['/', '/tasks', '/m/compliance', '/portal/grievances', `/privacy/${encodeURIComponent(tenant)}`, '/config.json', '/teams.html', '/no-such-page'];
  for (const path of paths) {
    const res = await request.get(path);
    expect(res.status(), path).toBe(200); // the SPA fallback serves index.html for app routes
    expectSecurityHeaders(path, res);
  }
  for (const asset of await builtAssets(request)) {
    const res = await request.get(asset);
    expect(res.status(), asset).toBe(200);
    expectSecurityHeaders(asset, res);
  }
});

test('the build ships no source maps (W-11)', async ({ request }) => {
  for (const asset of await builtAssets(request)) {
    const body = await (await request.get(asset)).text();
    expect(body, asset).not.toMatch(/[#@]\s*sourceMappingURL=/);
    expect((await request.get(`${asset}.map`)).status(), `${asset}.map`).toBe(404);
  }
});

test('the app runs under the policy without a single violation (W-01)', async ({ signedIn: page, seed, api }) => {
  await page.addInitScript(() => {
    const seen: string[] = [];
    (window as unknown as { __cspViolations: string[] }).__cspViolations = seen;
    document.addEventListener('securitypolicyviolation', (e) => seen.push(`${e.violatedDirective} ${e.blockedURI}`));
  });
  const tenant = await tenantOf(api.ctx);
  for (const path of ['/', '/tasks', `/tasks/${seed.taskId}`, `/ops/processes/${seed.definitionId}`, '/m/compliance', `/privacy/${encodeURIComponent(tenant)}`]) {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    const violations = await page.evaluate(() => (window as unknown as { __cspViolations: string[] }).__cspViolations);
    expect(violations, path).toEqual([]);
  }
});
