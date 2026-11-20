// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Register E-23: a sign-in start that fails must be told to the user. Seen live: the identity provider's discovery
 * document could not be fetched from the browser, "Sign in" was pressed and nothing happened (SignIn.tsx called
 * login() with no catch; the rejection of UserManager.signinRedirect went nowhere).
 *
 * The real oidc-client-ts (the version of package-lock.json) runs here against a stubbed fetch, so each case is the
 * library's own rejection: the request fails, times out, is answered with a page that is not JSON, with JSON that
 * cannot be read, or with an HTTP error. In every case the sign-in screen says so in an alert with a retry action, the
 * button can be pressed again, the cause is written to the console (name and message only), and what the user reads is
 * the catalogue text: no address, no library message, no stack.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { UserManager } from 'oidc-client-ts';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, get: api.get } };
});

import { ApiError } from '../../api/client';
import { initI18n, tr } from '../../i18n';
import { setConfig } from '../../config';
import { AuthProvider } from '../../auth/AuthProvider';
import { blockingA11yIssues } from '../../test/axe';
import { SignIn } from './SignIn';

const T = tr;
const AUTHORITY = 'https://idp.example/realms/sangam';
const DISCOVERY = `${AUTHORITY}/.well-known/openid-configuration`;
/** The line SignIn writes to the console for a failed start. */
const RECORD = /^Sign-in could not be started\./;

const fetchMock = vi.fn();
const answer = (body: string, status: number, type: string, statusText = '') => new Response(body, { status, statusText, headers: { 'Content-Type': type } });

/** [case, what fetch does for the discovery document, what the console record names]. */
const FAILURES: Array<[string, () => Promise<Response>, RegExp]> = [
  ['the discovery document cannot be fetched (network error)', async () => Promise.reject(new TypeError('Failed to fetch')), /Failed to fetch/],
  ['the request for it times out', async () => Promise.reject(new DOMException('The operation was aborted.', 'AbortError')), /timed out|aborted/i],
  ['the answer is not JSON (an HTML page)', async () => answer('<!doctype html><title>Sangam</title>', 200, 'text/html'), /Content-Type/],
  ['the answer is JSON that cannot be read', async () => answer('{"issuer":', 200, 'application/json'), /\S/],
  ['the identity provider answers 503', async () => answer('{}', 503, 'application/json', 'Service Unavailable'), /503/],
];

let errors: ReturnType<typeof vi.spyOn>;
const records = () => errors.mock.calls.filter((c: unknown[]) => typeof c[0] === 'string' && RECORD.test(c[0]));
const ssoButton = () => screen.getByRole('button', { name: T('auth.signInSso') }) as HTMLButtonElement;

async function pressSignIn() {
  render(<AuthProvider><SignIn /></AuthProvider>);
  fireEvent.click(await screen.findByRole('button', { name: T('auth.signInSso') }));
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
  setConfig({ authMode: 'oidc', environment: 'test', oidc: { issuer: AUTHORITY, clientId: 'sangam-ui' } });
  api.get.mockReset();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  setConfig({});
});

describe('E-23 sign-in start that fails (OIDC)', () => {
  it.each(FAILURES)('says so with a retry action when %s', async (_case, discovery, cause) => {
    fetchMock.mockImplementation(discovery);
    await pressSignIn();

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(T('auth.startFailed'))).toBeTruthy();
    expect(within(alert).getByText(T('auth.startFailedNext'))).toBeTruthy();
    expect(within(alert).getByRole('button', { name: T('common.retry') })).toBeTruthy();
    // the button is usable again, under its own name
    await waitFor(() => expect(ssoButton().disabled).toBe(false));

    // it was the discovery document that failed, once
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toBe(DISCOVERY);

    // what the user reads is the catalogue text: no address, no library message, no stack
    expect(alert.textContent).not.toMatch(/idp\.example|https?:|well-known|Content-Type|fetch|Error|\.[jt]sx?:\d+/);

    // nothing is swallowed: the cause is written once, as text (never the error object)
    expect(records()).toHaveLength(1);
    expect(records()[0].every((a: unknown) => typeof a === 'string')).toBe(true);
    expect(records()[0].join(' ')).toMatch(cause);
  });

  it('the alert passes the accessibility check and is not there before a failure', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const { container } = render(<AuthProvider><SignIn /></AuthProvider>);
    await screen.findByRole('button', { name: T('auth.signInSso') });
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(ssoButton());
    await screen.findByRole('alert');
    expect(await blockingA11yIssues(container)).toEqual([]);
  });

  it('a retry that fails again asks the identity provider again and shows the error again', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await pressSignIn();
    fireEvent.click(within(await screen.findByRole('alert')).getByRole('button', { name: T('common.retry') }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(within(await screen.findByRole('alert')).getByText(T('auth.startFailed'))).toBeTruthy();
    await waitFor(() => expect(ssoButton().disabled).toBe(false));
    expect(records()).toHaveLength(2);
  });

  it('a retry that works takes the error away and leaves for the identity provider', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await pressSignIn();
    const alert = await screen.findByRole('alert');
    // the redirect itself: a promise that stays open while the browser leaves the page
    const redirect = vi.spyOn(UserManager.prototype, 'signinRedirect').mockImplementation(() => new Promise<void>(() => undefined));
    fireEvent.click(within(alert).getByRole('button', { name: T('common.retry') }));
    await waitFor(() => expect(redirect).toHaveBeenCalledTimes(1));
    expect(redirect).toHaveBeenCalledWith({ state: window.location.pathname + window.location.search });
    expect(screen.queryByRole('alert')).toBeNull();
    const button = screen.getByRole('button', { name: T('auth.redirecting') }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(records()).toHaveLength(1);
  });

  it('pressing the sign-in button again is a retry too', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await pressSignIn();
    await screen.findByRole('alert');
    await waitFor(() => expect(ssoButton().disabled).toBe(false));
    fireEvent.click(ssoButton());
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });
});

describe('sign-in in dev-token mode (unchanged by E-23)', () => {
  it('a refused GET /me is shown with a retry, as before', async () => {
    setConfig({ authMode: 'dev' });
    api.get.mockRejectedValue(new ApiError({ status: 500, title: 'Server error' }));
    render(<AuthProvider><SignIn /></AuthProvider>);
    fireEvent.click(await screen.findByRole('button', { name: T('auth.signIn') }));
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(T('error.generic'))).toBeTruthy();
    expect(within(alert).getByRole('button', { name: T('common.retry') })).toBeTruthy();
    expect(records()).toHaveLength(0);
  });
});
