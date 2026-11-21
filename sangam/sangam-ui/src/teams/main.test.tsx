// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Register E-29(a): the boot of the Teams page (teams.html, src/teams/main.tsx) had no test harness. Here the entry
 * module itself is loaded, once per case, with the page URL of the case; the Teams SDK, the OIDC client and
 * config.json are replaced, everything else (the view switch, the screens, the texts) is real.
 *
 * Fail-first: in the sign-in pop-up (view=auth-start) a start that fails (identity provider not reachable, discovery
 * refused) was not caught: the pop-up stayed on "Opening your organisation's sign-in…" for good, and the tab that
 * opened it was never told.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { waitFor } from '@testing-library/react';

const sdk = vi.hoisted(() => ({
  initialize: vi.fn(async (_timeoutMs: number) => true),
  authenticate: vi.fn(async (_url: string) => ''),
  notifySuccess: vi.fn((_result: string) => undefined),
  notifyFailure: vi.fn((_reason: string) => undefined),
  theme: vi.fn(async () => 'default' as string | undefined),
}));
const mgr = vi.hoisted(() => ({
  signinRedirect: vi.fn(async (_args?: unknown) => undefined),
  signinRedirectCallback: vi.fn(async () => ({ toStorageString: () => 'user-as-text' })),
  removeUser: vi.fn(async () => undefined),
  storeUser: vi.fn(async () => undefined),
}));
const cfg = vi.hoisted(() => ({ teams: true }));

vi.mock('./sdk', () => ({ teamsSdk: sdk }));
vi.mock('../fonts', () => ({ applyFonts: async () => undefined }));
vi.mock('../config', async (orig) => {
  const real = await orig<typeof import('../config')>();
  return { ...real, loadConfig: async () => real.setConfig({ teams: { enabled: cfg.teams } }) };
});
vi.mock('../auth/AuthProvider', () => ({
  createUserManager: () => mgr,
  AuthProvider: ({ children }: { children: unknown }) => children,
  useAuth: () => ({ mode: 'oidc', status: 'anonymous', me: null, error: null, signedOutReason: null, reload: () => undefined, login: async () => undefined, signedIn: async () => undefined }),
}));

const STARTING = "Opening your organisation's sign-in…";
const FAILED = 'Sign-in failed. Please try again, or ask your administrator to check the Sangam Teams set-up.';

/** Loads the entry module for a page URL, as the browser does for teams.html. */
async function open(search: string) {
  document.body.innerHTML = '<div id="root"></div>';
  window.history.replaceState({}, '', `/teams.html${search}`);
  vi.resetModules();
  await import('./main');
}
const page = () => document.getElementById('root')?.textContent ?? '';

beforeEach(() => {
  cfg.teams = true;
  for (const f of [...Object.values(sdk), ...Object.values(mgr)]) f.mockClear();
  sdk.initialize.mockImplementation(async () => true);
  mgr.signinRedirect.mockImplementation(async () => undefined);
  mgr.signinRedirectCallback.mockImplementation(async () => ({ toStorageString: () => 'user-as-text' }));
});
afterEach(() => {
  document.body.innerHTML = '';
  window.history.replaceState({}, '', '/');
});

describe('Teams page boot (teams.html)', () => {
  it('pop-up start: goes to the organisation sign-in', async () => {
    await open('?view=auth-start');
    await waitFor(() => expect(mgr.signinRedirect).toHaveBeenCalledWith({ state: 'teams' }));
    expect(page()).toContain(STARTING);
    expect(sdk.notifyFailure).not.toHaveBeenCalled();
  });

  it('pop-up start that fails: the pop-up says so and tells the tab that opened it', async () => {
    mgr.signinRedirect.mockImplementation(async () => {
      throw new Error('Failed to fetch');
    });
    await open('?view=auth-start');
    await waitFor(() => expect(page()).toContain(FAILED));
    expect(page()).not.toContain(STARTING);
    expect(sdk.notifyFailure).toHaveBeenCalledTimes(1);
    expect(sdk.notifyFailure).toHaveBeenCalledWith('Failed to fetch');
  });

  it('pop-up end: hands the signed-in user to the tab and keeps nothing', async () => {
    await open('?code=c-1&state=s-1');
    await waitFor(() => expect(sdk.notifySuccess).toHaveBeenCalledWith('user-as-text'));
    expect(mgr.removeUser).toHaveBeenCalledTimes(1);
    expect(page()).toContain('Signed in. You can close this window.');
  });

  it('pop-up end that fails: says so and tells the tab', async () => {
    mgr.signinRedirectCallback.mockImplementation(async () => {
      throw new Error('No matching state found in storage');
    });
    await open('?error=access_denied&state=s-1');
    await waitFor(() => expect(sdk.notifyFailure).toHaveBeenCalledWith('No matching state found in storage'));
    expect(page()).toContain(FAILED);
  });

  it('the tab outside Teams: says where to open it', async () => {
    sdk.initialize.mockImplementation(async () => false);
    await open('');
    await waitFor(() => expect(page()).toContain('Open it from Teams'));
  });

  it('the tab inside Teams: the sign-in of the tab', async () => {
    await open('');
    await waitFor(() => expect(page()).toContain('Sangam in Microsoft Teams'));
    expect(document.querySelector('button.primary')?.textContent).toBe('Sign in');
  });

  it('switched off in config.json: says so and never asks the Teams host', async () => {
    cfg.teams = false;
    await open('');
    await waitFor(() => expect(page()).toContain('The Microsoft Teams app is not enabled'));
    expect(sdk.initialize).not.toHaveBeenCalled();
  });
});
