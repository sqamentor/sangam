// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 (component tests, finish, W3-J): AuthProvider in dev-token mode (the default runtime config, config.ts:51-67).
 * Sign-in stores the token in sessionStorage only and loads GET /me; the first substantive seat is selected; every
 * call then carries Authorization: Bearer and X-Sangam-Seat, and a seat held on additional charge travels as
 * X-Sangam-Acting-For-Seat with the own seat as X-Sangam-Seat (AuthProvider.tsx:102-112, IX.5 rule 4). Roles come
 * from /me, else from the token claims (:56-63, :126-127). 401 on /me is "signed out", any other failure is an error
 * (:130-137). Only GET /me is mocked; the real client builds the headers (client.ts:110-120) against a stubbed fetch.
 * The OIDC code path needs a live identity provider and is covered by the E2E suite, not here. Written, not run.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLayoutEffect } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get } };
});

import { ApiError, raw } from '../api/client';
import { AuthProvider, useAuth } from './AuthProvider';

const ME = {
  userId: 'u1',
  displayName: 'User One',
  tenantId: 't1',
  roles: ['TASK_USER'],
  seats: [
    { seatId: 's1', seatName: 'Section officer', designation: 'SO', orgUnitName: 'Unit', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' },
    { seatId: 's2', seatName: 'Under secretary', designation: 'US', orgUnitName: 'Unit', kind: 'ADDITIONAL_CHARGE', validFrom: '2026-01-01' },
  ],
};

function jwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64(claims)}.sig`;
}

/** Test-only view of the auth state (no product markup). */
function Probe() {
  const a = useAuth();
  return (
    <div>
      <output data-testid="status">{a.status}</output>
      <output data-testid="user">{a.me?.userId ?? ''}</output>
      <output data-testid="seat">{a.seat?.seatId ?? ''}</output>
      <output data-testid="admin">{String(a.hasRole('ADMIN'))}</output>
      <button type="button" onClick={() => void a.login('tok-1')}>login</button>
      <button type="button" onClick={() => void a.logout()}>logout</button>
      <button type="button" onClick={() => a.setSeat('s2')}>seat2</button>
    </div>
  );
}

const text = (id: string) => screen.getByTestId(id).textContent;
const fetchMock = vi.fn();

async function headersOfNextCall(): Promise<Headers> {
  fetchMock.mockClear();
  await raw('GET', '/probe');
  return (fetchMock.mock.calls[0][1] as RequestInit).headers as Headers;
}

beforeEach(() => {
  window.sessionStorage.clear();
  api.get.mockReset();
  api.get.mockImplementation(async (path: string) => (path === '/me' ? ME : []));
  fetchMock.mockReset();
  fetchMock.mockResolvedValue({ ok: true, status: 200, headers: new Headers(), json: async () => ({}) });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('M-047 AuthProvider (dev-token mode)', () => {
  it('without a stored token the user is signed out and /me is not called', async () => {
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(text('status')).toBe('anonymous'));
    expect(api.get).not.toHaveBeenCalled();
  });

  it('sign-in stores the token in sessionStorage, loads /me and selects the first substantive seat', async () => {
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(text('status')).toBe('anonymous'));
    fireEvent.click(screen.getByRole('button', { name: 'login' }));
    await waitFor(() => expect(text('status')).toBe('authenticated'));
    expect(window.sessionStorage.getItem('sangam.devToken')).toBe('tok-1');
    expect(api.get).toHaveBeenCalledWith('/me');
    expect(text('user')).toBe('u1');
    await waitFor(() => expect(text('seat')).toBe('s1'));
    expect(window.sessionStorage.getItem('sangam.seat')).toBe('s1');
  });

  it('every call carries the bearer token and the seat; an additional charge is sent as the acting-for seat', async () => {
    window.sessionStorage.setItem('sangam.devToken', 'tok-1');
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(text('seat')).toBe('s1'));
    let h = await headersOfNextCall();
    expect(h.get('Authorization')).toBe('Bearer tok-1');
    expect(h.get('X-Sangam-Seat')).toBe('s1');
    expect(h.get('X-Sangam-Acting-For-Seat')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'seat2' }));
    await waitFor(() => expect(text('seat')).toBe('s2'));
    h = await headersOfNextCall();
    expect(h.get('X-Sangam-Seat')).toBe('s1');
    expect(h.get('X-Sangam-Acting-For-Seat')).toBe('s2');
  });

  it('a call fired in the commit that shows the seat already carries that seat (no passive-effect race)', async () => {
    // The commit that shows the seat is observed in a layout effect, i.e. before React runs that commit's passive
    // effects (scheduled as a later task outside act). That is the window run 44489607242 hit at random: its waitFor saw
    // seat s1 in the DOM and fired the call before the seat headers were registered. Here the window is hit every time.
    const sent: Array<[string, string | null, string | null]> = [];
    function LoadOnSeat() {
      const { seat } = useAuth();
      useLayoutEffect(() => {
        if (!seat) return;
        void raw('GET', '/probe');
      }, [seat]);
      return null;
    }
    fetchMock.mockImplementation(async (_url: string, init: RequestInit) => {
      const h = init.headers as Headers;
      sent.push([h.get('Authorization') ?? '', h.get('X-Sangam-Seat'), h.get('X-Sangam-Acting-For-Seat')]);
      return { ok: true, status: 200, headers: new Headers(), json: async () => ({}) };
    });
    window.sessionStorage.setItem('sangam.devToken', 'tok-1');
    render(<AuthProvider><Probe /><LoadOnSeat /></AuthProvider>);
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]).toEqual(['Bearer tok-1', 's1', null]);
    fireEvent.click(screen.getByRole('button', { name: 'seat2' }));
    await waitFor(() => expect(sent.length).toBe(2));
    expect(sent[1]).toEqual(['Bearer tok-1', 's1', 's2']);
  });

  it('roles come from the token claims when /me has none', async () => {
    api.get.mockImplementation(async () => ({ ...ME, roles: undefined }));
    window.sessionStorage.setItem('sangam.devToken', jwt({ realm_access: { roles: ['admin'] } }));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(text('status')).toBe('authenticated'));
    expect(text('admin')).toBe('true');
  });

  it('401 on /me means signed out; another failure is an error', async () => {
    window.sessionStorage.setItem('sangam.devToken', 'tok-1');
    api.get.mockRejectedValue(new ApiError({ status: 401, title: 'Unauthorized' }));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(text('status')).toBe('anonymous'));
    cleanup();
    api.get.mockRejectedValue(new ApiError({ status: 500, title: 'Server error' }));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(text('status')).toBe('error'));
  });

  it('sign-out removes the token and the seat and drops the seat header', async () => {
    window.sessionStorage.setItem('sangam.devToken', 'tok-1');
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(text('seat')).toBe('s1'));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'logout' }));
    });
    await waitFor(() => expect(text('status')).toBe('anonymous'));
    expect(window.sessionStorage.getItem('sangam.devToken')).toBeNull();
    expect(window.sessionStorage.getItem('sangam.seat')).toBeNull();
    const h = await headersOfNextCall();
    expect(h.get('Authorization')).toBeNull();
    expect(h.get('X-Sangam-Seat')).toBeNull();
  });
});
