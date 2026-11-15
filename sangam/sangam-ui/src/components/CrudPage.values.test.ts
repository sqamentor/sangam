// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { FormField } from '../api/types';
import { newEntryValues } from './CrudPage';

describe('newEntryValues', () => {
  const fields: FormField[] = [
    { key: 'key', type: 'textfield', label: 'ops.key' },
    { key: 'humanReview', type: 'checkbox', label: 'admin.humanReview', defaultValue: true },
    { key: 'active', type: 'checkbox', label: 'events.active' },
    { key: 'limits', type: 'group', label: 'x', components: [{ key: 'budget', type: 'number', label: 'admin.tokenBudget', defaultValue: 1000 }, { key: 'strict', type: 'checkbox', label: 'x' }] },
  ];

  it('takes each field default, inside groups too; a checkbox without one starts unchecked (false); text fields start empty', () => {
    expect(newEntryValues(fields)).toEqual({ humanReview: true, active: false, budget: 1000, strict: false });
  });

  it("the screen's newDefaults win over field defaults", () => {
    expect(newEntryValues(fields, { humanReview: false, kind: 'SMS' })).toEqual({ humanReview: false, active: false, budget: 1000, strict: false, kind: 'SMS' });
  });
});
