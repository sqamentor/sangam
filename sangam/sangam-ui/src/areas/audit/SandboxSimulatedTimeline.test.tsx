// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Register E-26, Follow-up 1 (IFMS joint condition: a simulated outside check never reads as an IFMS answer). How the
 * UI shows a demo-sandbox answer today, and that the two checks the sandbox now answers (ifms.duplicateBill, docs.issue)
 * show the same way as a W-5 simulated payment (pfms.payment):
 * - every page of a sandbox tenant carries the badge "Sandbox" with its tooltip (Layout.tsx SandboxBadge, en.json
 *   auth.sandbox / auth.sandboxNotice; the tooltip wording is the one the owner approved in Follow-up 3);
 * - the case and task views link to the Case timeline (Audit.tsx Timeline), whose "Details" of an event show what the
 *   server recorded: simulated=true and sandbox=true for each of the three connectors.
 * The server side of the same marks is DemoLoaderSequenceAgainstServerTest step "sandbox marks".
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn(), request: vi.fn(), download: vi.fn() }));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return {
    ...real,
    http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload },
    request: api.request,
    download: api.download,
  };
});
vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: 'rajendra.sharma', displayName: 'R', tenantId: 'GOI.FIN', roles: ['sangam-tenant-admin'], seats: [], sandbox: true }, seat: null, mode: 'dev', hasRole: () => true }),
}));
vi.mock('../../plugins/host', () => ({ PluginSlot: () => null, usePlugins: () => [] }));

import { initI18n, tr } from '../../i18n';
import { SandboxBadge } from '../../app/Layout';
import { Timeline } from './Audit';

const BK = 'JPR-DDO-0420/BILL/2026-27/0001';

/** An audit entry as GET /audit/timeline sends it (AuditPort.AuditEntry): the pack event's details are its payload. */
const entry = (seq: number, type: string, payload: Record<string, unknown>) => ({
  eventId: `ev-${seq}`,
  chainId: BK,
  seq,
  type,
  occurredAt: '2026-11-21T04:30:00Z',
  actor: 'system',
  payload,
  hash: `h${seq}`,
  prevHash: `h${seq - 1}`,
});

/** What Vars.audit records for a sandbox tenant (sandboxMarked): sandbox=true always, simulated=true for a simulated connector. */
const W5_PAYMENT = entry(1, 'EXT_OP_REQUESTED', { connector: 'pfms.payment', step: 'pay', status: 'PENDING', sandbox: true, simulated: true });
const DUPLICATE_CHECK = entry(2, 'EXT_OP_ACKNOWLEDGED', { connector: 'ifms.duplicateBill', operation: 'check', mode: 'query', status: 'COMPLETED', sandbox: true, simulated: true });
const DOCUMENT = entry(3, 'DOCUMENT_ISSUED', { template: 'payment-advice', documentNo: 'DOC/1A2B3C4D', connector: 'docs.issue', sandbox: true, simulated: true });

beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const f of Object.values(api)) f.mockReset();
  api.get.mockResolvedValue([]);
  api.page.mockResolvedValue({ items: [W5_PAYMENT, DUPLICATE_CHECK, DOCUMENT], page: 0, size: 50, total: 3, capped: false });
});
afterEach(cleanup);

describe('E-26 Follow-up 1: a simulated check shows as simulated, as a W-5 payment does', () => {
  /** Follow-up 3 (owner approval, 2026-11-21): the tooltip names everything the sandbox simulates and never says "demo". */
  const NOTICE = 'Sandbox tenant: payees, payments, the duplicate-bill check and issued document numbers are simulated. No real money moves.';

  it('the badge says Sandbox and its tooltip reads exactly as the owner approved, with no "demo" in any case', () => {
    render(<SandboxBadge sandbox />);
    const badge = screen.getByTestId('sandbox-badge');
    expect(badge.textContent).toBe('Sandbox');
    expect(badge.getAttribute('title')).toBe(NOTICE);
    expect(tr('auth.sandbox')).toBe('Sandbox');
    expect(tr('auth.sandboxNotice')).toBe(NOTICE);
    expect(badge.getAttribute('title') ?? '').not.toMatch(/demo/i);
    expect(tr('auth.sandboxNotice')).not.toMatch(/demo/i);
  });

  it('the Case timeline shows simulated=true and sandbox=true in the Details of the duplicate check, the document and the W-5 payment alike', async () => {
    render(
      <MemoryRouter initialEntries={[`/audit/timeline/${encodeURIComponent(BK)}`]}>
        <Routes>
          <Route path="/audit/timeline/:businessKey" element={<Timeline />} />
        </Routes>
      </MemoryRouter>,
    );
    const buttons = await screen.findAllByRole('button', { name: tr('audit.details') });
    expect(buttons).toHaveLength(3);
    const shown: Record<string, string> = {};
    for (const [i, connector] of ['pfms.payment', 'ifms.duplicateBill', 'docs.issue'].entries()) {
      fireEvent.click(screen.getAllByRole('button', { name: tr('audit.details') })[i]);
      const pre = document.querySelector('pre.json');
      expect(pre, connector).not.toBeNull();
      const details = JSON.parse(pre!.textContent ?? '{}') as Record<string, unknown>;
      expect(details.connector).toBe(connector);
      expect(details.simulated, connector).toBe(true);
      expect(details.sandbox, connector).toBe(true);
      shown[connector] = `simulated=${String(details.simulated)} sandbox=${String(details.sandbox)}`;
    }
    // the same marking for all three: a simulated check reads like a simulated payment, never like an outside answer
    expect(new Set(Object.values(shown)).size).toBe(1);
  });

  it('a check the sandbox did not answer shows no simulated mark (the timeline adds none of its own)', async () => {
    api.page.mockResolvedValue({ items: [entry(1, 'EXT_OP_ACKNOWLEDGED', { connector: 'ledger.available', status: 'COMPLETED', sandbox: true })], page: 0, size: 50, total: 1, capped: false });
    render(
      <MemoryRouter initialEntries={[`/audit/timeline/${encodeURIComponent(BK)}`]}>
        <Routes>
          <Route path="/audit/timeline/:businessKey" element={<Timeline />} />
        </Routes>
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByRole('button', { name: tr('audit.details') }));
    const details = JSON.parse(document.querySelector('pre.json')!.textContent ?? '{}') as Record<string, unknown>;
    expect(details.connector).toBe('ledger.available');
    expect(details.simulated).toBeUndefined();
  });
});
