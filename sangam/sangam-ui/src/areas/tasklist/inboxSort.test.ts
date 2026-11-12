// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-042 (UI): the inbox sorts by amount and by reference through GET /tasks sortBy=amount / sortBy=reference (the typed
 * task-index column of W3-G; contract REQUEST to W3-M). Unknown columns are never sent. Written, not run.
 */
import { describe, expect, it } from 'vitest';
import { taskSort } from '../../api/endpoints';

describe('M-042 inbox sort keys', () => {
  it('maps amount and reference to the contract keys, with the direction prefix', () => {
    expect(taskSort('amount')).toBe('amount');
    expect(taskSort('-amount')).toBe('-amount');
    expect(taskSort('reference')).toBe('reference');
    expect(taskSort('-reference')).toBe('-reference');
  });

  it('keeps the order of a multi-key sort and the existing mappings', () => {
    expect(taskSort('-amount,received')).toBe('-amount,created');
    expect(taskSort('slaDue,-reference')).toBe('due,-reference');
  });

  it('drops a column the contract cannot sort by', () => {
    expect(taskSort('fromSeatName,amount')).toBe('amount');
    expect(taskSort('packKey')).toBeUndefined();
  });
});
