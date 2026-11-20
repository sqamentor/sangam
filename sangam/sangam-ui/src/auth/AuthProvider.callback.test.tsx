// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Register E-23, the callback path (AuthProvider completeCallback, AuthCallback in areas/auth/SignIn.tsx).
 *
 * - A rejected signinRedirectCallback was already shown on the callback page (role=alert); kept, and pinned here.
 * - The silent case: the identity provider signs the user in, but GET /me answers 401 to the new token (the server does
 *   not accept it: another issuer, audience or key). The user was sent back to the sign-in screen with no word, and
 *   pressing "Sign in" again came back the same way. The sign-in screen now says that the sign-in was not accepted.
 *
 * UserManager.signinRedirectCallback is replaced (the code exchange needs a live identity provider); the rest is real.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { UserManager } from 'oidc-client-ts';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get } };
});

import { ApiError } from '../api/client';
import { initI18n, tr } from '../i18n';
import { setConfig } from '../config';
import { AuthProvider, useAuth } from './AuthProvider';
import { AuthCallback, SignIn } from '../areas/auth/SignIn';

const T = tr;
const CALLBACK = '/auth/callback?code=c-1&state=s-1';
const ME = { userId: 'u1', displayName: 'User One', tenantId: 't1', roles: ['TASK_USER'], seats: [{ seatId: 's1', seatName: 'SO', designation: 'SO', orgUnitName: 'U', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' }] };

/** The routing of App.tsx Gate, as far as sign-in goes. */
function Shell() {
  const { status } = useAuth();
  const loc = useLocation();
  if (loc.pathname === '/auth/callback') return <AuthCallback />;
  if (status !== 'authenticated') return <SignIn />;
  return <output data-testid="signed-in">{loc.pathname}</output>;
}

function open() {
  window.history.replaceState({}, '', CALLBACK);
  render(<AuthProvider><MemoryRouter initialEntries={[CALLBACK]}><Shell /></MemoryRouter></AuthProvider>);
}

let callback: ReturnType<typeof vi.spyOn>;

beforeAll(() => initI18n('en'));
beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
  setConfig({ authMode: 'oidc', environment: 'test', oidc: { issuer: 'https://idp.example/realms/sangam', clientId: 'sangam-ui' } });
  api.get.mockReset();
  api.get.mockImplementation(async (path: string) => (path === '/me' ? ME : []));
  callback = vi.spyOn(UserManager.prototype, 'signinRedirectCallback').mockResolvedValue({ access_token: 'at-1', state: '/tasks' } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.history.replaceState({}, '', '/');
  setConfig({});
});

describe('E-23 OIDC callback', () => {
  it('a completed sign-in goes to the page the user came from', async () => {
    open();
    await waitFor(() => expect(screen.getByTestId('signed-in').textContent).toBe('/tasks'));
    expect(callback).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('a rejected callback is shown on the callback page', async () => {
    callback.mockRejectedValue(new Error('No matching state found in storage'));
    open();
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(T('error.generic'))).toBeTruthy();
    expect(api.get).not.toHaveBeenCalled();
  });

  it('a sign-in the server does not accept (401 on GET /me) is said on the sign-in screen', async () => {
    api.get.mockRejectedValue(new ApiError({ status: 401, title: 'Unauthorized' }));
    open();
    expect(await screen.findByRole('heading', { name: T('auth.title') })).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/me');
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe(T('auth.signInRefused'));
    expect(alert.textContent).not.toMatch(/at-1|c-1|s-1|https?:/);
  });

  it('pressing "Sign in" again takes that notice away', async () => {
    api.get.mockRejectedValue(new ApiError({ status: 401, title: 'Unauthorized' }));
    open();
    await screen.findByRole('alert');
    const redirect = vi.spyOn(UserManager.prototype, 'signinRedirect').mockImplementation(() => new Promise<void>(() => undefined));
    fireEvent.click(screen.getByRole('button', { name: T('auth.signInSso') }));
    await waitFor(() => expect(redirect).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('a GET /me that fails for another reason after the callback is shown with a retry (unchanged)', async () => {
    api.get.mockRejectedValue(new ApiError({ status: 500, title: 'Server error' }));
    open();
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(T('error.generic'))).toBeTruthy();
    expect(within(alert).getByRole('button', { name: T('common.retry') })).toBeTruthy();
  });
});
