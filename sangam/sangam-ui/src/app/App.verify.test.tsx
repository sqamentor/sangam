// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Q7 part 3 (owner, 2026-11-21 "Yes, inside the frame"): Help > "Verify a document" opens inside the normal page frame
 * for a signed-in caller. The public form (no sign-in, a QR code on a certificate opens it) stays exactly as built:
 * its markup is pinned by a snapshot taken before the change. No role's access changes: the route has no guard.
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';
import type { Role } from '../api/types';
import { setConfig } from '../config';
import { initI18n } from '../i18n';

const auth = vi.hoisted(() => ({ status: 'authenticated', roles: [] as string[], permissions: [] as string[] }));

vi.mock('../auth/AuthProvider', async (orig) => {
  const real = await orig<typeof import('../auth/AuthProvider')>();
  return {
    ...real,
    useAuth: () => ({
      status: auth.status,
      me: auth.status === 'authenticated' ? { userId: 'p1', displayName: 'P', tenantId: 't', roles: auth.roles, seats: [], permissions: auth.permissions } : null,
      seat: null,
      hasRole: (...r: Role[]) => r.some((x) => auth.roles.includes(x)),
      can: () => true,
      logout: () => undefined,
    }),
  };
});
vi.mock('../auth/IdleSession', () => ({ IdleSession: () => null }));

import { App } from './App';

beforeAll(() => {
  initI18n('en');
  // the public page was pinned with both languages offered (the UI default when the snapshot was taken)
  setConfig({ locales: 'en,hi' });
});
afterAll(() => void setConfig({}));

const at = (path: string) => window.history.replaceState(null, '', path);
const field = (label: string) => {
  const l = [...document.querySelectorAll('label')].find((x) => x.textContent?.startsWith(label));
  return l ? (document.getElementById(l.htmlFor) as HTMLInputElement | null) : null;
};

afterEach(() => {
  cleanup();
  at('/');
});

describe('the public form, for a caller who is not signed in', () => {
  const seen: string[] = [];
  // 'anonymous' and 'error' are the states AuthProvider sets for a caller who is not signed in; 'unauthenticated' is the
  // name under which the page was pinned before the change (any state other than loading and authenticated)
  it.each(['unauthenticated', 'anonymous', 'error'])('status %s: the form as built, without the page frame', async (status) => {
    auth.status = status;
    at('/verify');
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('main.signin .signin-card h1#page-title')?.textContent).toBe('Verify a document'));
    expect(document.querySelectorAll('main')).toHaveLength(1);
    expect(document.querySelector('nav')).toBeNull();
    expect(document.querySelector('footer')).toBeNull();
    // exactly as built: the markup of the public page, written before Q7 part 3 touched it
    // (React's generated field ids are left out: they count the fields rendered before, not the page)
    const html = container.innerHTML.replace(/_r_\w+_/g, 'ID');
    seen.push(html);
    expect(html).toBe(seen[0]);
    if (status === 'unauthenticated') expect(html).toMatchSnapshot('public verify page');
  }, 30_000);

  // Follow-up 2 (5): while the sign-in state is being read (a stored session: GET /me is on its way) neither form is
  // shown, so a signed-in caller who reloads /verify does not see the public page first. A caller with no stored
  // session is 'anonymous' at once (AuthProvider reads the browser's own store, no identity provider call).
  it('status loading: neither the public form nor the frame, only the loading notice', async () => {
    auth.status = 'loading';
    at('/verify');
    const { container } = render(<App />);
    await new Promise((r) => setTimeout(r, 500));
    expect(container.querySelector('main.signin')).toBeNull();
    expect(container.querySelector('form')).toBeNull();
    expect(document.querySelector('nav')).toBeNull();
    expect(container.querySelector('.loading[aria-busy="true"]')).toBeTruthy();
  }, 30_000);

  it('a QR address fills the issuer, the code and the kind', async () => {
    auth.status = 'unauthenticated';
    at('/verify/GOI.FIN?code=ABC-123&kind=extract');
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('main.signin')).toBeTruthy());
    expect(field('Issuer')?.value).toBe('GOI.FIN');
    expect(field('Verification code')?.value).toBe('ABC-123');
    expect((document.querySelectorAll('input[type=radio]')[1] as HTMLInputElement).checked).toBe(true);
  }, 30_000);
});

describe('a signed-in caller', () => {
  // GET /me of the groups sangam-user and sangam-worker (serverGroups.test.ts pins the worker; the user's roles are
  // TASK_USER): the page is open to every signed-in caller, whatever the group
  it.each([
    ['sangam-user', ['TASK_USER'], ['task.read', 'task.write', 'form.read']],
    ['sangam-worker', [], ['document.read', 'document.write', 'event.publish', 'message.send', 'work.execute']],
  ])('%s gets the form inside the page frame', async (_group, roles, permissions) => {
    auth.status = 'authenticated';
    auth.roles = roles;
    auth.permissions = permissions;
    at('/verify');
    render(<App />);
    await waitFor(() => expect(document.querySelector('main#main h1#page-title')?.textContent).toBe('Verify a document'));
    // the frame: one main landmark, the menu beside it, the footer; not the sign-in card layout
    expect(document.querySelectorAll('main')).toHaveLength(1);
    expect(document.querySelectorAll('#main')).toHaveLength(1);
    expect(document.querySelectorAll('#page-title')).toHaveLength(1);
    expect(document.querySelector('main.signin')).toBeNull();
    expect(document.querySelector('nav a[href="/verify"]')).toBeTruthy();
    expect(document.querySelector('footer')).toBeTruthy();
    // the same form
    expect(field('Issuer')).toBeTruthy();
    expect(field('Verification code')).toBeTruthy();
    expect(document.querySelectorAll('input[type=radio]')).toHaveLength(2);
    expect([...document.querySelectorAll('main button[type=submit]')].map((b) => b.textContent)).toEqual(['Verify']);
    expect(document.querySelector('main')?.textContent).toContain('Only the issuer and code you enter are sent for this check.');
  }, 30_000);

  it('a QR address fills the form inside the frame too', async () => {
    auth.status = 'authenticated';
    auth.roles = ['TASK_USER'];
    at('/verify/GOI.FIN?code=ABC-123&kind=extract');
    render(<App />);
    await waitFor(() => expect(document.querySelector('main#main h1#page-title')?.textContent).toBe('Verify a document'));
    expect(document.querySelector('main.signin')).toBeNull();
    expect(field('Issuer')?.value).toBe('GOI.FIN');
    expect(field('Verification code')?.value).toBe('ABC-123');
    expect((document.querySelectorAll('input[type=radio]')[1] as HTMLInputElement).checked).toBe(true);
  }, 30_000);
});
