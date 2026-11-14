// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Owner decisions 265 and 284: record create answers 201 (DB) or 202 + operation (REST); PENDING is shown and polled
// until COMPLETED, FAILED or UNKNOWN; 503 honours Retry-After; 501 says not installed; the button needs
// lowcode.record.write. Written, not run in this phase.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { initI18n } from '../../i18n';
import { ApiError } from '../../api/client';
import type { DataObjectDef } from '../../api/endpoints';
import { RECORD_WRITERS, canAccess } from '../../app/access';

const createRecord = vi.fn();
const recordOperation = vi.fn();
vi.mock('../../api/endpoints', () => ({
  api: { dataObjects: { createRecord: (...a: unknown[]) => createRecord(...a), recordOperation: (...a: unknown[]) => recordOperation(...a) } },
}));

import { RecordCreate, recordFromInputs } from './RecordCreate';

const DEF: DataObjectDef = {
  key: 'vendor',
  name: 'Vendor',
  backing: 'REST',
  fields: [
    { name: 'name', type: 'string', required: true },
    { name: 'limit', type: 'number' },
    { name: 'active', type: 'boolean' },
  ],
};
const PENDING = { operationId: 'op-1', dataObjectKey: 'vendor', status: 'PENDING' as const };
const fast = async () => undefined;

function fill() {
  fireEvent.change(screen.getByLabelText(/^name/), { target: { value: 'Acme' } });
  fireEvent.change(screen.getByLabelText(/^limit/), { target: { value: '12345678901234567.89' } });
  fireEvent.click(screen.getByRole('button', { name: 'New record' }));
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  createRecord.mockReset();
  recordOperation.mockReset();
});
afterEach(cleanup);

describe('recordFromInputs', () => {
  it('sends numbers as exact decimals and refuses missing or bad values', () => {
    const r = recordFromInputs(DEF.fields, { name: ' Acme ', limit: '0012345678901234567.890', active: true });
    expect(r.invalid).toEqual([]);
    expect(r.record).toEqual({ name: 'Acme', limit: { type: 'Decimal', value: '12345678901234567.890' }, active: true });
    expect(recordFromInputs(DEF.fields, { limit: '42' }).record.limit).toBe(42);
    expect(recordFromInputs(DEF.fields, { limit: '1,5' }).invalid).toEqual(['name', 'limit']);
    expect(recordFromInputs([{ name: 'j', type: 'json' }], { j: '{bad' }).invalid).toEqual(['j']);
  });
});

describe('RecordCreate', () => {
  it('DB backing: 201 shows the record at once', async () => {
    createRecord.mockResolvedValue({ kind: 'created', record: { id: 'v-1', name: 'Acme' } });
    const onCreated = vi.fn();
    render(<RecordCreate def={{ ...DEF, backing: 'DB' }} onClose={vi.fn()} onCreated={onCreated} pollSleep={fast} />);
    fill();
    await waitFor(() => expect(screen.getByText('Record created.')).toBeTruthy());
    expect(onCreated).toHaveBeenCalledTimes(1);
    expect(recordOperation).not.toHaveBeenCalled();
    expect(createRecord.mock.calls[0][1]).toEqual({ name: 'Acme', limit: { type: 'Decimal', value: '12345678901234567.89' } });
  });

  it('REST backing: 202 shows PENDING, polls the operation and shows the record on COMPLETED', async () => {
    createRecord.mockResolvedValue({ kind: 'accepted', operation: PENDING, location: '/api/v1/data-objects/vendor/records/operations/op-1' });
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => (release = r));
    recordOperation.mockImplementationOnce(async () => {
      await gate;
      return PENDING;
    });
    recordOperation.mockResolvedValueOnce({ ...PENDING, status: 'COMPLETED', record: { id: 'v-7', name: 'Acme' } });
    const onCreated = vi.fn();
    render(<RecordCreate def={DEF} onClose={vi.fn()} onCreated={onCreated} pollSleep={fast} />);
    fill();
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Pending'));
    expect(screen.queryByText('Record created.')).toBeNull();
    expect(onCreated).not.toHaveBeenCalled();
    release();
    await waitFor(() => expect(screen.getByText('Record created.')).toBeTruthy());
    expect(screen.getByText(/v-7/)).toBeTruthy();
    expect(recordOperation).toHaveBeenCalledWith('vendor', 'op-1', expect.anything());
  });

  it('FAILED shows the failure code and never success', async () => {
    createRecord.mockResolvedValue({ kind: 'accepted', operation: PENDING });
    recordOperation.mockResolvedValue({ ...PENDING, status: 'FAILED', failureCode: 'DUPLICATE_VENDOR' });
    const onCreated = vi.fn();
    render(<RecordCreate def={DEF} onClose={vi.fn()} onCreated={onCreated} pollSleep={fast} />);
    fill();
    await waitFor(() => expect(screen.getByText(/DUPLICATE_VENDOR/)).toBeTruthy());
    expect(screen.queryByText('Record created.')).toBeNull();
    expect(onCreated).not.toHaveBeenCalled();
  });

  it('UNKNOWN says the outcome is not confirmed', async () => {
    createRecord.mockResolvedValue({ kind: 'accepted', operation: PENDING });
    recordOperation.mockResolvedValue({ ...PENDING, status: 'UNKNOWN' });
    render(<RecordCreate def={DEF} onClose={vi.fn()} pollSleep={fast} />);
    fill();
    await waitFor(() => expect(screen.getByText(/not confirmed/)).toBeTruthy());
    expect(screen.queryByText('Record created.')).toBeNull();
  });

  it('still PENDING after the bound: not confirmed yet, with a manual check', async () => {
    createRecord.mockResolvedValue({ kind: 'accepted', operation: PENDING });
    recordOperation.mockResolvedValue(PENDING);
    render(<RecordCreate def={DEF} onClose={vi.fn()} pollSleep={fast} />);
    fill();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Check again' })).toBeTruthy());
    expect(screen.getByText(/not confirmed yet/)).toBeTruthy();
    expect(screen.queryByText('Record created.')).toBeNull();
  });

  it('503 DATA_OBJECT_CONNECTOR_FAILED: retry offered after Retry-After, with the same Idempotency-Key', async () => {
    createRecord.mockRejectedValueOnce(new ApiError({ title: 'x', status: 503, code: 'DATA_OBJECT_CONNECTOR_FAILED', retryAfter: '0' }));
    createRecord.mockResolvedValueOnce({ kind: 'created', record: { id: 'v-2' } });
    render(<RecordCreate def={DEF} onClose={vi.fn()} pollSleep={fast} />);
    fill();
    const retry = await screen.findByRole('button', { name: 'Retry' });
    await waitFor(() => expect((retry as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(retry);
    await waitFor(() => expect(screen.getByText('Record created.')).toBeTruthy());
    expect(createRecord.mock.calls[1][2]).toBe(createRecord.mock.calls[0][2]);
  });

  it('503 with a Retry-After in the future keeps the retry disabled and says when', async () => {
    createRecord.mockRejectedValue(new ApiError({ title: 'x', status: 503, code: 'DATA_OBJECT_CONNECTOR_FAILED', retryAfter: '30' }));
    render(<RecordCreate def={DEF} onClose={vi.fn()} pollSleep={fast} />);
    fill();
    await waitFor(() => expect(screen.getByText(/try again in 30 seconds/)).toBeTruthy());
    expect((screen.getByRole('button', { name: 'Retry' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('501 says REST-backed records are not installed', async () => {
    createRecord.mockRejectedValue(new ApiError({ title: 'x', status: 501, code: 'CAPABILITY_NOT_INSTALLED' }));
    render(<RecordCreate def={DEF} onClose={vi.fn()} pollSleep={fast} />);
    fill();
    await waitFor(() => expect(screen.getByText(/not installed/)).toBeTruthy());
  });
});

describe('decision 284 gate', () => {
  it('only holders of lowcode.record.write see the create button', () => {
    expect(canAccess({ roles: ['TASK_USER'] }, RECORD_WRITERS)).toBe(true);
    expect(canAccess({ roles: ['MODELLER'] }, RECORD_WRITERS)).toBe(true);
    expect(canAccess({ roles: ['REVIEWER'] }, RECORD_WRITERS)).toBe(false);
    expect(canAccess({ roles: ['AUDITOR'] }, RECORD_WRITERS)).toBe(false);
    expect(canAccess({ roles: ['REVIEWER'], permissions: ['lowcode.record.write'] }, RECORD_WRITERS)).toBe(true);
    expect(canAccess({ roles: ['REVIEWER'], permissions: ['lowcode.read'] }, RECORD_WRITERS)).toBe(false);
  });
});
