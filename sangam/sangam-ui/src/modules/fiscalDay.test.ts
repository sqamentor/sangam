// SPDX-License-Identifier: Apache-2.0
// Owner X1-03b: the optional fiscalStartDay (1..28) on the three bodies of contract 217C21BE. Written, not run.
import { describe, expect, it } from 'vitest';
import en from '../i18n/locales/en.json';
import registrySource from './registry.tsx?raw';
import { fiscalStartDayField } from './fiscalDay';
import { numberSeriesFields } from './registryNumbering';
import { programmeConfigFields } from './programmeConfig';

type Tree = { [k: string]: string | Tree };
const lookup = (t: Tree, key: string): unknown => key.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Tree)[k] : undefined), t);

describe('fiscalStartDay', () => {
  it('is one optional integer field, 1..28, under the contract name', () => {
    expect(fiscalStartDayField()).toEqual({ key: 'fiscalStartDay', label: 'fld.fiscalStartDay', description: 'fld.fiscalStartDayHint', type: 'number', validate: { min: 1, max: 28 } });
  });
  it('is on the number-series and programme-config forms, right after their start month', () => {
    for (const fields of [numberSeriesFields(), programmeConfigFields()]) {
      const keys = fields.map((x) => x.key);
      const month = keys.findIndex((k) => k === 'fiscalStartMonth' || k === 'fiscalYearStartMonth');
      expect(month).toBeGreaterThanOrEqual(0);
      expect(keys[month + 1]).toBe('fiscalStartDay');
    }
  });
  it('validity (POST /registry/types) has no form: the type publish dialog hints at definition.validity.fiscalStartDay', () => {
    expect(registrySource).toContain("hint: 'mod.registry.publishTypeHintFiscal'");
    const hint = lookup(en as Tree, 'mod.registry.publishTypeHintFiscal');
    expect(typeof hint).toBe('string');
    expect(hint as string).toMatch(/validity/);
    expect(hint as string).toMatch(/fiscalStartDay/);
    expect(hint as string).toMatch(/1.{1,4}28/);
  });
  it('has English labels', () => {
    for (const k of ['fld.fiscalStartDay', 'fld.fiscalStartDayHint']) expect(typeof lookup(en as Tree, k), k).toBe('string');
  });
});
