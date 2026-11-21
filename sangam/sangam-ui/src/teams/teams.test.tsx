// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Decision 162 (M-109): Teams pop-up OIDC sign-in reusing the UI's login, and the optional-module boundary.
// Written, not run in this phase.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { User } from 'oidc-client-ts';
import { initI18n } from '../i18n';
import { normalise } from '../config';
import { authStartUrl, completePopupLogin, readPopupResult, signInFailure, signInFromTab, startPopupLogin, teamsPageUrl, teamsView, TeamsSignInError } from './teamsAuth';
import { TeamsSignIn, TeamsUnavailable } from './TeamsApp';

const auth = { mode: 'oidc' as 'oidc' | 'dev', status: 'anonymous', login: vi.fn(async () => undefined), reload: vi.fn(), signedIn: vi.fn(async (_token: string) => undefined) };
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => auth }));

beforeAll(() => initI18n('en'));
afterEach(cleanup);

const user = (over: Partial<ConstructorParameters<typeof User>[0]> = {}) =>
  new User({ access_token: 'at-1', token_type: 'Bearer', profile: { sub: 'u1', iss: 'https://idp', aud: 'sangam-ui', exp: 0, iat: 0 }, expires_at: Math.floor(Date.now() / 1000) + 600, ...over });

describe('teamsView and URLs', () => {
  it('tells the tab, the pop-up start and the IdP answer apart', () => {
    expect(teamsView('')).toBe('tab');
    expect(teamsView('?view=auth-start')).toBe('auth-start');
    expect(teamsView('?code=c&state=s')).toBe('auth-end');
    expect(teamsView('?error=access_denied&state=s')).toBe('auth-end');
    expect(teamsView('?code=c')).toBe('tab');
  });

  it('builds the pop-up redirect URI from the origin and base path', () => {
    expect(teamsPageUrl('https://h', '/')).toBe('https://h/teams.html');
    expect(teamsPageUrl('https://h', '/sangam')).toBe('https://h/sangam/teams.html');
    expect(authStartUrl('https://h', '/')).toBe('https://h/teams.html?view=auth-start');
  });
});

describe('pop-up sign-in', () => {
  it('starts the UI OIDC login (code + PKCE in oidc-client-ts) and hands the user back as text', async () => {
    const signinRedirect = vi.fn(async () => undefined);
    await startPopupLogin({ signinRedirect } as never);
    expect(signinRedirect).toHaveBeenCalledWith({ state: 'teams' });
    const u = user();
    const result = await completePopupLogin({ signinRedirectCallback: async () => u } as never);
    expect(User.fromStorageString(result).access_token).toBe('at-1');
  });

  it('the tab accepts only a result with a live access token', () => {
    expect(readPopupResult(user().toStorageString()).access_token).toBe('at-1');
    expect(() => readPopupResult('not json')).toThrow(TeamsSignInError);
    expect(() => readPopupResult(user({ access_token: '' }).toStorageString())).toThrow(/invalid/);
    expect(() => readPopupResult(user({ expires_at: 10 }).toStorageString())).toThrow(/expired/);
  });

  it('stores the user where AuthProvider reads it', async () => {
    const storeUser = vi.fn(async () => undefined);
    const authenticate = vi.fn(async () => user().toStorageString());
    await signInFromTab(authenticate, { storeUser }, 'https://h', '/');
    expect(authenticate).toHaveBeenCalledWith('https://h/teams.html?view=auth-start');
    expect((storeUser.mock.calls[0] as unknown[])[0]).toBeInstanceOf(User);
  });

  it('a closed pop-up is "cancelled", anything else "failed"', () => {
    expect(signInFailure(new Error('CancelledByUser')).reason).toBe('cancelled');
    expect(signInFailure(new Error('boom')).reason).toBe('failed');
  });
});

describe('TeamsSignIn', () => {
  it('signs in through the pop-up, then loads the session for the new token', async () => {
    const storeUser = vi.fn(async () => undefined);
    render(<TeamsSignIn authenticate={async () => user().toStorageString()} userManager={{ storeUser, removeUser: vi.fn() }} origin="https://h" base="/" />);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    // E-29(b): signedIn (not a bare reload), so that a 401 from GET /me is told to the user
    await waitFor(() => expect(auth.signedIn).toHaveBeenCalledWith('at-1'));
    expect(storeUser).toHaveBeenCalled();
  });

  it('says so when the pop-up was closed', async () => {
    render(<TeamsSignIn authenticate={async () => { throw new Error('CancelledByUser'); }} userManager={{ storeUser: vi.fn(), removeUser: vi.fn() }} origin="https://h" base="/" />);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(screen.getByText('Sign-in was cancelled.')).toBeTruthy());
  });
});

describe('optional module', () => {
  it('is off unless config.json enables it', () => {
    expect(normalise({}).teams.enabled).toBe(false);
    expect(normalise({ teams: { enabled: true } }).teams.enabled).toBe(true);
    render(<TeamsUnavailable reason="disabled" />);
    expect(screen.getByText(/The Microsoft Teams app is not enabled/)).toBeTruthy();
  });

  it('nothing outside src/teams imports it or the Teams SDK', () => {
    const sources = import.meta.glob(['../**/*.ts', '../**/*.tsx'], { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
    expect(Object.keys(sources).length).toBeGreaterThan(50);
    const offenders = Object.entries(sources)
      .filter(([path]) => !path.startsWith('./') && !path.startsWith('../teams/'))
      .filter(([, text]) => /@microsoft\/teams-js|from '(\.\.\/)+teams\/|from '\.\/teams\//.test(text))
      .map(([path]) => path);
    expect(offenders).toEqual([]);
  });
});
