// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Register E-29(b) and E-32(d): the Teams tab after its pop-up sign-in.
 *
 * The real AuthProvider, the real client and the real tab run against a stubbed fetch that answers as the server
 * does; the pop-up (Teams SDK authenticate) is replaced by its result, a signed-in user as text; the two task screens
 * are replaced by a marker.
 *
 * Fail-first:
 * - the identity provider signed the user in, but GET /me answered 401 to the new token: the tab went back to its
 *   sign-in with no word (in the console the sign-in screen says so since E-23);
 * - a seat the server no longer accepts (403 SEAT_NOT_HELD): the tab had no handling, the refused seat was sent
 *   again on every call (the console has SeatContextGuard since E-24).
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { User } from 'oidc-client-ts';

vi.mock('../areas/tasklist/TaskInbox', () => ({ TaskInbox: () => <output data-testid="inbox">inbox</output> }));
vi.mock('../areas/tasklist/TaskDetail', () => ({ TaskDetail: () => null }));

import { ApiError, raw } from '../api/client';
import { setConfig } from '../config';
import { initI18n, tr } from '../i18n';
import { AuthProvider, createUserManager } from '../auth/AuthProvider';
import { TeamsTab } from './TeamsApp';

const SEATS = {
  s1: { seatId: 's1', seatName: 'Section officer', designation: 'SO', orgUnitName: 'Unit', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' },
  s3: { seatId: 's3', seatName: 'Desk officer', designation: 'DO', orgUnitName: 'Unit', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' },
};
/** The seats the person holds now, as the server knows them. */
let held: Array<keyof typeof SEATS> = [];
/** What GET /me answers to the token: 200, or 401 when the server does not accept it. */
let meStatus = 200;
let sent: Array<[string, string | null]> = [];

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

async function server(url: string, init: RequestInit): Promise<Response> {
  const h = init.headers as Headers;
  const path = new URL(url, 'http://localhost').pathname.replace(/^.*\/api\/v1/, '');
  const seat = h.get('X-Sangam-Seat');
  sent.push([path, seat]);
  if (path === '/me' && meStatus === 401) {
    return json(401, { title: 'Unauthenticated', status: 401, code: 'UNAUTHENTICATED' });
  }
  if (seat && !held.includes(seat as keyof typeof SEATS)) {
    return json(403, { title: 'Seat not held', status: 403, code: 'SEAT_NOT_HELD', detail: `You do not hold seat '${seat}' now` });
  }
  if (path === '/me') {
    return json(200, { userId: 'u1', displayName: 'User One', tenantId: 't1', roles: ['TASK_USER'], seats: held.map((s) => SEATS[s]) });
  }
  return json(200, {});
}

/** What the pop-up hands back through the Teams SDK: the signed-in user as text. */
const popUpResult = () =>
  new User({
    access_token: 'at-1',
    token_type: 'Bearer',
    profile: { sub: 'u1', iss: 'https://idp.example/realms/sangam', aud: 'sangam-ui', exp: 0, iat: 0 },
    expires_at: Math.floor(Date.now() / 1000) + 600,
  }).toStorageString();

function openTab() {
  const mgr = createUserManager('https://h/teams.html');
  render(
    <AuthProvider providerSignOut={false}>
      <TeamsTab authenticate={async () => popUpResult()} userManager={mgr} origin="https://h" base="/" />
    </AuthProvider>,
  );
}

async function signIn() {
  const button = await screen.findByRole('button', { name: tr('teams.signIn') });
  fireEvent.click(button);
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
  setConfig({ authMode: 'oidc', environment: 'test', oidc: { issuer: 'https://idp.example/realms/sangam', clientId: 'sangam-ui' } });
  held = ['s1'];
  meStatus = 200;
  sent = [];
  vi.stubGlobal('fetch', vi.fn(server));
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  setConfig({});
});

describe('Teams tab after the pop-up sign-in', () => {
  it('a sign-in the server accepts shows the work of the tab', async () => {
    openTab();
    await signIn();
    await waitFor(() => expect(screen.getByTestId('inbox')).toBeTruthy());
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('E-29(b): GET /me answers 401 to the new token: the tab says the sign-in was not accepted', async () => {
    meStatus = 401;
    openTab();
    await signIn();
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(tr('auth.signInRefused')));
    // still the sign-in of the tab, and the user can try again
    expect(screen.getByRole('button', { name: tr('teams.signIn') })).toBeTruthy();
    expect(screen.queryByTestId('inbox')).toBeNull();
  });

  it('E-32(d): a seat the server no longer accepts: plain message, seat dropped, /me read again, still signed in', async () => {
    openTab();
    await signIn();
    await waitFor(() => expect(screen.getByTestId('inbox')).toBeTruthy());
    await waitFor(() => expect(window.sessionStorage.getItem('sangam.seat')).toBe('s1'));
    held = ['s3'];   // relieved from s1, posted to s3
    await expect(raw('GET', '/probe')).rejects.toBeInstanceOf(ApiError);
    await waitFor(() => expect(screen.getByText(/no longer hold/).textContent).toContain('Section officer'));
    await waitFor(() => expect(window.sessionStorage.getItem('sangam.seat')).toBe('s3'));
    expect(screen.getByTestId('inbox')).toBeTruthy();
    sent = [];
    await raw('GET', '/probe');
    expect(sent).toEqual([['/probe', 's3']]);
  });
});
