// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 (component tests, first part, W2-C screens): each screen sends exactly the contract body the server reads and
 * shows the server's refusals in words. The API client is mocked; nothing is rendered by a server. Written, not run in
 * this phase (W3-J finishes M-047 for the remaining core flows).
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), page: vi.fn(), request: vi.fn(), download: vi.fn() }));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, page: api.page }, request: api.request, download: api.download };
});

import { ApiError } from '../api/client';
import { initI18n } from '../i18n';
import { BulkIngestPage, explain413 } from './bulk';
import { PackLifecycle, Promotions, GuidedRun, groupPacks, packProblem, promotionProblem } from './config';
import { ModuleSecurityDefaults } from '../areas/admin/Admin';
import { NoticeView } from './compliance';
import { BulkTestReport, reportRows, runRows } from './platform';
import { ActionMenu } from '../components/ActionMenu';
import { ActionDialog } from './engine';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const show = (el: JSX.Element) => render(<MemoryRouter>{el}</MemoryRouter>);
const problem = (status: number, code: string, extra: Record<string, unknown> = {}) => new ApiError({ status, title: 't', code, ...extra });
const dialog = () => screen.getByRole('dialog');

beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const f of Object.values(api)) f.mockReset();
  api.page.mockResolvedValue({ items: [], page: 0, size: 25, total: 0, capped: false });
  api.get.mockResolvedValue([]);
});
afterEach(cleanup);

describe('M-029 bulk ingest', () => {
  it('explains both 413s with the quota numbers', () => {
    expect(explain413(problem(413, 'TENANT_QUOTA_EXCEEDED', { allowed: 500, requested: 1000 }), T)).toMatch(/500.*1,000|500.*1000/);
    expect(explain413(problem(413, 'BULK_LIMIT', { detail: 'a chunk holds at most 1000 rows' }), T)).toMatch(/1000 rows/);
    expect(explain413(problem(409, 'CHUNK_CONFLICT'), T)).toBeNull();
  });

  it('creates a job with {objectType, expectedChunks} and opens it', async () => {
    api.request.mockImplementation(async (method: string, path: string) => {
      if (method === 'POST' && path === '/bulk/jobs') return { id: 'j1', objectType: 'ops.variable', state: 'OPEN', chunks: 0, totalRows: 0 };
      return undefined;
    });
    api.get.mockResolvedValue({ id: 'j1', objectType: 'ops.variable', state: 'OPEN', chunks: 0, totalRows: 0 });
    show(<BulkIngestPage />);
    fireEvent.click(screen.getByRole('button', { name: T('bulk.create') }));
    fireEvent.change(within(dialog()).getByLabelText(new RegExp(T('bulk.expectedChunks'))), { target: { value: '2' } });
    fireEvent.click(within(dialog()).getByRole('button', { name: T('common.confirm') }));
    await waitFor(() => expect(api.request).toHaveBeenCalledWith('POST', '/bulk/jobs', expect.objectContaining({ body: { objectType: 'ops.variable', expectedChunks: 2 } })));
    expect(await screen.findByText(T('bulk.jobTitle', { id: 'j1' }))).toBeTruthy();
  });
});

describe('M-030 pack lifecycle', () => {
  it('groups versions per pack and explains PACK_* refusals with their blockers', () => {
    const g = groupPacks([
      { packId: 'a', version: '1.0.0', tenantId: 't', deploymentId: 'd1' },
      { packId: 'a', version: '1.1.0', tenantId: 't', deploymentId: 'd2' },
      { packId: 'b', version: '2.0.0', tenantId: 't', deploymentId: 'd3' },
    ]);
    expect(g.map((x) => [x.packId, x.distinct])).toEqual([['a', 2], ['b', 1]]);
    const p = packProblem(problem(409, 'PACK_HAS_LIVE_INSTANCES', { blockers: ['pi-1', 'pi-2'] }), T);
    expect(p).toEqual({ message: T('packs.reason.PACK_HAS_LIVE_INSTANCES'), items: ['pi-1', 'pi-2'] });
    expect(packProblem(problem(422, 'PACK_REJECTED', { violations: ['V1'] }), T)?.items).toEqual(['V1']);
    expect(packProblem(problem(409, 'OTHER'), T)).toBeNull();
  });

  it('uninstalls with {tenant, reason} and lists the installed packs of the tenant', async () => {
    api.get.mockResolvedValue([{ packId: 'sec.leave', version: '1.0.0', tenantId: 'RJ', deploymentId: 'd1' }]);
    api.post.mockResolvedValue({ packId: 'sec.leave', tenantId: 'RJ', deploymentIds: ['d1'] });
    show(<PackLifecycle />);
    fireEvent.change(screen.getByLabelText(new RegExp(T('fld.tenant'))), { target: { value: 'RJ' } });
    fireEvent.click(screen.getByRole('button', { name: T('common.open') }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/packs/installed', { tenant: 'RJ' }, expect.anything()));
    expect((await screen.findByText(T('packs.rollback'))).closest('button')?.disabled).toBe(true); // one version: nothing to roll back to
    fireEvent.click(screen.getByText(T('packs.uninstall')));
    fireEvent.change(within(dialog()).getByLabelText(new RegExp(T('common.reason'))), { target: { value: 'pack replaced by v2' } });
    fireEvent.click(within(dialog()).getByRole('button', { name: T('common.confirm') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/packs/sec.leave/uninstall', { tenant: 'RJ', reason: 'pack replaced by v2' }, expect.any(String)));
  });
});

describe('M-034 promotions', () => {
  const bundle = { format: 1, body: { sourceTenant: 'RJ', environment: 'uat' }, signature: { keyId: 'k1', algorithm: 'Ed25519', value: 'x' } };
  it('plans with {bundle, targetTenant} and applies the reviewed plan by its hash', async () => {
    api.post.mockImplementation(async (path: string) =>
      path === '/onboarding/promotions/plan'
        ? { sourceTenant: 'RJ', sourceEnvironment: 'uat', targetTenant: 'RJ', targetEnvironment: 'prod', planHash: 'h1', changes: [{ path: 'packs.ids[x]', kind: 'ADDED', right: 'x' }], dryRun: { ok: true, problems: [] } }
        : { id: 'run-1', status: 'AWAITING_APPROVAL' },
    );
    show(<Promotions onApplied={() => undefined} />);
    fireEvent.change(screen.getByLabelText(T('promo.bundle')), { target: { value: JSON.stringify(bundle) } });
    fireEvent.change(screen.getByLabelText(new RegExp(T('promo.target'))), { target: { value: 'RJ' } });
    expect(screen.getByText(T('promo.signedBy', { key: 'k1', alg: 'Ed25519' }))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: T('promo.makePlan') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/onboarding/promotions/plan', { bundle, targetTenant: 'RJ' }));
    fireEvent.click(await screen.findByRole('button', { name: T('promo.apply') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/onboarding/promotions', { bundle, targetTenant: 'RJ', planHash: 'h1' }, expect.any(String)));
    expect(await screen.findByText(T('promo.applied', { id: 'run-1', status: 'AWAITING_APPROVAL' }))).toBeTruthy();
  });
  it('explains the promotion and bundle refusals', () => {
    expect(promotionProblem(problem(409, 'PROMOTION_PLAN_CHANGED'), T)).toBe(T('promo.err.PROMOTION_PLAN_CHANGED'));
    expect(promotionProblem(problem(422, 'BUNDLE_SIGNATURE_INVALID'), T)).toBe(T('promo.err.BUNDLE_SIGNATURE_INVALID'));
    expect(promotionProblem(problem(400, 'VALIDATION_FAILED'), T)).toBeNull();
  });
});

describe('M-035 guided onboarding', () => {
  it('starts a run only after a clean dry run of the built request', async () => {
    api.post.mockImplementation(async (path: string) => (path === '/onboarding/dry-run' ? { ok: true, problems: [] } : { id: 'r1' }));
    const started = vi.fn();
    show(<GuidedRun onStarted={started} initial={{ tenant: { id: 'RJ.FIN', name: 'वित्त' } }} />);
    fireEvent.click(screen.getByRole('button', { name: T('onb.step.review') }));
    const start = screen.getByRole('button', { name: T('mod.onboarding.start') });
    expect((start as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: T('mod.onboarding.dryRun') }));
    const body = { tenant: { id: 'RJ.FIN', name: 'वित्त' }, profile: { mode: 'INHERIT' }, packs: { allowUpgrade: false }, security: { moduleDefaults: true, selfService: true } };
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/onboarding/dry-run', body));
    await waitFor(() => expect((screen.getByRole('button', { name: T('mod.onboarding.start') }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: T('mod.onboarding.start') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/onboarding/runs', body, expect.any(String)));
    await waitFor(() => expect(started).toHaveBeenCalled());
  });
});

describe('M-032 module security defaults', () => {
  it('installs with {selfService} and lists the role templates', async () => {
    api.post.mockResolvedValue({ tenantId: 'RJ', roleTemplates: ['registry.clerk'], selfService: false });
    show(<ModuleSecurityDefaults />);
    fireEvent.click(screen.getByLabelText(T('secdef.selfService')));
    fireEvent.click(screen.getByRole('button', { name: T('secdef.installButton') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/security/module-defaults', { selfService: false }, expect.any(String)));
    expect(await screen.findByText('registry.clerk')).toBeTruthy();
  });
});

describe('M-033 privacy notice', () => {
  it('shows the notice text in its own language and direction, never as injected HTML', () => {
    const { container } = render(
      <NoticeView notice={{ noticeKey: 'pp', version: 3, language: { code: 'ur', rtl: true, nativeName: 'اردو' }, text: 'نوٹس <b>x</b>', html: '<script>bad()</script>', contentHash: 'abc', fallback: true }} />,
    );
    const text = container.querySelector('.notice-text') as HTMLElement;
    expect(text.getAttribute('lang')).toBe('ur');
    expect(text.getAttribute('dir')).toBe('rtl');
    expect(text.textContent).toBe('نوٹس <b>x</b>');
    expect(container.querySelector('script, b')).toBeNull();
    expect(screen.getByText(T('privacy.fallback'))).toBeTruthy();
  });
});

describe('M-037 action menu', () => {
  it('lists the actions for the subject and runs one with {subjectRef, input} under one idempotency key', async () => {
    api.get.mockImplementation(async (path: string) => (path === '/actions' ? [{ key: 'notify', label: 'Notify applicant' }] : []));
    api.post.mockResolvedValue({ operationId: 'op-1', result: { processInstanceId: 'pi-9' }, replayed: false });
    show(<ActionMenu scope="TASK" subjectRef="task-7" />);
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/actions', { scope: 'TASK', targetRef: 'task-7' }, expect.anything()));
    fireEvent.click(await screen.findByText('Notify applicant'));
    fireEvent.change(within(dialog()).getByLabelText(new RegExp(T('actions.input'))), { target: { value: '{"channel":"sms"}' } });
    fireEvent.click(within(dialog()).getByRole('button', { name: T('actions.run') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/actions/notify/run', { subjectRef: 'task-7', input: { channel: 'sms' } }, expect.any(String)));
    expect(await within(dialog()).findByText('op-1')).toBeTruthy();
  });
  it('shows nothing when the caller may not invoke actions (403)', async () => {
    api.get.mockRejectedValue(problem(403, 'ACTION_NOT_PERMITTED'));
    const { container } = show(<ActionMenu scope="CASE" subjectRef="c1" />);
    await waitFor(() => expect(api.get).toHaveBeenCalled());
    expect(container.querySelector('details')).toBeNull();
  });
});

describe('M-038 test reports', () => {
  it('keeps judge results apart: by the stored flag, or by the judge: prefix of a fresh run', () => {
    expect(runRows([{ name: 'happy', passed: true }, { name: 'judge:tone', passed: false }]).map((r) => [r.name, r.deterministic, r.status])).toEqual([
      ['happy', true, 'PASSED'],
      ['judge:tone', false, 'FAILED'],
    ]);
    expect(reportRows({ runId: 'r', passed: 1, failed: 0, notJudged: 1, cases: [{ caseId: 'x', status: 'NOT_JUDGED', deterministic: false }] })[0].deterministic).toBe(false);
  });
  it('lists the stored reports and shows a report with judged cases in their own table', async () => {
    api.get.mockResolvedValue([{ runId: 'run-1', passed: 1, failed: 0, notJudged: 1, cases: [{ caseId: 'happy', status: 'PASSED', deterministic: true }, { caseId: 'judge:tone', status: 'NOT_JUDGED', deterministic: false }] }]);
    show(<BulkTestReport packageId="pkg-1" />);
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/packages/pkg-1/tests/reports', undefined, expect.anything()));
    fireEvent.click(await screen.findByRole('button', { name: 'run-1' }));
    expect(screen.getByRole('table', { name: T('mod.modeller2.judged') })).toBeTruthy();
    expect(screen.getByRole('table', { name: T('mod.modeller2.deterministic') })).toBeTruthy();
  });
});

describe('M-039 engine: purpose code in a header, body fields from the row', () => {
  // SL-166 (5), W3-M REQUEST 2026-11-12T12:04:42Z: X-Purpose is the purpose CODE (RegistryPortBridges.java:143-151,
  // CMP_PURPOSE.purpose_code); the user picks it by its localized name, and no label or free text is ever sent.
  const reveal = { id: 'reveal', label: 'act.reveal', path: '/registry/parties/identifiers/{identifierId}/reveal', purposeHeader: 'X-Purpose', noJson: true, showResult: true };
  const confirmButton = () => within(dialog()).getByRole('button', { name: T('common.confirm') }) as HTMLButtonElement;

  it('lists the purposes by name and sends the chosen code as X-Purpose, never the label, with no body reason', async () => {
    api.get.mockImplementation(async (path: string) =>
      path === '/compliance/purposes' ? [{ code: 'KYC_REVERIFY', names: { en: 'KYC re-verification', hi: 'केवाईसी पुनः सत्यापन' } }, { code: 'GRIEVANCE', names: { en: 'Grievance redressal' } }] : [],
    );
    api.request.mockResolvedValue({ identifierId: 'i1', value: 'XXXX' });
    render(<ActionDialog act={reveal} row={{ identifierId: 'i1' }} onClose={() => undefined} onDone={() => undefined} />);
    const select = (await within(dialog()).findByLabelText(new RegExp(T('mod.common.purpose')))) as HTMLSelectElement;
    expect(within(select).getByRole('option', { name: 'KYC re-verification (KYC_REVERIFY)' })).toBeTruthy();
    expect(within(dialog()).queryByLabelText(new RegExp(T('common.reason')))).toBeNull();
    expect(confirmButton().disabled).toBe(true);
    fireEvent.change(select, { target: { value: 'KYC_REVERIFY' } });
    fireEvent.click(confirmButton());
    await waitFor(() => expect(api.request).toHaveBeenCalled());
    const [method, path, opts] = api.request.mock.calls[0] as [string, string, { body?: Record<string, unknown>; headers: Record<string, string> }];
    expect([method, path]).toEqual(['POST', '/registry/parties/identifiers/i1/reveal']);
    expect(opts.headers).toEqual({ 'X-Purpose': 'KYC_REVERIFY' });
    expect(opts.body?.reason).toBeUndefined();
    expect(api.get).toHaveBeenCalledWith('/compliance/purposes', undefined, expect.anything());
  });

  it('without compliance.read the code is typed, and a description or non-ASCII text is refused before any call', async () => {
    api.get.mockRejectedValue(problem(403, 'FORBIDDEN'));
    api.request.mockResolvedValue({ identifierId: 'i1', value: 'XXXX' });
    render(<ActionDialog act={reveal} row={{ identifierId: 'i1' }} onClose={() => undefined} onDone={() => undefined} />);
    const code = await within(dialog()).findByLabelText(new RegExp(T('mod.common.purposeCode')));
    for (const bad of ['सत्यापन हेतु', 'KYC re-verification']) {
      fireEvent.change(code, { target: { value: bad } });
      expect(within(dialog()).getByText(T('mod.common.purposeCodeInvalid'))).toBeTruthy();
      expect(confirmButton().disabled).toBe(true);
    }
    expect(within(dialog()).queryByRole('alert')).toBeNull();
    fireEvent.change(code, { target: { value: 'KYC_REVERIFY' } });
    fireEvent.click(confirmButton());
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(1));
    expect((api.request.mock.calls[0][2] as { headers: Record<string, string> }).headers).toEqual({ 'X-Purpose': 'KYC_REVERIFY' });
  });
  it('adds body fields taken from the row (milestoneId must equal the path id)', async () => {
    api.request.mockResolvedValue({});
    render(<ActionDialog act={{ id: 'progress', label: 'act.recordProgress', method: 'PATCH', path: '/programme-milestones/{id}', noJson: true, bodyFromRow: (r) => ({ milestoneId: r.id }), fields: [{ key: 'expectedVersion', type: 'number', label: 'fld.expectedVersion' }] }} row={{ id: 'm-1' }} onClose={() => undefined} onDone={() => undefined} />);
    fireEvent.change(within(dialog()).getByLabelText(new RegExp(T('fld.expectedVersion'))), { target: { value: '4' } });
    fireEvent.click(within(dialog()).getByRole('button', { name: T('common.confirm') }));
    await waitFor(() => expect(api.request).toHaveBeenCalled());
    const [, path, opts] = api.request.mock.calls[0] as [string, string, { body: Record<string, unknown> }];
    expect(path).toBe('/programme-milestones/m-1');
    expect(opts.body).toMatchObject({ milestoneId: 'm-1', expectedVersion: 4 });
  });
});
