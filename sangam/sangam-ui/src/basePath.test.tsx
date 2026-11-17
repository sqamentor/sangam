// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * R8-present: the UI served under a path prefix (Vite base /sangam/, deploy/presentation). config.json, the font
 * files, the OIDC redirect URI and the router all follow the base; routes and oidc.redirectPath stay prefix-free.
 * Each test sets BASE_URL and imports the modules fresh, since they read import.meta.env.BASE_URL.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

vi.mock('./areas/auth/SignIn', () => ({
  SignIn: () => <p>sign-in page</p>,
  AuthCallback: () => <p>auth callback</p>,
}));
vi.mock('./auth/AuthProvider', async (orig) => {
  const real = await orig<typeof import('./auth/AuthProvider')>();
  return { ...real, useAuth: () => ({ status: 'unauthenticated' }) };
});

const fetchMock = vi.fn();

beforeEach(() => {
  vi.resetModules();
  vi.stubEnv('BASE_URL', '/sangam/');
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('base path /sangam/', { timeout: 30_000 }, () => {
  it('helpers: prefix, app paths and router base name', async () => {
    const b = await import('./basePath');
    expect(b.basePrefix()).toBe('/sangam');
    expect(b.appPath('/config.json')).toBe('/sangam/config.json');
    expect(b.routerBasename()).toBe('/sangam');
    expect(b.basePrefix('/')).toBe('');
    expect(b.appPath('/config.json', '/')).toBe('/config.json');
    expect(b.routerBasename('/')).toBeUndefined();
  });

  it('reads config.json under the base', async () => {
    const { loadConfig } = await import('./config');
    await loadConfig();
    expect(fetchMock.mock.calls[0][0]).toBe('/sangam/config.json');
  });

  it('declares the bundled fonts and the site manifest under the base', async () => {
    const { applyFonts } = await import('./fonts');
    await applyFonts({});
    expect(fetchMock.mock.calls.map((c) => String(c[0]))).toContain('/sangam/fonts/site/fonts.json');
    const css = document.getElementById('sangam-fonts')?.textContent ?? '';
    expect(css).toContain('/sangam/fonts/noto/NotoSans-Regular.ttf');
    expect(css).not.toMatch(/url\(["']?\/fonts\//);
  });

  it('sends the OIDC redirect to the callback under the base', async () => {
    const { createUserManager, oidcRedirectUri } = await import('./auth/AuthProvider');
    expect(oidcRedirectUri()).toBe(window.location.origin + '/sangam/auth/callback');
    expect(createUserManager(oidcRedirectUri()).settings.post_logout_redirect_uri).toBe(window.location.origin + '/sangam/');
  });

});
