// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Owner decisions 267-269: upload and new version with documentType and replaceSlot; 403 DOCUMENT_SLOT_NOT_TASK_HOLDER
// and 409 DOCUMENT_SLOT_OCCUPIED (replace only on the officer's confirmation). Written, not run in this phase.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { initI18n } from '../../i18n';
import { ApiError } from '../../api/client';
import type { Attachment } from '../../api/types';

const upload = vi.fn();
const addVersion = vi.fn();
vi.mock('../../api/endpoints', async (orig) => ({
  ...((await orig()) as object),
  api: { documents: { upload: (...a: unknown[]) => upload(...a), addVersion: (...a: unknown[]) => addVersion(...a) } },
}));

import { documentForm } from '../../api/endpoints';
import { SlotUpload } from './SlotUpload';

const DOC: Attachment = { id: 'doc-1', name: 'Noting.pdf', contentType: 'application/pdf', size: 1, sha256: 'a'.repeat(64), version: 2, uploadedBy: 'u', uploadedAt: '2026-11-13T00:00:00Z' };
const file = () => new File(['%PDF-1.7'], 'Noting v3.pdf', { type: 'application/pdf' });
const problem = (status: number, code: string) => new ApiError({ title: code, status, code });

function choose(type: string, target = '') {
  const [typeSel, targetSel] = screen.getAllByRole('combobox') as HTMLSelectElement[];
  fireEvent.change(typeSel, { target: { value: type } });
  fireEvent.change(targetSel, { target: { value: target } });
  const input = screen.getByLabelText('File (PDF)') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [file()] } });
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  upload.mockReset();
  addVersion.mockReset();
});
afterEach(cleanup);

describe('documentForm', () => {
  it('sends documentType, taskId and replaceSlot only when given', () => {
    const f = documentForm(file(), { taskId: 't1', documentType: 'NOTING', replaceSlot: true });
    expect(f.get('taskId')).toBe('t1');
    expect(f.get('documentType')).toBe('NOTING');
    expect(f.get('replaceSlot')).toBe('true');
    const plain = documentForm(file(), { documentType: '', replaceSlot: false });
    expect(plain.has('documentType')).toBe(false);
    expect(plain.has('replaceSlot')).toBe(false);
    expect(plain.get('file')).toBeInstanceOf(File);
  });
});

describe('SlotUpload', () => {
  it('needs a document type before a file can be chosen', () => {
    render(<SlotUpload taskId="t1" documents={[]} />);
    expect((screen.getByLabelText('File (PDF)') as HTMLInputElement).disabled).toBe(true);
  });

  it('uploads a new document for the task with its type, without replaceSlot', async () => {
    upload.mockResolvedValue(DOC);
    const onStored = vi.fn();
    render(<SlotUpload taskId="t1" documents={[DOC]} onStored={onStored} />);
    choose('NOTING');
    await waitFor(() => expect(onStored).toHaveBeenCalled());
    expect(upload.mock.calls[0][1]).toEqual({ taskId: 't1', documentType: 'NOTING', replaceSlot: false });
    expect(addVersion).not.toHaveBeenCalled();
  });

  it('adds a new version of a chosen document with its type', async () => {
    addVersion.mockResolvedValue(DOC);
    render(<SlotUpload taskId="t1" documents={[DOC]} />);
    choose('SANCTION_NOTE', 'doc-1');
    await waitFor(() => expect(addVersion).toHaveBeenCalledTimes(1));
    expect(addVersion.mock.calls[0][0]).toBe('doc-1');
    expect(addVersion.mock.calls[0][2]).toEqual({ documentType: 'SANCTION_NOTE', replaceSlot: false });
  });

  it('on 409 DOCUMENT_SLOT_OCCUPIED asks, and replaces only on confirmation, with a new Idempotency-Key', async () => {
    upload.mockRejectedValueOnce(problem(409, 'DOCUMENT_SLOT_OCCUPIED')).mockResolvedValueOnce(DOC);
    const onStored = vi.fn();
    render(<SlotUpload taskId="t1" documents={[]} onStored={onStored} />);
    choose('NOTING');
    const replace = await screen.findByRole('button', { name: 'Replace the slot document' });
    expect(upload).toHaveBeenCalledTimes(1);
    expect(onStored).not.toHaveBeenCalled();
    fireEvent.click(replace);
    await waitFor(() => expect(onStored).toHaveBeenCalled());
    expect(upload.mock.calls[1][1]).toEqual({ taskId: 't1', documentType: 'NOTING', replaceSlot: true });
    expect(upload.mock.calls[1][2]).not.toBe(upload.mock.calls[0][2]);
  });

  it('cancelling the replace sends nothing more', async () => {
    upload.mockRejectedValue(problem(409, 'DOCUMENT_SLOT_OCCUPIED'));
    render(<SlotUpload taskId="t1" documents={[]} />);
    choose('NOTING');
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('button', { name: 'Replace the slot document' })).toBeNull();
    expect(upload).toHaveBeenCalledTimes(1);
  });

  it('explains 403 DOCUMENT_SLOT_NOT_TASK_HOLDER', async () => {
    upload.mockRejectedValue(problem(403, 'DOCUMENT_SLOT_NOT_TASK_HOLDER'));
    render(<SlotUpload taskId="t1" documents={[]} />);
    choose('NOTING');
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Nothing was stored'));
  });
});
