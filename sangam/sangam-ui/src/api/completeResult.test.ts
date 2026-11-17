// SPDX-License-Identifier: Apache-2.0
/**
 * e2e tasklist "claim, then complete": POST /tasks/{id}/complete answers the contract's CompleteTaskResult, which has
 * no `state`; the done screen badges one, and Badge threw on undefined (a blank page after a 200).
 */
import { describe, expect, it } from 'vitest';
import { normCompleteResult } from './adapters';

describe('normCompleteResult', () => {
  const wire = { taskId: 't1', processInstanceId: 'p1', decision: 'COMPLETE', payloadHash: 'h', nextTaskIds: [] };

  it('gives the state ENDED when the instance ended', () => {
    expect(normCompleteResult({ ...wire, instanceEnded: true }).state).toBe('ENDED');
  });

  it('gives the state COMPLETED when the instance goes on, and keeps the contract fields', () => {
    const r = normCompleteResult({ ...wire, instanceEnded: false, nextTaskIds: ['t2'] });
    expect(r.state).toBe('COMPLETED');
    expect(r).toMatchObject({ taskId: 't1', nextTaskIds: ['t2'] });
  });

  it('keeps a state the server sends', () => {
    expect(normCompleteResult({ ...wire, state: 'RESUBMITTED' }).state).toBe('RESUBMITTED');
  });
});
