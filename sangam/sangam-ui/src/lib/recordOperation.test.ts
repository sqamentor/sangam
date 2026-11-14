// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Owner decision 265: polling of a REST-backed record create is bounded, stops at a final state, honours Retry-After on
// a 503, and never turns "not settled" into success. Written, not run in this phase.
import { describe, expect, it, vi } from 'vitest';
import { ApiError, retryAfterMs } from '../api/client';
import type { DataObjectRecordOperation } from '../api/types';
import { MAX_WAIT_MS, POLL_DELAYS_MS, pollRecordOperation } from './recordOperation';

const PENDING: DataObjectRecordOperation = { operationId: 'op-1', dataObjectKey: 'vendor', status: 'PENDING' };
const noSleep = vi.fn(async () => undefined);

describe('pollRecordOperation', () => {
  it('stops at COMPLETED with the record', async () => {
    const read = vi.fn().mockResolvedValueOnce(PENDING).mockResolvedValueOnce({ ...PENDING, status: 'COMPLETED', record: { id: 'v-9' } });
    const r = await pollRecordOperation(read, PENDING, { sleep: noSleep });
    expect(r).toEqual({ operation: { ...PENDING, status: 'COMPLETED', record: { id: 'v-9' } }, settled: true });
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('treats FAILED and UNKNOWN as final (UNKNOWN is never retried automatically)', async () => {
    for (const status of ['FAILED', 'UNKNOWN'] as const) {
      const read = vi.fn().mockResolvedValue({ ...PENDING, status, failureCode: status === 'FAILED' ? 'DUPLICATE_VENDOR' : undefined });
      const r = await pollRecordOperation(read, PENDING, { sleep: noSleep });
      expect(r.settled).toBe(true);
      expect(r.operation.status).toBe(status);
      expect(read).toHaveBeenCalledTimes(1);
    }
  });

  it('is bounded: after the last delay a PENDING operation is reported unsettled, not created', async () => {
    const read = vi.fn().mockResolvedValue(PENDING);
    const waits: number[] = [];
    const r = await pollRecordOperation(read, PENDING, { sleep: async (ms) => void waits.push(ms) });
    expect(r).toEqual({ operation: PENDING, settled: false });
    expect(read).toHaveBeenCalledTimes(POLL_DELAYS_MS.length);
    expect(waits).toEqual([...POLL_DELAYS_MS]);
    for (let i = 1; i < waits.length; i++) expect(waits[i]).toBeGreaterThanOrEqual(waits[i - 1]);
  });

  it('waits at least Retry-After after a 503 DATA_OBJECT_CONNECTOR_FAILED, then goes on', async () => {
    const busy = new ApiError({ title: 'x', status: 503, code: 'DATA_OBJECT_CONNECTOR_FAILED', retryAfter: '7' });
    const read = vi.fn().mockRejectedValueOnce(busy).mockResolvedValueOnce({ ...PENDING, status: 'COMPLETED', record: {} });
    const waits: number[] = [];
    const r = await pollRecordOperation(read, PENDING, { sleep: async (ms) => void waits.push(ms) });
    expect(r.settled).toBe(true);
    expect(waits).toEqual([POLL_DELAYS_MS[0], Math.max(POLL_DELAYS_MS[1], 7000)]);
  });

  it('a Retry-After beyond the bound ends the automatic polling unsettled', async () => {
    const busy = new ApiError({ title: 'x', status: 503, code: 'DATA_OBJECT_CONNECTOR_FAILED', retryAfter: String(MAX_WAIT_MS / 1000 + 1) });
    const read = vi.fn().mockRejectedValue(busy);
    const r = await pollRecordOperation(read, PENDING, { sleep: noSleep });
    expect(r).toEqual({ operation: PENDING, settled: false });
    expect(read).toHaveBeenCalledTimes(1);
  });

  it('hands any other error to the caller (404, 501)', async () => {
    const read = vi.fn().mockRejectedValue(new ApiError({ title: 'x', status: 501, code: 'CAPABILITY_NOT_INSTALLED' }));
    await expect(pollRecordOperation(read, PENDING, { sleep: noSleep })).rejects.toMatchObject({ status: 501 });
  });
});

describe('retryAfterMs', () => {
  it('reads delay seconds and an HTTP date', () => {
    expect(retryAfterMs('5')).toBe(5000);
    expect(retryAfterMs('Fri, 13 Nov 2026 06:00:10 GMT', Date.parse('2026-11-13T06:00:00Z'))).toBe(10000);
    expect(retryAfterMs('soon')).toBeUndefined();
    expect(retryAfterMs(undefined)).toBeUndefined();
    expect(new ApiError({ title: 'x', status: 503, retryAfter: '2' }).retryAfterMs).toBe(2000);
  });
});
