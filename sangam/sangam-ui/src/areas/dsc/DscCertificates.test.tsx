// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Owner decision 227: DSC certificate enrolment screens (request, approve with dsc.certificate.approve, revoke) on the
// W4-M contract routes/dsc.py; decision 241 note. The API is a mock, the agent a fake fetch. Written, not run.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { initI18n } from '../../i18n';
import { ApiError } from '../../api/client';
import type { DscEnrolment } from '../../api/types';

const dsc = { enrol: vi.fn(), mine: vi.fn(), revokeMine: vi.fn(), list: vi.fn(), get: vi.fn(), decide: vi.fn() };
vi.mock('../../api/endpoints', () => ({
  api: {
    dsc: {
      enrol: (...a: unknown[]) => dsc.enrol(...a),
      mine: (...a: unknown[]) => dsc.mine(...a),
      revokeMine: (...a: unknown[]) => dsc.revokeMine(...a),
      list: (...a: unknown[]) => dsc.list(...a),
      get: (...a: unknown[]) => dsc.get(...a),
      decide: (...a: unknown[]) => dsc.decide(...a),
    },
  },
}));

import { DscCertificateQueue, MyDscCertificates, certificateFileToBase64, explainEnrolmentProblem, reasonProblem } from './DscCertificates';

const ROW: DscEnrolment = {
  enrolmentId: 'enr-1',
  principalId: 'officer.a',
  certificateSha256: 'b'.repeat(64),
  issuer: 'CN=Licensed CA',
  serialHex: '0a1b',
  subject: 'CN=Officer A',
  notBefore: '2026-01-01T00:00:00Z',
  notAfter: '2028-01-01T00:00:00Z',
  status: 'PENDING',
  enrolledAt: '2026-11-13T00:00:00Z',
};
const t = (k: string, o?: Record<string, unknown>) => (o ? `${k} ${JSON.stringify(o)}` : k);
const problem = (status: number, code: string) => new ApiError({ title: code, status, code });

beforeAll(() => {
  initI18n('en');
  // jsdom has <dialog> but not its modal API.
  const proto = HTMLDialogElement.prototype as unknown as { showModal?: () => void; close?: () => void };
  proto.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  proto.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
});
beforeEach(() => {
  for (const f of Object.values(dsc)) f.mockReset();
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('certificate file', () => {
  it('takes DER as it is and the first PEM block, and refuses anything else', () => {
    const der = new Uint8Array([0x30, 0x03, 0x02, 0x01, 0x05]);
    expect(certificateFileToBase64(der)).toBe('MAMCAQU=');
    const pem = new TextEncoder().encode('junk\n-----BEGIN CERTIFICATE-----\nMAMC\nAQU=\n-----END CERTIFICATE-----\n');
    expect(certificateFileToBase64(pem)).toBe('MAMCAQU=');
    expect(certificateFileToBase64(new Uint8Array())).toBeUndefined();
    expect(certificateFileToBase64(new TextEncoder().encode('hello'))).toBeUndefined();
    expect(certificateFileToBase64(new TextEncoder().encode('-----BEGIN CERTIFICATE-----\nAQU=\n-----END CERTIFICATE-----'))).toBeUndefined();
    const big = new Uint8Array(16 * 1024 + 1);
    big[0] = 0x30;
    expect(certificateFileToBase64(big)).toBeUndefined();
  });
});

describe('reasons and problems', () => {
  it('requires a reason for reject and revoke, not for approve, at most 512 characters', () => {
    expect(reasonProblem('  ', true, t)).toBe('form.required');
    expect(reasonProblem('', false, t)).toBeUndefined();
    expect(reasonProblem('x'.repeat(512), true, t)).toBeUndefined();
    expect(reasonProblem('x'.repeat(513), false, t)).toContain('form.maxLength');
  });

  it('explains the contract codes', () => {
    expect(explainEnrolmentProblem(problem(403, 'SELF_APPROVAL'), t)).toBe('dsc.cert.selfApproval');
    expect(explainEnrolmentProblem(problem(409, 'ENROLMENT_NOT_PENDING'), t)).toBe('dsc.cert.alreadyDecided');
    expect(explainEnrolmentProblem(problem(409, 'CERTIFICATE_ALREADY_ENROLLED'), t)).toBe('dsc.cert.alreadyEnrolled');
    expect(explainEnrolmentProblem(problem(422, 'CERTIFICATE_EXPIRED'), t)).toBe('dsc.cert.expired');
    expect(explainEnrolmentProblem(problem(500, 'OTHER'), t)).toBeUndefined();
  });
});

describe('MyDscCertificates', () => {
  it('enrols the signer certificate from the token (first in the agent chain) and lists it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url.endsWith('/v1/status')
          ? new Response(JSON.stringify({ agent: 'sangam-dsc', api: 1, algorithms: [] }), { status: 200 })
          : new Response(JSON.stringify({ certificates: [{ certificateSha256: 'c'.repeat(64), subject: 'CN=Officer A', issuer: 'CN=CA', notAfter: '2028-01-01T00:00:00Z', certificateChain: ['U0lHTkVS', 'Q0E='] }] }), { status: 200 }),
      ),
    );
    dsc.mine.mockResolvedValueOnce([]).mockResolvedValue([ROW]);
    dsc.enrol.mockResolvedValue(ROW);
    render(<MyDscCertificates agentUrl="http://127.0.0.1:47321" />);
    expect(screen.getByText(/signed again/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Read the certificates on my token' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Enrol' }));
    await waitFor(() => expect(dsc.enrol).toHaveBeenCalledTimes(1));
    expect(dsc.enrol.mock.calls[0][0]).toBe('U0lHTkVS');
    await waitFor(() => expect(within(screen.getByRole('table')).getByText('Pending')).toBeTruthy());
  });

  it('revokes my own live enrolment only with a reason', async () => {
    dsc.mine.mockResolvedValue([{ ...ROW, status: 'APPROVED' }]);
    dsc.revokeMine.mockResolvedValue({ ...ROW, status: 'REVOKED' });
    render(<MyDscCertificates agentUrl="" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }));
    expect(dsc.revokeMine).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '  token lost  ' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(dsc.revokeMine).toHaveBeenCalledTimes(1));
    expect(dsc.revokeMine.mock.calls[0].slice(0, 2)).toEqual(['enr-1', 'token lost']);
  });

  it('says DSC is not installed on a 501', async () => {
    dsc.mine.mockRejectedValue(problem(501, 'CAPABILITY_NOT_INSTALLED'));
    render(<MyDscCertificates agentUrl="" />);
    await waitFor(() => expect(screen.getByText(/not installed/)).toBeTruthy());
  });
});

describe('DscCertificateQueue', () => {
  it('lists PENDING by default and approves with an optional reason', async () => {
    dsc.list.mockResolvedValue([ROW]);
    dsc.decide.mockResolvedValue({ ...ROW, status: 'APPROVED' });
    render(<DscCertificateQueue />);
    await waitFor(() => expect(dsc.list).toHaveBeenCalledWith('PENDING'));
    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(dsc.decide).toHaveBeenCalledTimes(1));
    expect(dsc.decide.mock.calls[0].slice(0, 3)).toEqual(['enr-1', 'approve', '']);
  });

  it('refuses a reject without a reason and shows SELF_APPROVAL in words', async () => {
    dsc.list.mockResolvedValue([ROW]);
    dsc.decide.mockRejectedValue(problem(403, 'SELF_APPROVAL'));
    render(<DscCertificateQueue />);
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reject' }));
    expect(dsc.decide).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'not the officer' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(within(dialog).getByText(/your own/)).toBeTruthy());
  });

  it('offers only revoke for an APPROVED enrolment, and nothing for a decided one', async () => {
    dsc.list.mockImplementation(async (s: string) => (s === 'APPROVED' ? [{ ...ROW, status: 'APPROVED' }] : [{ ...ROW, status: 'REJECTED' }]));
    render(<DscCertificateQueue />);
    await waitFor(() => expect(within(screen.getByRole('table')).getByText('Rejected')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Revoke' })).toBeNull();
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'APPROVED' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Revoke' })).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  });
});
