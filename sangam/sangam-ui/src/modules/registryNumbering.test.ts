// SPDX-License-Identifier: Apache-2.0
// SL-126: the number-series form sends the API's own field names (reset, fiscalStartMonth), so a FISCAL_YEAR series
// defined from the UI keeps its reset (the form used to send resetPolicy, which the server ignored: NEVER), and the
// fiscal start month (SL-125: required for FISCAL_YEAR, never assumed) is offered only for that reset.
import { describe, expect, it } from 'vitest';
import en from '../i18n/locales/en.json';
import hi from '../i18n/locales/hi.json';
import { buildRequest } from './engine';
import { NUMBER_SERIES_RESETS, fiscalStartMonthOptions, numberSeriesFields } from './registryNumbering';

type Tree = { [k: string]: string | Tree };
const lookup = (t: Tree, key: string): unknown => key.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Tree)[k] : undefined), t);

describe('SL-126 number-series form', () => {
  it('uses the server field names, never resetPolicy', () => {
    const keys = numberSeriesFields().map((x) => x.key);
    expect(keys).toContain('reset');
    expect(keys).toContain('fiscalStartMonth');
    expect(keys).not.toContain('resetPolicy');
  });

  it('sends reset and fiscalStartMonth in the body for a FISCAL_YEAR series', () => {
    const { body, error } = buildRequest(numberSeriesFields(), { seriesKey: 'TL', pattern: '{prefix}/{fy}/{seq:6}', reset: 'FISCAL_YEAR', fiscalStartMonth: '7' }, '');
    expect(error).toBeUndefined();
    expect(body).toEqual({ seriesKey: 'TL', pattern: '{prefix}/{fy}/{seq:6}', reset: 'FISCAL_YEAR', fiscalStartMonth: '7' });
    expect(body).not.toHaveProperty('resetPolicy');
  });

  it('offers the twelve months only for the FISCAL_YEAR reset', () => {
    expect(fiscalStartMonthOptions({ reset: 'FISCAL_YEAR' }).map((o) => o.value)).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12']);
    for (const r of NUMBER_SERIES_RESETS.filter((x) => x !== 'FISCAL_YEAR')) expect(fiscalStartMonthOptions({ reset: r })).toEqual([]);
    expect(fiscalStartMonthOptions({})).toEqual([]);
  });

  it('has a label for every option and field in both languages', () => {
    const labels = [
      ...numberSeriesFields().flatMap((x) => [x.label, x.description, ...(x.values ?? []).map((v) => v.label)]),
      ...fiscalStartMonthOptions({ reset: 'FISCAL_YEAR' }).map((o) => o.label),
    ].filter((x): x is string => !!x);
    for (const key of labels) {
      expect(typeof lookup(en as Tree, key), `en ${key}`).toBe('string');
      expect(typeof lookup(hi as Tree, key), `hi ${key}`).toBe('string');
    }
  });
});
