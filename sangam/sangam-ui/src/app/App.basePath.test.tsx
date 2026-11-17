// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * R8-present: under the Vite base /sangam/ the router takes the base as its base name, so /sangam/auth/callback is the
 * callback route (oidc.redirectPath "/auth/callback" stays prefix-free). Without the base name the path would not match
 * and the sign-in page would show instead. BASE_URL is read at render time (basePath.routerBasename).
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('../areas/auth/SignIn', () => ({
  SignIn: () => <p>sign-in page</p>,
  AuthCallback: () => <p>auth callback</p>,
}));
vi.mock('../auth/AuthProvider', async (orig) => {
  const real = await orig<typeof import('../auth/AuthProvider')>();
  return { ...real, useAuth: () => ({ status: 'unauthenticated' }) };
});

import { App } from './App';

beforeEach(() => vi.stubEnv('BASE_URL', '/sangam/'));
afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  window.history.replaceState(null, '', '/');
});

it('routes /sangam/auth/callback to the callback screen', async () => {
  window.history.replaceState(null, '', '/sangam/auth/callback');
  render(<App />);
  expect(await screen.findByText('auth callback')).toBeTruthy();
  expect(screen.queryByText('sign-in page')).toBeNull();
});
