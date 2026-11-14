// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Owner decisions 230 and 242: the field template designer checks NUMBER scale and bounds as sangam-field does
// (FieldTemplate.validateDefinition, FieldTemplate.Bound), exactly, before publishing. Written, not run in this phase.
import { describe, expect, it } from 'vitest';
import { buildRequest, f } from './engine';
import { checkTemplateSections } from './fieldTemplateCheck';

const t = (k: string, o?: Record<string, unknown>) => (o ? `${k} ${JSON.stringify(o)}` : k);
const sections = (...items: Record<string, unknown>[]) => [{ id: 's1', items }];

describe('checkTemplateSections', () => {
  it('accepts NUMBER items with scale 0..10 and canonical bounds within the scale', () => {
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', scale: 2, min: '0', max: '1250.5' }), t)).toBeUndefined();
    expect(checkTemplateSections(sections({ id: 'b', type: 'NUMBER', scale: 0, min: '-3', max: 100 }), t)).toBeUndefined();
    expect(checkTemplateSections(sections({ id: 'c', type: 'NUMBER', scale: 10, min: '0.0000000001' }), t)).toBeUndefined();
    // without a scale there is no limit beyond canonical decimal text (decision 242)
    expect(checkTemplateSections(sections({ id: 'd', type: 'NUMBER', min: '0.123456789012345678901234567890001' }), t)).toBeUndefined();
    expect(checkTemplateSections(sections({ id: 'e', type: 'TEXT' }), t)).toBeUndefined();
  });

  it('refuses a scale outside 0..10, a fractional scale and a scale on a non-NUMBER item', () => {
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', scale: 11 }), t)).toContain('mod.field.check.scaleRange');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', scale: -1 }), t)).toContain('mod.field.check.scaleRange');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', scale: 1.5 }), t)).toContain('mod.field.check.scaleRange');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', scale: '2' }), t)).toContain('mod.field.check.scaleRange');
    expect(checkTemplateSections(sections({ id: 'q', type: 'TEXT', scale: 2 }), t)).toBe('mod.field.check.scaleNotNumber {"item":"q"}');
  });

  it('refuses a bound with more decimal places than the scale, counted on the canonical form', () => {
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', scale: 1, max: '12.55' }), t)).toBe('mod.field.check.boundScale {"item":"a","bound":"max","value":"12.55","scale":1}');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', scale: 0, min: '0.5' }), t)).toContain('mod.field.check.boundScale');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', scale: 1, max: '12.5' }), t)).toBeUndefined();
  });

  it('refuses non-canonical bound text and names the canonical form (decision 230)', () => {
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', min: '12.50' }), t)).toBe('mod.field.check.boundNotCanonical {"item":"a","bound":"min","expected":"12.5"}');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', min: '+3' }), t)).toContain('"expected":"3"');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', max: '1E+2' }), t)).toContain('"expected":"100"');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', max: 'abc' }), t)).toContain('mod.field.check.boundNotNumber');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', max: true }), t)).toContain('mod.field.check.boundNotNumber');
  });

  it('asks for a fractional or very large bound as text, because JSON.parse would make it a binary double', () => {
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', min: 0.1 }), t)).toContain('mod.field.check.boundAsText');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', max: 2 ** 53 }), t)).toContain('mod.field.check.boundAsText');
    expect(checkTemplateSections(sections({ id: 'a', type: 'NUMBER', max: 9007199254740991 }), t)).toBeUndefined();
  });

  it('checks the body the designer dialog builds: decimal text in the JSON stays exact through JSON.parse', () => {
    const fields = [f.text('key', undefined, true), f.json('sections', 'fld.checklistSections', true)];
    const typed = '[{"id":"s","items":[{"id":"amt","type":"NUMBER","scale":2,"min":"0.01","max":"99999999999999999.99"}]}]';
    const req = buildRequest(fields, { key: 'k', sections: typed }, '');
    expect(req.error).toBeUndefined();
    expect(checkTemplateSections(req.body.sections, t)).toBeUndefined();
    expect(JSON.stringify(req.body.sections)).toContain('"max":"99999999999999999.99"');
  });
});
