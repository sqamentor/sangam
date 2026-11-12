// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * C3-09: a dialog action with `load` prefills its form from a GET of the current server object (engine.tsx Act.load,
 * prefillFrom). 404 means nothing to prefill; another failure is shown; the PUT carries what the form holds.
 * Written, not run.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), request: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get }, request: api.request };
});

import { ApiError } from '../api/client';
import { initI18n } from '../i18n';
import { ActionDialog, type Act } from './engine';
import { programmeConfigFields } from './programmeConfig';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const act: Act = { id: 'publish', label: 'act.publish', method: 'PUT', path: '/programme-config', fields: programmeConfigFields(), load: { path: '/programme-config', omit: ['version', 'contentHash'] } };

beforeAll(() => initI18n('en'));
beforeEach(() => {
  api.get.mockReset();
  api.request.mockReset();
});
afterEach(cleanup);

describe('C3-09 dialog prefill', () => {
  it('fills the form from the GET answer and publishes it back', async () => {
    api.get.mockResolvedValue({ fiscalYearStartMonth: 4, indicators: [{ key: 'km' }], units: ['km'], version: 2, contentHash: 'h' });
    api.request.mockResolvedValue({});
    render(<ActionDialog act={act} onClose={() => undefined} onDone={() => undefined} />);
    expect(await screen.findByText(T('mod.common.prefilled'))).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/programme-config', undefined, expect.anything());
    fireEvent.click(screen.getByRole('button', { name: T('common.confirm') }));
    await waitFor(() => expect(api.request).toHaveBeenCalled());
    const [method, path, opts] = api.request.mock.calls[0];
    expect(method).toBe('PUT');
    expect(path).toBe('/programme-config');
    expect(opts.body).toEqual({ fiscalYearStartMonth: 4, indicators: [{ key: 'km' }], units: ['km'] });
  });

  it('404 opens the form empty, without an error', async () => {
    api.get.mockRejectedValue(new ApiError({ status: 404, title: 'Not found' }));
    render(<ActionDialog act={act} onClose={() => undefined} onDone={() => undefined} />);
    await waitFor(() => expect(api.get).toHaveBeenCalled());
    expect(screen.queryByText(T('mod.common.prefilled'))).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('another failure is shown and the form stays usable', async () => {
    api.get.mockRejectedValue(new ApiError({ status: 403, title: 'Forbidden' }));
    render(<ActionDialog act={act} onClose={() => undefined} onDone={() => undefined} />);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('button', { name: T('common.confirm') })).toBeTruthy();
  });

  it('an action without load makes no GET', () => {
    render(<ActionDialog act={{ ...act, load: undefined }} onClose={() => undefined} onDone={() => undefined} />);
    expect(api.get).not.toHaveBeenCalled();
  });
});
