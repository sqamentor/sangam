// SPDX-License-Identifier: Apache-2.0
// M-031: tenant custom fields appear in object forms; shipped fields win; the schema profile maps to form controls.
// Written, not run in this phase.
import { describe, expect, it } from 'vitest';
import { customFieldToFormField, withCustomFields, type CustomField } from './customFields';

const cf = (key: string, schema: Record<string, unknown>, extra: Partial<CustomField> = {}): CustomField => ({
  objectType: 'form:leave',
  key,
  version: 1,
  schema,
  effectiveFrom: '2026-01-01',
  ...extra,
});

describe('customFieldToFormField', () => {
  it('maps the schema profile (TenantCustomFieldRegistry.java:44-48)', () => {
    expect(customFieldToFormField(cf('x_ok', { type: 'boolean' }))?.type).toBe('checkbox');
    expect(customFieldToFormField(cf('x_n', { type: 'integer', minimum: 1, maximum: 9 }))).toMatchObject({ type: 'number', validate: { min: 1, max: 9 } });
    expect(customFieldToFormField(cf('x_d', { type: 'string', format: 'date' }))).toMatchObject({ type: 'datetime', subtype: 'date' });
    expect(customFieldToFormField(cf('x_e', { type: 'string', enum: ['A', 'B'] }))?.values?.map((v) => v.value)).toEqual(['A', 'B']);
    expect(customFieldToFormField(cf('x_m', { type: 'string', format: 'decimal' }))?.validate?.pattern).toBe('-?\\d+(\\.\\d+)?');
    expect(customFieldToFormField(cf('x_t', { type: 'string', format: 'date-time' }))?.type).toBe('textfield');
    expect(customFieldToFormField(cf('x_l', { type: 'array', items: { type: 'string', enum: ['p', 'q'] } }))?.type).toBe('checklist');
  });
  it('carries label, required and read-only, with the schema title as a fallback label', () => {
    expect(customFieldToFormField(cf('x_a', { type: 'string', maxLength: 10 }, { label: 'पिता का नाम', required: true }))).toMatchObject({
      label: 'पिता का नाम',
      validate: { required: true, maxLength: 10 },
    });
    expect(customFieldToFormField(cf('x_b', { type: 'string', title: 'Ward' }, { readOnly: true }))).toMatchObject({ label: 'Ward', readonly: true });
    expect(customFieldToFormField(cf('x_c', { type: 'string' }))?.label).toBe('x_c');
  });
  it('has no control for an array without an enum', () => {
    expect(customFieldToFormField(cf('x_arr', { type: 'array', items: { type: 'string' } }))).toBeNull();
  });
});

describe('withCustomFields', () => {
  const form = { key: 'leave', components: [{ key: 'days', type: 'number' as const, label: 'Days' }, { key: 'g', type: 'group' as const, components: [{ key: 'x_dup', type: 'textfield' as const }] }] };
  it('appends the fields the form does not have, in key order', () => {
    const out = withCustomFields(form, [cf('x_zone', { type: 'string' }), cf('x_area', { type: 'string' })]);
    expect(out.components.map((c) => c.key)).toEqual(['days', 'g', 'x_area', 'x_zone']);
  });
  it('lets a shipped field win, even inside a group', () => {
    expect(withCustomFields(form, [cf('days', { type: 'string' }), cf('x_dup', { type: 'string' })])).toBe(form);
  });
  it('leaves the form untouched without custom fields', () => {
    expect(withCustomFields(form, [])).toBe(form);
  });
});
