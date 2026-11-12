// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-046: axe-core on the shared components and the W2-C screens (WCAG 2.1 A/AA; serious and critical issues fail).
 * This runs in `npm test`, which ui.yml runs on every UI change, so it is the CI gate. Written, not run in this phase.
 * The remaining screens join as M-047 adds their component tests (W3-J).
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), page: vi.fn(), request: vi.fn() }));
vi.mock('./api/client', async (orig) => {
  const real = await orig<typeof import('./api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, page: api.page }, request: api.request };
});

import { initI18n } from './i18n';
import { blockingA11yIssues } from './test/axe';
import { DataTable, Dialog, KeyValues, ProblemAlert, SelectField, TextArea, TextField } from './components/ui';
import { ApiError } from './api/client';
import { BulkIngestPage } from './modules/bulk';
import { GuidedRun, PackLifecycle } from './modules/config';
import { ModuleSecurityDefaults } from './areas/admin/Admin';
import { NoticeView } from './modules/compliance';

const show = (el: JSX.Element) => render(<MemoryRouter>{el}</MemoryRouter>);

beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const f of Object.values(api)) f.mockReset();
  api.page.mockResolvedValue({ items: [], page: 0, size: 25, total: 0, capped: false });
  api.get.mockResolvedValue([]);
});
afterEach(cleanup);

describe('M-046 axe: shared components', () => {
  it('form fields, table, key values and a problem have no serious or critical issue', async () => {
    const { container } = render(
      <main>
        <h1>Form</h1>
        <TextField label="Name" required value="राम" onChange={() => undefined} error="Too short" />
        <TextArea label="Remarks" hint="Optional" value="" onChange={() => undefined} />
        <SelectField label="Status" value="" onChange={() => undefined} placeholder="All" options={[{ value: 'OPEN', label: 'Open' }]} />
        <DataTable caption="Jobs" rows={[{ id: 'j1', n: 'नाम' }]} rowKey={(r) => r.id} columns={[{ key: 'id', header: 'Id' }, { key: 'n', header: 'Name' }]} />
        <KeyValues items={[['Tenant', 'RJ']]} />
        <ProblemAlert error={new ApiError({ status: 409, title: 'Conflict', code: 'X', detail: 'Changed meanwhile' })} />
      </main>,
    );
    expect(await blockingA11yIssues(container)).toEqual([]);
  });

  it('a dialog has an accessible name and controls', async () => {
    render(
      <Dialog open title="Close job" onClose={() => undefined} footer={<button type="button">Confirm</button>}>
        <p>No more chunks can be added.</p>
      </Dialog>,
    );
    expect(await blockingA11yIssues(screen.getByRole('dialog'))).toEqual([]);
  });
});

describe('M-046 axe: W2-C screens', () => {
  it('bulk ingest', async () => {
    const { container } = show(<BulkIngestPage />);
    await screen.findByText(/./);
    expect(await blockingA11yIssues(container)).toEqual([]);
  });
  it('pack lifecycle', async () => {
    const { container } = show(<PackLifecycle />);
    expect(await blockingA11yIssues(container)).toEqual([]);
  });
  it('guided onboarding', async () => {
    const { container } = show(<GuidedRun onStarted={() => undefined} />);
    expect(await blockingA11yIssues(container)).toEqual([]);
  });
  it('module security defaults', async () => {
    const { container } = show(<ModuleSecurityDefaults />);
    expect(await blockingA11yIssues(container)).toEqual([]);
  });
  it('a right-to-left notice in the English UI', async () => {
    const { container } = render(
      <main>
        <h1>Privacy notice</h1>
        <NoticeView notice={{ noticeKey: 'pp', version: 1, language: { code: 'ur', rtl: true }, text: 'نوٹس', contentHash: 'h' }} />
      </main>,
    );
    expect(await blockingA11yIssues(container)).toEqual([]);
  });
});
