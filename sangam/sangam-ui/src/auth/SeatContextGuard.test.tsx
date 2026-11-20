// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Register E-24 follow-up 1: the server refuses a seat the person does not hold now with 403 SEAT_NOT_HELD (K7). The
 * UI sends the chosen seat on every call (AuthProvider), so a posting that ends during a session, or a seat kept in
 * sessionStorage from before, is refused on the next call. The user then gets a plain message, the stored seat is
 * dropped, GET /me is read again and a seat it offers is used; nobody is signed out.
 *
 * The real AuthProvider and the real client run against a stubbed fetch that answers as the server does: GET /me
 * lists the seats held now, and every call that names a seat not held is 403 SEAT_NOT_HELD (also GET /me itself).
 * Before the fix the UI had no handling: the refused call failed, the seat stayed in sessionStorage and was sent
 * again, and a page reload ended on the error screen of the sign-in page until the user signed out.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ApiError, raw } from '../api/client';
import { initI18n } from '../i18n';
import { AuthProvider, useAuth } from './AuthProvider';
import { SeatContextGuard } from './SeatContextGuard';

const SEATS = {
  s1: { seatId: 's1', seatName: 'Section officer', designation: 'SO', orgUnitName: 'Unit', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' },
  s2: { seatId: 's2', seatName: 'Under secretary', designation: 'US', orgUnitName: 'Unit', kind: 'ADDITIONAL_CHARGE', validFrom: '2026-01-01' },
  s3: { seatId: 's3', seatName: 'Desk officer', designation: 'DO', orgUnitName: 'Unit', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' },
};

/** The seats the person holds now, as the server knows them. */
let held: Array<keyof typeof SEATS> = [];
/** Every request the UI sent: path, X-Sangam-Seat, X-Sangam-Acting-For-Seat. */
let sent: Array<[string, string | null, string | null]> = [];
/** The status and code a call that names no refused seat gets on /probe (default 200). */
let probeAnswer: { status: number; code?: string } = { status: 200 };

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
  const actingFor = h.get('X-Sangam-Acting-For-Seat');
  sent.push([path, seat, actingFor]);
  for (const named of [seat, actingFor]) {
    if (named && !held.includes(named as keyof typeof SEATS)) {
      return json(403, { title: 'Seat not held', status: 403, code: 'SEAT_NOT_HELD', detail: `You do not hold seat '${named}' now` });
    }
  }
  if (path === '/me') {
    return json(200, { userId: 'u1', displayName: 'User One', tenantId: 't1', roles: ['TASK_USER'], seats: held.map((s) => SEATS[s]) });
  }
  if (probeAnswer.status !== 200) {
    return json(probeAnswer.status, { title: 'Refused', status: probeAnswer.status, code: probeAnswer.code });
  }
  return json(200, {});
}

/** Test-only view of the auth state (no product markup). */
function Probe() {
  const a = useAuth();
  return (
    <div>
      <output data-testid="status">{a.status}</output>
      <output data-testid="seat">{a.seat?.seatId ?? ''}</output>
      <output data-testid="offered">{(a.me?.seats ?? []).map((s) => s.seatId).join(',')}</output>
      <button type="button" onClick={() => a.setSeat('s2')}>seat2</button>
    </div>
  );
}

const text = (id: string) => screen.getByTestId(id).textContent;
const app = () => render(<AuthProvider><SeatContextGuard><Probe /></SeatContextGuard></AuthProvider>);
const meCalls = () => sent.filter(([p]) => p === '/me');

beforeAll(() => initI18n('en'));
beforeEach(() => {
  window.sessionStorage.clear();
  window.sessionStorage.setItem('sangam.devToken', 'tok-1');
  held = ['s1', 's2'];
  sent = [];
  probeAnswer = { status: 200 };
  vi.stubGlobal('fetch', vi.fn(server));
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('E-24: a seat the server no longer accepts (403 SEAT_NOT_HELD)', () => {
  it('a posting that ended during the session: plain message, seat dropped, /me read again, no sign-out', async () => {
    app();
    await waitFor(() => expect(text('seat')).toBe('s1'));
    held = ['s3'];   // relieved from s1 and from the charge s2; posted to s3
    const before = meCalls().length;
    await expect(raw('GET', '/probe')).rejects.toBeInstanceOf(ApiError);
    await waitFor(() => expect(text('offered')).toBe('s3'));
    expect(text('status')).toBe('authenticated');
    expect(window.sessionStorage.getItem('sangam.devToken')).toBe('tok-1');
    expect(screen.getByRole('status').textContent).toContain('Section officer');
    expect(screen.getByRole('status').textContent).toContain('no longer hold');
    // /me was read once more, without the refused seat
    expect(meCalls().slice(before)).toEqual([['/me', null, null]]);
    // the seat /me offers now is the one in use; the refused one is gone from the session
    await waitFor(() => expect(text('seat')).toBe('s3'));
    expect(window.sessionStorage.getItem('sangam.seat')).toBe('s3');
    sent = [];
    await raw('GET', '/probe');
    expect(sent).toEqual([['/probe', 's3', null]]);
  });

  it('an acted-for seat whose charge ended is dropped the same way, the own seat stays', async () => {
    app();
    await waitFor(() => expect(text('seat')).toBe('s1'));
    fireEvent.click(screen.getByRole('button', { name: 'seat2' }));
    await waitFor(() => expect(text('seat')).toBe('s2'));
    held = ['s1'];
    await expect(raw('GET', '/probe')).rejects.toMatchObject({ status: 403 });
    await waitFor(() => expect(text('seat')).toBe('s1'));
    expect(text('offered')).toBe('s1');
    expect(screen.getByRole('status').textContent).toContain('Under secretary');
    sent = [];
    await raw('GET', '/probe');
    expect(sent).toEqual([['/probe', 's1', null]]);
  });

  it('a seat kept in the session from before: the page loads, no error screen, the offered seat is used', async () => {
    window.sessionStorage.setItem('sangam.seat', 's9');
    app();
    await waitFor(() => expect(text('status')).toBe('authenticated'));
    await waitFor(() => expect(text('seat')).toBe('s1'));
    expect(meCalls()).toEqual([['/me', 's9', null], ['/me', null, null]]);
    expect(window.sessionStorage.getItem('sangam.seat')).toBe('s1');
    expect(screen.getByRole('status').textContent).toContain('s9');
  });

  it('several calls refused at the same moment read /me again once', async () => {
    app();
    await waitFor(() => expect(text('seat')).toBe('s1'));
    held = ['s3'];
    const before = meCalls().length;
    await Promise.allSettled([raw('GET', '/probe'), raw('GET', '/probe'), raw('GET', '/probe')]);
    await waitFor(() => expect(text('seat')).toBe('s3'));
    expect(meCalls().length - before).toBe(1);
  });

  it('the message can be closed', async () => {
    app();
    await waitFor(() => expect(text('seat')).toBe('s1'));
    held = ['s3'];
    await expect(raw('GET', '/probe')).rejects.toBeInstanceOf(ApiError);
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('any other refusal changes nothing: the seat stays and no message is shown', async () => {
    app();
    await waitFor(() => expect(text('seat')).toBe('s1'));
    probeAnswer = { status: 403, code: 'ACTION_NOT_PERMITTED' };
    const before = meCalls().length;
    await expect(raw('GET', '/probe')).rejects.toMatchObject({ status: 403 });
    expect(text('seat')).toBe('s1');
    expect(window.sessionStorage.getItem('sangam.seat')).toBe('s1');
    expect(meCalls().length).toBe(before);
    expect(screen.queryByRole('status')).toBeNull();
  });
});
