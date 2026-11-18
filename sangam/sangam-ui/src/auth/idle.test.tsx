// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 349 (CERT-In / DPDP matrix W-09): the idle period comes from the runtime configuration (/config.json
 * session.idleMinutes; default 15, a bad value falls back to it), a warning with "Stay signed in" comes first, and on
 * idle the session ends: the token and the seat leave sessionStorage, the user is signed out and the sign-in screen
 * says why. Background requests never extend the session; any input does.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get } };
});

import { initI18n, tr } from '../i18n';
import { IDLE_MINUTES, idleMinutes, normalise, setConfig } from '../config';
import { AuthProvider, useAuth } from './AuthProvider';
import { IdleSession } from './IdleSession';
import { idleState, warningMs } from './idle';
import { SignIn } from '../areas/auth/SignIn';

const T = tr;
const ME = { userId: 'u1', displayName: 'User One', tenantId: 't1', roles: ['TASK_USER'], seats: [{ seatId: 's1', seatName: 'SO', designation: 'SO', orgUnitName: 'U', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' }] };

function Shell() {
  const { status } = useAuth();
  if (status !== 'authenticated') return <SignIn />;
  return (
    <>
      <IdleSession />
      <output data-testid="signed-in">yes</output>
    </>
  );
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  window.sessionStorage.clear();
  api.get.mockReset();
  api.get.mockImplementation(async (path: string) => (path === '/me' ? ME : []));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  setConfig({});
});

describe('decision 349 idle period from the runtime configuration', () => {
  it('defaults to 15 minutes; a whole number of minutes from 1 to 480 is taken; anything else falls back', () => {
    expect(IDLE_MINUTES.DEFAULT).toBe(15);
    expect(normalise({}).session.idleMinutes).toBe(15);
    expect(normalise({ session: { idleMinutes: 30 } }).session.idleMinutes).toBe(30);
    // the nginx template renders numbers; a string from another deployment tool is read too
    expect(normalise({ session: { idleMinutes: ' 10 ' } }).session.idleMinutes).toBe(10);
    for (const bad of [0, -5, 481, 2.5, NaN, '', 'off', '15m', null, true, {}]) expect(idleMinutes(bad), String(bad)).toBe(15);
    expect(idleMinutes(1)).toBe(1);
    expect(idleMinutes(480)).toBe(480);
  });
});

describe('decision 349 idle state', () => {
  const idle = 15 * 60_000;
  it('warns one minute before the end (half the period when shorter) and is idle at the end', () => {
    expect(warningMs(idle)).toBe(60_000);
    expect(warningMs(60_000)).toBe(30_000);
    expect(idleState(0, 0, idle)).toEqual({ phase: 'active', remainingMs: idle });
    expect(idleState(0, idle - 60_001, idle).phase).toBe('active');
    expect(idleState(0, idle - 60_000, idle).phase).toBe('warning');
    expect(idleState(0, idle - 1, idle)).toEqual({ phase: 'warning', remainingMs: 1 });
    expect(idleState(0, idle, idle)).toEqual({ phase: 'idle', remainingMs: 0 });
    // a tab that slept far past the end is idle at once, not after a countdown
    expect(idleState(0, 10 * idle, idle).phase).toBe('idle');
  });
});

describe('decision 349 idle sign-out (dev-token mode)', () => {
  async function signIn() {
    setConfig({ session: { idleMinutes: 1 } });
    window.sessionStorage.setItem('sangam.devToken', 'tok-1');
    // fake timers that also follow real time, so the sign-in promises and waitFor still run
    vi.useFakeTimers({ shouldAdvanceTime: true, now: Date.now() });
    render(<AuthProvider><Shell /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('signed-in')).toBeTruthy());
    await waitFor(() => expect(window.sessionStorage.getItem('sangam.seat')).toBe('s1'));
    // input once, so the period starts now, after the sign-in
    fireEvent.keyDown(window, { key: 'Shift' });
  }
  const advance = (ms: number) => act(() => void vi.advanceTimersByTime(ms));

  it('warns, then ends the session: no token, no seat, signed out, and the sign-in screen says why', async () => {
    await signIn();
    advance(29_000);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    advance(2_000);
    expect(screen.getByRole('alertdialog', { name: T('auth.idleWarningTitle') })).toBeTruthy();
    expect(screen.getByText(new RegExp(T('auth.idleWarning', { seconds: '(29|30)' })))).toBeTruthy();
    advance(30_000);
    expect(screen.queryByTestId('signed-in')).toBeNull();
    expect(screen.getByRole('heading', { name: T('auth.title') })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe(T('auth.idleSignedOut', { minutes: 1 }));
    expect(window.sessionStorage.getItem('sangam.devToken')).toBeNull();
    expect(window.sessionStorage.getItem('sangam.seat')).toBeNull();
    expect(window.sessionStorage.getItem('sangam.signedOut')).toBe('idle');
  });

  it('"Stay signed in" and any input restart the period; background requests do not', async () => {
    await signIn();
    advance(45_000);
    fireEvent.click(screen.getByRole('button', { name: T('auth.idleStay') }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    advance(45_000);
    expect(screen.getByTestId('signed-in')).toBeTruthy();
    fireEvent.pointerMove(window);
    advance(45_000);
    expect(screen.getByTestId('signed-in')).toBeTruthy();
    // polling: GET calls while nobody touches anything do not count
    for (let i = 0; i < 3; i++) await act(async () => void (await api.get('/tasks/counts')));
    advance(16_000);
    expect(screen.queryByTestId('signed-in')).toBeNull();
  });

  it('a tab that wakes up after the period ends the session at once', async () => {
    await signIn();
    vi.setSystemTime(Date.now() + 5 * 60_000); // the laptop slept; no timer ran meanwhile
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    expect(screen.queryByTestId('signed-in')).toBeNull();
  });

  it('input after the period ends the session instead of extending it', async () => {
    await signIn();
    vi.setSystemTime(Date.now() + 2 * 60_000);
    act(() => void fireEvent.keyDown(window, { key: 'a' }));
    expect(screen.queryByTestId('signed-in')).toBeNull();
  });

  it('signing in again clears the reason', async () => {
    await signIn();
    advance(61_000);
    expect(screen.getByRole('status')).toBeTruthy();
    vi.useRealTimers();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: T('auth.signIn') }));
    });
    await waitFor(() => expect(screen.getByTestId('signed-in')).toBeTruthy());
    expect(window.sessionStorage.getItem('sangam.signedOut')).toBeNull();
  });
});
