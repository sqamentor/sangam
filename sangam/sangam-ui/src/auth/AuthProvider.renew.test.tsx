// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Register E-23, silent renew (createUserManager: automaticSilentRenew). The real oidc-client-ts (the version of
 * package-lock.json) runs here with a stored user whose access token is about to expire, against a stubbed fetch.
 *
 * - When the renewal fails, the library raises its silentRenewError event. Nothing listened to it, and the library's own
 *   log is off, so the failure left no trace. It is now written to the console: name, OAuth error code and message
 *   only, never the error object (an ErrorResponse carries the token request's form, the refresh token included).
 * - When the access token then expires, the user was put on the sign-in screen with no word. The sign-in screen now
 *   says that the session could not be renewed.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { User } from 'oidc-client-ts';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get } };
});

import { initI18n, tr } from '../i18n';
import { setConfig } from '../config';
import { AuthProvider, useAuth } from './AuthProvider';
import { SignIn } from '../areas/auth/SignIn';

const T = tr;
const AUTHORITY = 'https://idp.example/realms/sangam';
const DISCOVERY = `${AUTHORITY}/.well-known/openid-configuration`;
const TOKEN = `${AUTHORITY}/protocol/openid-connect/token`;
const ME = { userId: 'u1', displayName: 'User One', tenantId: 't1', roles: ['TASK_USER'], seats: [{ seatId: 's1', seatName: 'SO', designation: 'SO', orgUnitName: 'U', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' }] };
/** The line AuthProvider writes to the console for a failed renewal. */
const RECORD = /^Session renewal failed\./;

const fetchMock = vi.fn();
const json = (body: unknown, status: number) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function Shell() {
  const { status } = useAuth();
  if (status !== 'authenticated') return <SignIn />;
  return <output data-testid="signed-in">yes</output>;
}

let warnings: ReturnType<typeof vi.spyOn>;
const records = () => warnings.mock.calls.filter((c: unknown[]) => typeof c[0] === 'string' && RECORD.test(c[0]));
const advance = (ms: number) => act(() => void vi.advanceTimersByTime(ms));

/**
 * A signed-in user as oidc-client-ts stores it (sessionStorage, key oidc.user:<authority>:<client>), with an access
 * token that lives `lifetime` more seconds and a refresh token. Each test takes another lifetime: the client is one per
 * module and keeps its expiry timer by expiry time.
 */
async function signedIn(lifetime: number) {
  // fake timers that also follow real time, so the promises and waitFor still run (as in idle.test.tsx)
  vi.useFakeTimers({ shouldAdvanceTime: true, now: Date.now() });
  const now = Math.floor(Date.now() / 1000);
  const user = new User({ access_token: 'at-1', refresh_token: 'rt-1', token_type: 'Bearer', scope: 'openid', profile: { sub: 'u1', iss: AUTHORITY, aud: 'sangam-ui', exp: now + lifetime, iat: now }, expires_at: now + lifetime });
  window.sessionStorage.setItem(`oidc.user:${AUTHORITY}:sangam-ui`, user.toStorageString());
  render(<AuthProvider><Shell /></AuthProvider>);
  await waitFor(() => expect(screen.getByTestId('signed-in')).toBeTruthy());
  // the tree may have committed outside act (the sign-in promises): run its effects before the clock moves
  await act(async () => undefined);
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
  setConfig({ authMode: 'oidc', environment: 'test', oidc: { issuer: AUTHORITY, clientId: 'sangam-ui' } });
  api.get.mockReset();
  api.get.mockImplementation(async (path: string) => (path === '/me' ? ME : []));
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  warnings = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setConfig({});
});

describe('E-23 silent renew that fails', () => {
  it('is recorded when the identity provider cannot be reached', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await signedIn(20);
    advance(1500); // the library renews one second after loading a token this close to its end
    await waitFor(() => expect(records()).toHaveLength(1));
    expect(String(fetchMock.mock.calls[0][0])).toBe(DISCOVERY);
    expect(records()[0].join(' ')).toMatch(/Failed to fetch/);
    // the session itself goes on until the token ends
    expect(screen.getByTestId('signed-in')).toBeTruthy();
  });

  it('is recorded without the refresh token when the identity provider refuses it', async () => {
    fetchMock.mockImplementation(async (url: unknown) => {
      if (String(url) === DISCOVERY) return json({ issuer: AUTHORITY, authorization_endpoint: `${AUTHORITY}/protocol/openid-connect/auth`, token_endpoint: TOKEN }, 200);
      if (String(url) === TOKEN) return json({ error: 'invalid_grant', error_description: 'Session not active' }, 400);
      throw new TypeError(`unexpected request ${String(url)}`);
    });
    await signedIn(30);
    advance(1500);
    await waitFor(() => expect(records()).toHaveLength(1));
    // the refresh token did go to the token endpoint, so the library's error carries it
    const tokenCall = fetchMock.mock.calls.find((c) => String(c[0]) === TOKEN);
    expect(String((tokenCall?.[1] as RequestInit).body)).toContain('refresh_token=rt-1');
    // the record: text only, the OAuth code and message, no token
    expect(records()[0].every((a: unknown) => typeof a === 'string')).toBe(true);
    const line = records()[0].join(' ');
    expect(line).toMatch(/invalid_grant/);
    expect(line).toMatch(/Session not active/);
    expect(line).not.toMatch(/rt-1|at-1/);
  });

  it('ends on the sign-in screen with the reason when the access token then expires', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await signedIn(40);
    advance(1500);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    advance(45_000); // past the end of the token: the library raises accessTokenExpired
    await waitFor(() => expect(screen.queryByTestId('signed-in')).toBeNull());
    expect(screen.getByRole('heading', { name: T('auth.title') })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe(T('auth.sessionExpired'));
  });
});
