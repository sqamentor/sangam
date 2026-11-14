// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Decisions 165, 227, 228, 236-238, 241: DscSigner on the DSC contract routes (routes/dsc.py). The server is a mock of
// api.tasks.dscPrepare / dscComplete, the agent a fake fetch. Written, not run in this phase.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { initI18n } from '../../i18n';
import { ApiError } from '../../api/client';
import type { Attachment, DscCompleteResponse, DscPrepareResponse } from '../../api/types';

const dscPrepare = vi.fn();
const dscComplete = vi.fn();
vi.mock('../../api/endpoints', () => ({
  api: { tasks: { dscPrepare: (...a: unknown[]) => dscPrepare(...a), dscComplete: (...a: unknown[]) => dscComplete(...a) } },
}));

import { CompletionProblem, DscOutcomeError, TaskDscSign, explainDscProblem, isPdf, preselectedPdf } from './TaskDscSign';

const AGENT = 'http://127.0.0.1:47321';
const HEX = 'e'.repeat(64);
const CHAIN = ['TUlJQ2VydA==', 'TUlJQ0E='];
const PDF: Attachment = { id: 'doc-1', name: 'Sanction order.pdf', contentType: 'application/pdf', size: 10, sha256: 'a'.repeat(64), version: 3, uploadedBy: 'u', uploadedAt: '2026-11-13T00:00:00Z', scanStatus: 'CLEAN' };
const DOCX: Attachment = { ...PDF, id: 'doc-2', name: 'notes.docx', contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' };
const PREPARED: DscPrepareResponse = {
  prepareId: '0123456789abcdef0123456789abcdef',
  signedAttributes: 'MQA=',
  preparedDigestSha256: 'f'.repeat(64),
  algorithm: 'SHA256withRSA',
  expiresAt: '2026-11-13T00:10:00Z',
  documentId: 'doc-1',
  documentVersion: 3,
};
const VALID: DscCompleteResponse = { outcome: 'VERIFIED_VALID', reasonCode: null, retryable: false, evidence: {}, documentId: 'doc-1', signedDocumentVersion: 4 };

function agentFetch() {
  return vi.fn(async (url: string) => {
    if (url.endsWith('/v1/status')) return new Response(JSON.stringify({ agent: 'sangam-dsc', api: 1, algorithms: ['SHA256withRSA'] }), { status: 200 });
    if (url.endsWith('/v1/certificates'))
      return new Response(JSON.stringify({ certificates: [{ certificateSha256: HEX, subject: 'CN=Officer', issuer: 'CN=CA', notAfter: '2027-01-01T00:00:00Z', certificateChain: CHAIN }] }), { status: 200 });
    return new Response(JSON.stringify({ signature: 'c2lnbmF0dXJl', algorithm: 'SHA256withRSA', certificateChain: CHAIN }), { status: 200 });
  });
}

async function signOnce() {
  fireEvent.click(screen.getByRole('button', { name: 'Find my signing certificates' }));
  await waitFor(() => expect(screen.getByRole('radio')).toBeTruthy());
  fireEvent.click(screen.getByRole('button', { name: 'Sign Sanction order.pdf' }));
}

const problem = (status: number, code: string) => new ApiError({ title: code, status, code });

beforeAll(() => initI18n('en'));
beforeEach(() => {
  dscPrepare.mockReset();
  dscComplete.mockReset();
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('TaskDscSign', () => {
  it('shows nothing when the DSC agent is not configured', () => {
    const { container } = render(<TaskDscSign taskId="t1" documents={[PDF]} agentUrl="" />);
    expect(container.textContent).toBe('');
  });

  it('offers PDF attachments only', () => {
    expect(isPdf(PDF)).toBe(true);
    expect(isPdf({ name: 'scan.PDF', contentType: '' })).toBe(true);
    expect(isPdf({ name: 'x', contentType: 'application/pdf; charset=binary' })).toBe(true);
    expect(isPdf(DOCX)).toBe(false);
    render(<TaskDscSign taskId="t1" documents={[DOCX]} agentUrl={AGENT} />);
    expect(screen.getByText(/Upload the PDF/)).toBeTruthy();
  });

  it('prepares with the agent chain and the document, completes with the same prepareId and chain, and does not complete the task', async () => {
    vi.stubGlobal('fetch', agentFetch());
    dscPrepare.mockResolvedValue(PREPARED);
    dscComplete.mockResolvedValue(VALID);
    const onSigned = vi.fn();
    render(<TaskDscSign taskId="t1" documents={[PDF, DOCX]} agentUrl={AGENT} onSigned={onSigned} />);
    await signOnce();
    await waitFor(() => expect(onSigned).toHaveBeenCalledTimes(1));
    expect(dscPrepare).toHaveBeenCalledWith('t1', { documentId: 'doc-1', certificateChain: CHAIN });
    expect(dscComplete).toHaveBeenCalledWith('t1', { prepareId: PREPARED.prepareId, signature: 'c2lnbmF0dXJl', algorithm: 'SHA256withRSA', certificateChain: CHAIN });
    expect(screen.getByText(/version 4/)).toBeTruthy();
    expect(screen.getByText(/Complete the task/)).toBeTruthy();
  });

  it('explains a certificate that is not an approved enrolment (decision 227) and never asks the agent to sign', async () => {
    const f = agentFetch();
    vi.stubGlobal('fetch', f);
    dscPrepare.mockRejectedValue(problem(422, 'CERTIFICATE_NOT_ENROLLED'));
    render(<TaskDscSign taskId="t1" documents={[PDF]} agentUrl={AGENT} />);
    await signOnce();
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('My DSC certificates'));
    expect(f.mock.calls.some(([u]) => String(u).endsWith('/v1/sign'))).toBe(false);
    expect(dscComplete).not.toHaveBeenCalled();
  });

  it('a revocation after prepare gives VERIFIED_INVALID CERTIFICATE_NOT_ENROLLED: not signed, sign again (decision 241)', async () => {
    vi.stubGlobal('fetch', agentFetch());
    dscPrepare.mockResolvedValue(PREPARED);
    dscComplete.mockResolvedValue({ ...VALID, outcome: 'VERIFIED_INVALID', reasonCode: 'CERTIFICATE_NOT_ENROLLED', signedDocumentVersion: null });
    const onSigned = vi.fn();
    render(<TaskDscSign taskId="t1" documents={[PDF]} agentUrl={AGENT} onSigned={onSigned} />);
    await signOnce();
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('revoked'));
    expect(onSigned).not.toHaveBeenCalled();
    expect(screen.queryByText(/^Signed\./)).toBeNull();
    expect(screen.queryByText(/version 4/)).toBeNull();
  });

  it('a changed document (409 DOCUMENT_CHANGED, decision 228) asks to sign again; the used prepare is not sent twice', async () => {
    vi.stubGlobal('fetch', agentFetch());
    dscPrepare.mockResolvedValue(PREPARED);
    dscComplete.mockRejectedValueOnce(problem(409, 'DOCUMENT_CHANGED')).mockResolvedValueOnce(VALID);
    const onSigned = vi.fn();
    render(<TaskDscSign taskId="t1" documents={[PDF]} agentUrl={AGENT} onSigned={onSigned} />);
    await signOnce();
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('changed'));
    fireEvent.click(screen.getByRole('button', { name: 'Sign Sanction order.pdf' }));
    await waitFor(() => expect(onSigned).toHaveBeenCalledTimes(1));
    // signing again prepares again: every complete call carries a prepare made for it
    expect(dscPrepare).toHaveBeenCalledTimes(2);
    expect(dscComplete).toHaveBeenCalledTimes(2);
  });

  it('a server without the optional module (501) says signing is not installed', async () => {
    vi.stubGlobal('fetch', agentFetch());
    dscPrepare.mockRejectedValue(problem(501, 'CAPABILITY_NOT_INSTALLED'));
    render(<TaskDscSign taskId="t1" documents={[PDF]} agentUrl={AGENT} />);
    await signOnce();
    await waitFor(() => expect(screen.getByText(/not installed/)).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Sign Sanction order.pdf' })).toBeNull();
  });

  it('preselects the step PDF from TaskDetail.dscDocument and signs it without a manual pick (decision 236)', async () => {
    vi.stubGlobal('fetch', agentFetch());
    const other: Attachment = { ...PDF, id: 'doc-9', name: 'Annexure.pdf' };
    dscPrepare.mockResolvedValue(PREPARED);
    dscComplete.mockResolvedValue(VALID);
    render(<TaskDscSign taskId="t1" documents={[other, PDF]} agentUrl={AGENT} dscDocument={{ slot: 'notingDocumentId', documentId: 'doc-1' }} />);
    expect(screen.getByText(/notingDocumentId/)).toBeTruthy();
    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('doc-1');
    await signOnce();
    await waitFor(() => expect(dscComplete).toHaveBeenCalledTimes(1));
    expect(dscPrepare.mock.calls[0][1]).toMatchObject({ documentId: 'doc-1' });
  });

  it('keeps the manual pick when dscDocument is null, has no document id, or names a document not attached', () => {
    expect(preselectedPdf([PDF], null)).toBeUndefined();
    expect(preselectedPdf([PDF], { slot: 'notingDocumentId', documentId: null })).toBeUndefined();
    expect(preselectedPdf([PDF], { slot: 'notingDocumentId', documentId: 'gone' })).toBeUndefined();
    expect(preselectedPdf([PDF], { slot: 'notingDocumentId', documentId: 'doc-1' })).toBe(PDF);
    const other: Attachment = { ...PDF, id: 'doc-9', name: 'Annexure.pdf' };
    render(<TaskDscSign taskId="t1" documents={[other, PDF]} agentUrl={AGENT} dscDocument={{ slot: 'notingDocumentId', documentId: 'gone' }} />);
    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('');
    expect(screen.getByText(/not among the PDFs/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Find my signing certificates/ })).toBeNull();
  });

  it('completion: 422 DSC_ENROLMENT_REVOKED has its own message, DSC_SIGNATURE_NOT_VERIFIED keeps the generic one', () => {
    const { unmount } = render(<CompletionProblem error={problem(422, 'DSC_ENROLMENT_REVOKED')} />);
    expect(screen.getByRole('alert').textContent).toContain('currently approved certificate');
    unmount();
    render(<CompletionProblem error={new ApiError({ title: 'Unprocessable', status: 422, code: 'DSC_SIGNATURE_NOT_VERIFIED', detail: 'No verified signature' })} />);
    const generic = screen.getByRole('alert').textContent ?? '';
    expect(generic).not.toContain('currently approved certificate');
    expect(generic).toContain('No verified signature');
  });

  it('explains the contract codes of prepare and complete', () => {
    const t = (k: string, o?: Record<string, unknown>) => (o ? `${k} ${JSON.stringify(o)}` : k);
    expect(explainDscProblem(problem(403, 'NOT_ASSIGNEE'), t)).toBe('dsc.task.notAssignee');
    expect(explainDscProblem(problem(409, 'DSC_PREPARE_NOT_USABLE'), t)).toBe('dsc.task.prepareNotUsable');
    expect(explainDscProblem(problem(422, 'ALREADY_SIGNED'), t)).toBe('dsc.task.alreadySigned');
    expect(explainDscProblem(problem(422, 'DOCUMENT_NOT_PDF'), t)).toBe('dsc.task.notTaskPdf');
    expect(explainDscProblem(problem(409, 'DOCUMENT_NOT_CLEAN'), t)).toBe('dsc.task.notClean');
    expect(explainDscProblem(new DscOutcomeError({ ...VALID, outcome: 'INDETERMINATE', reasonCode: 'OCSP_UNAVAILABLE', retryable: true }), t)).toContain('dsc.task.indeterminateRetry');
    expect(explainDscProblem(new DscOutcomeError({ ...VALID, outcome: 'VERIFIED_INVALID', reasonCode: 'SIGNER_MISMATCH' }), t)).toBe('dsc.task.signerMismatch');
    expect(explainDscProblem(problem(422, 'SOMETHING_NEW'), t)).toContain('dsc.error.server');
  });
});
