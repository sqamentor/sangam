// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-021 / C3-13 (W3-J2): the test-mode clock panel on Engine administration. It reads GET /test-mode/clock and sends
 * exactly one of advanceBy, set or reset (plus an optional reason) to POST /test-mode/clock, the contract TestMode
 * shapes (sangam-api.yaml TestClock, TestClockChange). A 404 means test mode is off: the panel says so and offers no
 * change. Written, not run (tests run after Wave 5).
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), request: vi.fn() }));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page }, request: api.request };
});
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: 'admin', roles: ['PLATFORM_ADMIN'], seats: [] }, seat: null, mode: 'dev', hasRole: () => true }),
}));
vi.mock('../plugins/host', () => ({ PluginSlot: () => null, usePlugins: () => [] }));

import { ApiError } from '../api/client';
import { initI18n } from '../i18n';
import { TestClockPanel, clockChange } from './testClock';
import { EngineAdminPage } from './operaton';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** A field label, optionally followed by the required marker. */
const L = (k: string) => new RegExp(`^${esc(T(k))}(\\s*\\*.*)?$`);
const CLOCK = { now: '2026-11-12T10:00:00Z', offsetMs: 0 };
const KEY = expect.any(String);

beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const f of Object.values(api)) f.mockReset();
  api.get.mockImplementation(async (path: string) => (path === '/test-mode/clock' ? CLOCK : []));
  api.page.mockResolvedValue({ items: [], page: 0, size: 25, total: 0, capped: false });
  api.request.mockResolvedValue([]);
});
afterEach(cleanup);

function show(ui = <TestClockPanel />) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}
const applyButton = () => screen.getByRole('button', { name: T('mod.tclock.apply') }) as HTMLButtonElement;

describe('clockChange: exactly one contract action', () => {
  it('advance takes an ISO-8601 duration, upper-cased, with the reason when given', () => {
    expect(clockChange('advance', 'p3d', '', '  ')).toEqual({ advanceBy: 'P3D' });
    expect(clockChange('advance', 'PT2H', '', 'SLA test')).toEqual({ advanceBy: 'PT2H', reason: 'SLA test' });
    expect(clockChange('advance', 'P1DT30M', '', '')).toEqual({ advanceBy: 'P1DT30M' });
  });
  it('advance refuses what is not a duration', () => {
    for (const bad of ['', 'P', 'PT', '3 days', 'P3', 'T2H', 'P1H']) expect(clockChange('advance', bad, '', '')).toBeUndefined();
  });
  it('set sends the instant the local time names; reset sends reset only', () => {
    expect(clockChange('set', '', '2026-12-01T10:00', '')).toEqual({ set: new Date('2026-12-01T10:00').toISOString() });
    expect(clockChange('set', '', '', '')).toBeUndefined();
    expect(clockChange('reset', 'P3D', '2026-12-01T10:00', 'done')).toEqual({ reset: true, reason: 'done' });
  });
});

describe('TestClockPanel', () => {
  it('test mode off (404): says so and offers no change', async () => {
    api.get.mockRejectedValue(new ApiError({ status: 404, title: 'Not Found', code: 'NOT_FOUND' }));
    show();
    expect(await screen.findByText(T('mod.tclock.off'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: T('mod.tclock.apply') })).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows the clock and its offset', async () => {
    api.get.mockResolvedValue({ now: '2026-11-15T10:00:00Z', offsetMs: 259200000 });
    show();
    expect(await screen.findByText(T('mod.tclock.offsetMs', { n: (259200000).toLocaleString('en-US') }))).toBeTruthy();
  });

  it('advances by a duration with a reason, then shows the answer', async () => {
    api.post.mockResolvedValue({ now: '2026-11-15T10:00:00Z', offsetMs: 259200000, action: 'ADVANCE' });
    show();
    await screen.findByText(T('mod.tclock.offsetMs', { n: '0' }));
    expect(applyButton().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(L('mod.tclock.advanceBy')), { target: { value: 'p3d' } });
    fireEvent.change(screen.getByLabelText(L('mod.tclock.reason')), { target: { value: 'SLA breach test' } });
    fireEvent.click(applyButton());
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/test-mode/clock', { advanceBy: 'P3D', reason: 'SLA breach test' }, KEY));
    expect(await screen.findByText(T('mod.tclock.offsetMs', { n: (259200000).toLocaleString('en-US') }))).toBeTruthy();
  });

  it('an invalid duration is refused on the screen, before any call', async () => {
    show();
    await screen.findByText(T('mod.tclock.offsetMs', { n: '0' }));
    fireEvent.change(screen.getByLabelText(L('mod.tclock.advanceBy')), { target: { value: 'three days' } });
    expect(screen.getByText(T('mod.tclock.advanceByInvalid'))).toBeTruthy();
    expect(applyButton().disabled).toBe(true);
    expect(api.post).not.toHaveBeenCalled();
  });

  it('reset sends reset: true only', async () => {
    api.post.mockResolvedValue({ ...CLOCK, action: 'RESET' });
    show();
    await screen.findByText(T('mod.tclock.offsetMs', { n: '0' }));
    fireEvent.change(screen.getByLabelText(L('mod.tclock.action')), { target: { value: 'reset' } });
    fireEvent.click(applyButton());
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/test-mode/clock', { reset: true }, KEY));
  });

  it('set sends the chosen time as a UTC instant', async () => {
    api.post.mockResolvedValue({ now: '2026-12-01T10:00:00Z', offsetMs: 1, action: 'SET' });
    show();
    await screen.findByText(T('mod.tclock.offsetMs', { n: '0' }));
    fireEvent.change(screen.getByLabelText(L('mod.tclock.action')), { target: { value: 'set' } });
    fireEvent.change(screen.getByLabelText(L('mod.tclock.setAt')), { target: { value: '2026-12-01T10:00' } });
    fireEvent.click(applyButton());
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/test-mode/clock', { set: new Date('2026-12-01T10:00').toISOString() }, KEY));
  });

  it('a server refusal (400) is shown and the clock stays as read', async () => {
    api.post.mockRejectedValue(new ApiError({ status: 400, title: 'Validation failed', code: 'VALIDATION_FAILED' }));
    show();
    await screen.findByText(T('mod.tclock.offsetMs', { n: '0' }));
    fireEvent.change(screen.getByLabelText(L('mod.tclock.advanceBy')), { target: { value: 'PT0S' } });
    fireEvent.click(applyButton());
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText(T('mod.tclock.offsetMs', { n: '0' }))).toBeTruthy();
  });
});

describe('Engine administration', () => {
  it('has a Test clock tab that reads GET /test-mode/clock only when opened', async () => {
    show(<EngineAdminPage />);
    expect(api.get).not.toHaveBeenCalledWith('/test-mode/clock');
    fireEvent.click(await screen.findByRole('tab', { name: T('mod.tclock.tab') }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/test-mode/clock'));
    expect(await screen.findByRole('heading', { name: T('mod.tclock.title') })).toBeTruthy();
  });
});
