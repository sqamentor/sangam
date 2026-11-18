// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
import type { ReactElement } from 'react';
/**
 * Owner decisions 338 and 342 on the officer compliance screens, with the API client mocked:
 * - 338: the consent and rights registers send the principal reference in a POST body to .../search; no request
 *   carries it in a URL or a query parameter;
 * - 342: the evidence report sends `from` and `to` as instants in the tenant's zone, and sends nothing without one.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), page: vi.fn(), request: vi.fn(), download: vi.fn() }));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, page: api.page }, request: api.request, download: api.download };
});

import { initI18n, setTenantLocale } from '../i18n';
import { EvidenceReport, checkPurpose, consents, purposes, rightsScreen } from './compliance';
import { ActionDialog } from './engine';
import { PrincipalSearchScreen } from './complianceSearch';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const show = (el: ReactElement) => render(<MemoryRouter>{el}</MemoryRouter>);
const REF = 'citizen-1@example.org';

beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const fn of Object.values(api)) fn.mockReset();
  api.get.mockResolvedValue({});
  setTenantLocale(null);
});
afterEach(cleanup);

/** Every URL and query the mocked client was asked for, as text. */
const urls = () => JSON.stringify([...api.get.mock.calls.map((c) => [c[0], c[1]]), ...api.post.mock.calls.map((c) => c[0]), ...api.request.mock.calls.map((c) => [c[1], (c[2] as { query?: unknown })?.query])]);

describe('decision 338: principal look-ups by POST body', () => {
  for (const def of [consents, rightsScreen(undefined, () => false)]) {
    it(`${def.id}: the reference goes in the body of ${def.search}, never in a URL`, async () => {
      api.post.mockResolvedValue([{ consentId: 'k1', requestId: 'r1', status: 'ACTIVE' }]);
      show(<PrincipalSearchScreen def={def} />);
      fireEvent.change(screen.getByLabelText(new RegExp(T('fld.principalRef'))), { target: { value: `  ${REF} ` } });
      fireEvent.click(screen.getByRole('button', { name: T('common.search') }));
      await waitFor(() => expect(api.post).toHaveBeenCalledWith(def.search, { subject: REF }));
      expect(await screen.findByRole('table')).toBeTruthy();
      expect(api.get).not.toHaveBeenCalled();
      expect(urls()).not.toContain(REF);
      expect(urls()).not.toContain('citizen-1');
    });
  }

  it('nothing is sent until a reference is typed', () => {
    show(<PrincipalSearchScreen def={consents} />);
    expect((screen.getByRole('button', { name: T('common.search') }) as HTMLButtonElement).disabled).toBe(true);
    expect(api.post).not.toHaveBeenCalled();
  });
});

describe('decision 342: the evidence report period in the tenant zone', () => {
  const pick = (first: string, last: string) => {
    fireEvent.change(screen.getByLabelText(new RegExp(T('mod.compliance.firstDay'))), { target: { value: first } });
    fireEvent.change(screen.getByLabelText(new RegExp(T('mod.compliance.lastDay'))), { target: { value: last } });
  };
  const generate = () => screen.getByRole('button', { name: T('mod.compliance.generate') }) as HTMLButtonElement;

  it('sends the start of the first day and the start of the day after the last, as instants in the tenant zone', async () => {
    setTenantLocale({ zone: 'Asia/Kolkata' });
    api.get.mockResolvedValue({ reportId: 'rep-1', hash: 'h', content: {} });
    show(<EvidenceReport />);
    expect(screen.getByText(T('mod.compliance.reportZone', { zone: 'Asia/Kolkata' }))).toBeTruthy();
    pick('2026-11-01', '2026-11-30');
    fireEvent.click(generate());
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/compliance/reports', { from: '2026-10-31T18:30:00.000Z', to: '2026-11-30T18:30:00.000Z', verifyChains: true, format: 'json' }),
    );
  });

  it('without the tenant zone the report cannot be asked for', () => {
    show(<EvidenceReport />);
    expect(screen.getByText(T('mod.compliance.reportNoZone'))).toBeTruthy();
    pick('2026-11-01', '2026-11-30');
    expect(generate().disabled).toBe(true);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('a last day before the first is refused before any call', () => {
    setTenantLocale({ zone: 'Asia/Kolkata' });
    show(<EvidenceReport />);
    pick('2026-11-30', '2026-11-01');
    expect(generate().disabled).toBe(true);
    expect(screen.getByText(T('mod.compliance.reportPeriodOrder'))).toBeTruthy();
  });
});

describe('define purpose: the legal attestations are explicit choices (integrator finding, CI run 43717963671)', () => {
  const define = purposes.toolbar!.find((a) => a.id === 'define')!;
  const dialog = () => screen.getByRole('dialog');
  const esc = (x: string) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const set = (label: string, value: string) => fireEvent.change(screen.getByLabelText(new RegExp(`^${esc(T(label))}`)), { target: { value } });
  const confirm = () => fireEvent.click(screen.getByRole('button', { name: T('common.confirm') }));

  it('sends standardsAttested and active as booleans the officer chose, data items with sensitive, and no version', async () => {
    api.request.mockResolvedValue({ code: 'P1', version: 1 });
    render(<ActionDialog act={define} onClose={() => undefined} onDone={() => undefined} />);
    expect(dialog()).toBeTruthy();
    set('fld.code', 'P1');
    set('fld.names', '{"en": "Purpose one"}');
    set('fld.lawfulBasis', 'CONSENT');
    set('fld.standardsAttested', 'false');
    set('fld.active', 'true');
    set('fld.dataItems', '[{"code": "name", "names": {"en": "Full name"}, "sensitive": false}]');
    confirm();
    await waitFor(() => expect(api.request).toHaveBeenCalled());
    const [method, path, opts] = api.request.mock.calls[0] as [string, string, { body: Record<string, unknown> }];
    expect([method, path]).toEqual(['PUT', '/compliance/purposes/by-code?code=P1']);
    expect(opts.body).toMatchObject({ basis: 'CONSENT', standardsAttested: false, active: true, dataItems: [{ code: 'name', sensitive: false }] });
    expect(opts.body.version).toBeUndefined();
  });

  it('nothing is sent until the attestation is chosen; it has no default', async () => {
    render(<ActionDialog act={define} onClose={() => undefined} onDone={() => undefined} />);
    expect((screen.getByLabelText(new RegExp(`^${esc(T('fld.standardsAttested'))}`)) as HTMLSelectElement).value).toBe('');
    set('fld.code', 'P1');
    set('fld.names', '{"en": "Purpose one"}');
    set('fld.lawfulBasis', 'CONSENT');
    set('fld.active', 'true');
    confirm();
    await new Promise((r) => setTimeout(r, 20));
    expect(api.request).not.toHaveBeenCalled();
  });

  it('a data item without sensitive true or false is refused before any call', () => {
    const t = (k: string) => k;
    expect(checkPurpose({ standardsAttested: false, active: true, dataItems: [{ code: 'name' }] }, t)).toBe('mod.compliance.dataItemsInvalid');
    expect(checkPurpose({ standardsAttested: false, active: true, dataItems: [{ code: 'name', sensitive: 'no' }] }, t)).toBe('mod.compliance.dataItemsInvalid');
    expect(checkPurpose({ standardsAttested: false, active: true, dataItems: [{ code: 'name', sensitive: true }] }, t)).toBeUndefined();
    expect(checkPurpose({ active: true }, t)).toBe('mod.compliance.choiceRequired');
  });
});
