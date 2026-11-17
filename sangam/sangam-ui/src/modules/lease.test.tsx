// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Presence/lease key fix (Wave 4b W4-J): one edit lease per package, keyed by the package id like the save fence
// (ModellerWiring.fenced on PUT /packages/{id}) and presence (streamModelPresence {file}); the fence goes with
// heartbeat, release and every save; a takeover carries the reason the server requires. Written, not run.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { initI18n } from '../i18n';
import { setTokenProvider } from '../api/client';
import { EDIT_FENCE_HEADER, api } from '../api/endpoints';
import { LeaseBanner, leasePath, type LeaseState } from './platform';

beforeAll(() => initI18n('en'));

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } });
const MINE = { fileId: 'pkg/1', holder: 'me', fence: 7, expiresAt: '2026-11-13T10:00:00Z', mine: true, editable: true };
const THEIRS = { fileId: 'pkg/1', holder: 'asha', fence: 9, expiresAt: '2026-11-13T10:00:00Z', mine: false, editable: false };

const fetchMock = vi.fn();
beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  setTokenProvider(async () => 'tok');
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  setTokenProvider(async () => null);
});

const last = () => fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
const call = (i: number) => ({ url: String(fetchMock.mock.calls[i][0]), body: JSON.parse(String(fetchMock.mock.calls[i][1].body ?? '{}')) as Record<string, unknown> });

describe('lease key', () => {
  it('is the package id, the same key as the save fence and presence', () => {
    expect(leasePath('pkg/1')).toBe('/models/by-file/lease?file=pkg%2F1');
    expect(api.models.presencePath('pkg/1')).toBe('/models/by-file/presence/stream?file=pkg%2F1');
  });
});

describe('LeaseBanner', () => {
  it('acquires by package id, reports mine with the fence, and shows no banner', async () => {
    fetchMock.mockResolvedValueOnce(json(MINE));
    const onState = vi.fn<(s: LeaseState) => void>();
    const { container } = render(<LeaseBanner packageId="pkg/1" onState={onState} />);
    await waitFor(() => expect(onState).toHaveBeenCalledWith({ mine: true, fence: 7 }));
    expect(call(0).url).toBe('/api/v1/models/by-file/lease/acquire?file=pkg%2F1');
    expect(container.querySelector('.alert')).toBeNull();
  });

  it('renews with the fence every 30 s and releases with the fence on close', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fetchMock.mockResolvedValueOnce(json(MINE)).mockResolvedValue(json(MINE));
    const onState = vi.fn();
    const { unmount } = render(<LeaseBanner packageId="pkg-1" onState={onState} />);
    await waitFor(() => expect(onState).toHaveBeenCalled());
    await act(async () => { await vi.advanceTimersByTimeAsync(30000); });
    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(call(1).url).toBe('/api/v1/models/by-file/lease/heartbeat?file=pkg-1');
    expect(call(1).body).toEqual({ fence: 7 });
    unmount();
    await waitFor(() => expect(String(last()[0])).toBe('/api/v1/models/by-file/lease/release?file=pkg-1'));
    expect(JSON.parse(String(last()[1].body))).toEqual({ fence: 7 });
  });

  it('a renewal refused as EDIT_LEASE_LOST stops saving with the old fence and re-reads the holder', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fetchMock
      .mockResolvedValueOnce(json(MINE))
      .mockResolvedValueOnce(json({ title: 'Conflict', status: 409, code: 'EDIT_LEASE_LOST' }, 409))
      .mockResolvedValueOnce(json(THEIRS));
    const onState = vi.fn<(s: LeaseState) => void>();
    render(<LeaseBanner packageId="pkg-1" onState={onState} />);
    await waitFor(() => expect(onState).toHaveBeenCalledWith({ mine: true, fence: 7 }));
    await act(async () => { await vi.advanceTimersByTimeAsync(30000); });
    await waitFor(() => expect(onState).toHaveBeenCalledWith({ mine: false, fence: undefined }));
    expect(onState).toHaveBeenCalledWith({ mine: false });
    expect(screen.getByText(/asha is editing this model\./)).toBeTruthy();
  });

  it('when someone else holds it: names the holder, asks for it, and takes over only with a reason', async () => {
    fetchMock.mockResolvedValueOnce(json(THEIRS)).mockResolvedValueOnce(new Response(null, { status: 204 }));
    render(<LeaseBanner packageId="pkg-1" />);
    await waitFor(() => expect(screen.getByText(/asha is editing this model\./)).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Ask to edit' }));
    await waitFor(() => expect(call(1).url).toBe('/api/v1/models/by-file/lease/request-takeover?file=pkg-1'));
    fetchMock.mockResolvedValueOnce(json({ fileId: 'pkg-1', holder: 'me', fence: 10 })).mockResolvedValueOnce(json({ ...MINE, fence: 10 }));
    fireEvent.click(screen.getByRole('button', { name: 'Take over' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(dialog.querySelector('textarea')!, { target: { value: 'Holder is on leave' } });
    fireEvent.click(Array.from(dialog.querySelectorAll('button')).find((b) => b.className.includes('danger') || b.className.includes('primary'))!);
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => String(c[0]).includes('/lease/takeover?'))).toBe(true));
    const take = fetchMock.mock.calls.find((c) => String(c[0]).includes('/lease/takeover?'))!;
    expect(JSON.parse(String(take[1].body))).toEqual({ reason: 'Holder is on leave' });
    await waitFor(() => expect(String(last()[0])).toBe('/api/v1/models/by-file/lease/acquire?file=pkg-1'));
  });

  it('a reader without package.author (403) sees no error banner', async () => {
    fetchMock.mockResolvedValueOnce(json({ title: 'Forbidden', status: 403 }, 403));
    render(<LeaseBanner packageId="pkg-1" />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 10));
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('package save fence', () => {
  it('sends X-Sangam-Edit-Fence with the lease fence, and no header without a lease', async () => {
    fetchMock.mockImplementation(async () => json({ id: 'pkg-1', name: 'P', key: 'p', version: 1, state: 'DRAFT', resources: {} }));
    await api.packages.saveResources('pkg-1', [{ id: 'a.bpmn', name: 'a.bpmn', kind: 'BPMN', content: '<x/>' }], 'P', 7);
    expect((fetchMock.mock.calls[0][1].headers as Headers).get(EDIT_FENCE_HEADER)).toBe('7');
    await api.packages.saveResources('pkg-1', [{ id: 'a.bpmn', name: 'a.bpmn', kind: 'BPMN', content: '<x/>' }], 'P');
    expect((fetchMock.mock.calls[1][1].headers as Headers).get(EDIT_FENCE_HEADER)).toBeNull();
  });
});
