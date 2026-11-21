// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 359: the Teams tab (teams.html, off by default) has the same idle sign-out as the console (decision 349):
 * a warning with "Stay signed in", then the session ends and the tab's sign-in screen says why. Inside a Teams frame
 * the identity provider's sign-out page cannot run, so there the session ends by forgetting the tab's tokens
 * (AuthProvider providerSignOut={false}); the console keeps the provider sign-out.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get } };
});
// the tab's screens are not under test here
vi.mock('../areas/tasklist/TaskInbox', () => ({ TaskInbox: () => <output data-testid="inbox">inbox</output> }));
vi.mock('../areas/tasklist/TaskDetail', () => ({ TaskDetail: () => null }));

const oidc = vi.hoisted(() => ({
  getUser: vi.fn(),
  removeUser: vi.fn(async () => undefined),
  signoutRedirect: vi.fn(async () => undefined),
}));
vi.mock('oidc-client-ts', async (orig) => {
  const real = await orig<typeof import('oidc-client-ts')>();
  class FakeUserManager {
    events = { addUserLoaded: vi.fn(), removeUserLoaded: vi.fn(), addAccessTokenExpired: vi.fn(), removeAccessTokenExpired: vi.fn(), addSilentRenewError: vi.fn(), removeSilentRenewError: vi.fn() };
    getUser = oidc.getUser;
    removeUser = oidc.removeUser;
    signoutRedirect = oidc.signoutRedirect;
  }
  return { ...real, UserManager: FakeUserManager };
});

import { initI18n, tr } from '../i18n';
import { setConfig } from '../config';
import { AuthProvider } from '../auth/AuthProvider';
import { TeamsTab } from './TeamsApp';

const T = tr;
const ME = { userId: 'u1', displayName: 'User One', tenantId: 't1', roles: ['TASK_USER'], seats: [{ seatId: 's1', seatName: 'SO', designation: 'SO', orgUnitName: 'U', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' }] };
const props = { authenticate: async () => '', userManager: { storeUser: vi.fn(async () => undefined), removeUser: vi.fn(async () => undefined) }, origin: 'https://h', base: '/' };
const advance = (ms: number) => act(() => void vi.advanceTimersByTime(ms));

beforeAll(() => initI18n('en'));
beforeEach(() => {
  window.sessionStorage.clear();
  api.get.mockReset();
  api.get.mockImplementation(async (path: string) => (path === '/me' ? ME : []));
  oidc.getUser.mockReset();
  oidc.getUser.mockResolvedValue({ access_token: 'at-1', id_token: 'id-1', expired: false });
  oidc.removeUser.mockClear();
  oidc.signoutRedirect.mockClear();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  setConfig({});
});

async function signedInTab(mode: 'dev' | 'oidc', providerSignOut?: boolean) {
  setConfig({ authMode: mode, session: { idleMinutes: 1 } });
  window.sessionStorage.setItem('sangam.devToken', 'tok-1');
  vi.useFakeTimers({ shouldAdvanceTime: true, now: Date.now() });
  render(<AuthProvider providerSignOut={providerSignOut}><TeamsTab {...props} /></AuthProvider>);
  await waitFor(() => expect(screen.getByTestId('inbox')).toBeTruthy());
  // the tab may have committed outside act (the sign-in promises): run its effects (the idle watch) before counting
  await act(async () => undefined);
  fireEvent.keyDown(window, { key: 'Shift' });
}

describe('decision 359 idle sign-out in the Teams tab', () => {
  it('warns, then ends the session and the tab sign-in screen says why', async () => {
    await signedInTab('dev');
    advance(31_000);
    expect(screen.getByRole('alertdialog', { name: T('auth.idleWarningTitle') })).toBeTruthy();
    advance(30_000);
    expect(screen.queryByTestId('inbox')).toBeNull();
    expect(screen.getByRole('button', { name: T('teams.signIn') })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe(T('auth.idleSignedOut', { minutes: 1 }));
    expect(window.sessionStorage.getItem('sangam.devToken')).toBeNull();
    expect(window.sessionStorage.getItem('sangam.seat')).toBeNull();
  });

  it('in a Teams frame forgets the tokens without the provider sign-out page', async () => {
    await signedInTab('oidc', false);
    advance(61_000);
    await waitFor(() => expect(oidc.removeUser).toHaveBeenCalled());
    expect(oidc.signoutRedirect).not.toHaveBeenCalled();
    expect(screen.getByRole('status').textContent).toBe(T('auth.idleSignedOut', { minutes: 1 }));
  });

  it('the console default still ends the provider session too', async () => {
    await signedInTab('oidc');
    advance(61_000);
    await waitFor(() => expect(oidc.signoutRedirect).toHaveBeenCalledWith({ id_token_hint: 'id-1' }));
    expect(oidc.removeUser).toHaveBeenCalled();
  });
});
