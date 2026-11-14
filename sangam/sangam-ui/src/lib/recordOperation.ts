// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 265 (contract 1D188888, getDataObjectRecordOperation): the outcome of a record create on a REST-backed
 * data object is read by polling its operation until it settles. Rules kept here:
 * - bounded: at most POLL_DELAYS_MS.length reads, growing delays (about two minutes in all); after that the caller says
 *   the outcome is not known yet and offers a manual check, never success;
 * - PENDING keeps polling; COMPLETED, FAILED and UNKNOWN are final (UNKNOWN is in reconciliation, no automatic retry);
 * - 503 (DATA_OBJECT_CONNECTOR_FAILED and every 503) waits at least its Retry-After before the next read;
 * - any other error (404, 501, 403) stops polling and is handed to the caller.
 */
import { ApiError } from '../api/client';
import type { DataObjectRecordOperation } from '../api/types';

export const POLL_DELAYS_MS: readonly number[] = [1000, 2000, 3000, 5000, 8000, 13000, 20000, 30000, 30000];
/** Upper bound of one wait, also for a Retry-After (a longer one ends the automatic polling). */
export const MAX_WAIT_MS = 60000;

export interface PollResult {
  operation: DataObjectRecordOperation;
  /** false when the bound was reached while the operation was still PENDING (or never read). */
  settled: boolean;
}

export type Sleep = (ms: number, signal?: AbortSignal) => Promise<void>;

export const sleep: Sleep = (ms, signal) =>
  new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new DOMException('Aborted', 'AbortError'));
    const id = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(id);
      reject(new DOMException('Aborted', 'AbortError'));
    });
  });

export const isSettled = (op: DataObjectRecordOperation): boolean => op.status !== 'PENDING';

/**
 * Polls read() with the bounded delays until the operation is no longer PENDING. `last` is the operation known so far
 * (the 202 answer). onRead is told every operation read, so the screen can show it.
 */
export async function pollRecordOperation(
  read: (signal?: AbortSignal) => Promise<DataObjectRecordOperation>,
  last: DataObjectRecordOperation,
  opts: { sleep?: Sleep; signal?: AbortSignal; onRead?: (op: DataObjectRecordOperation) => void } = {},
): Promise<PollResult> {
  const wait = opts.sleep ?? sleep;
  let current = last;
  let extra = 0;
  for (const delay of POLL_DELAYS_MS) {
    const ms = Math.max(delay, extra);
    if (ms > MAX_WAIT_MS) break;
    await wait(ms, opts.signal);
    extra = 0;
    try {
      current = await read(opts.signal);
    } catch (e) {
      if (e instanceof ApiError && e.status === 503) {
        extra = e.retryAfterMs ?? 0;
        continue;
      }
      throw e;
    }
    opts.onRead?.(current);
    if (isSettled(current)) return { operation: current, settled: true };
  }
  return { operation: current, settled: false };
}
