// SPDX-License-Identifier: Apache-2.0
// SL-126: the number-series form sends the API's own field names (reset, fiscalStartMonth), so a FISCAL_YEAR series
// defined from the UI keeps its reset (the form used to send resetPolicy, which the server ignored: NEVER), and the
// fiscal start month (SL-125: required for FISCAL_YEAR, never assumed) is offered only for that reset.
import { describe, expect, it } from 'vitest';
import en from '../i18n/locales/en.json';
import hi from '../i18n/locales/hi.json';
import { buildRequest } from './engine';
import { NUMBER_SERIES_RESETS, fiscalStartMonthOptions, numberSeriesFields, usesFiscalYear } from './registryNumbering';

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
    // the contract types fiscalStartMonth as an integer (217C21BE); the select's text is sent as that integer
    expect(body).toEqual({ seriesKey: 'TL', pattern: '{prefix}/{fy}/{seq:6}', reset: 'FISCAL_YEAR', fiscalStartMonth: 7 });
    expect(body).not.toHaveProperty('resetPolicy');
  });

  it('offers the twelve months only for the FISCAL_YEAR reset', () => {
    expect(fiscalStartMonthOptions({ reset: 'FISCAL_YEAR' }).map((o) => o.value)).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12']);
    for (const r of NUMBER_SERIES_RESETS.filter((x) => x !== 'FISCAL_YEAR')) expect(fiscalStartMonthOptions({ reset: r })).toEqual([]);
    expect(fiscalStartMonthOptions({})).toEqual([]);
  });

  it('X1-02: offers the months also for a {fy} or {fyStart} token, as the server requires (NumberSeriesDefinition.java:40-57)', () => {
    for (const pattern of ['{prefix}/{fy}/{seq:6}', 'R{fyStart}-{seq}', '{fy:x}{seq}']) {
      expect(fiscalStartMonthOptions({ reset: 'NEVER', pattern }).length, pattern).toBe(12);
      expect(usesFiscalYear({ reset: 'DAILY', pattern }), pattern).toBe(true);
    }
    for (const pattern of ['{prefix}/{yyyy}/{seq:6}', '{fyx}{seq}', 'fy{seq}']) expect(usesFiscalYear({ reset: 'MONTHLY', pattern }), pattern).toBe(false);
    expect(usesFiscalYear({ reset: 'FISCAL_YEAR' })).toBe(true);
  });

  it('X1-02: offers every reset the server accepts, DAILY included (NumberSeriesDefinition.java:72)', () => {
    expect(NUMBER_SERIES_RESETS).toEqual(['NEVER', 'YEARLY', 'FISCAL_YEAR', 'MONTHLY', 'DAILY']);
    const { body } = buildRequest(numberSeriesFields(), { seriesKey: 'RCP', pattern: 'RCP/{yyyy}{MM}{dd}/{seq:4}', reset: 'DAILY' }, '');
    expect(body).toEqual({ seriesKey: 'RCP', pattern: 'RCP/{yyyy}{MM}{dd}/{seq:4}', reset: 'DAILY' });
  });

  it('owner X1-03b: offers the optional fiscalStartDay (1..28) and sends it as an integer only when given', () => {
    const day = numberSeriesFields().find((x) => x.key === 'fiscalStartDay');
    expect(day).toMatchObject({ type: 'number', validate: { min: 1, max: 28 } });
    expect(day?.validate?.required).toBeUndefined();
    const withDay = buildRequest(numberSeriesFields(), { seriesKey: 'TL', pattern: '{fy}/{seq}', fiscalStartMonth: '4', fiscalStartDay: 6 }, '').body;
    expect(withDay).toEqual({ seriesKey: 'TL', pattern: '{fy}/{seq}', fiscalStartMonth: 4, fiscalStartDay: 6 });
    expect(buildRequest(numberSeriesFields(), { seriesKey: 'TL', pattern: '{seq}' }, '').body).not.toHaveProperty('fiscalStartDay');
  });

  // Q-06 / SL-147 (owner-accepted 2026-11-12): labels added after the Hindi freeze are English-only; every other label
  // still needs its Hindi string.
  const ENGLISH_ONLY = new Set(['fld.fiscalStartDay', 'fld.fiscalStartDayHint']);
  it('has a label for every option and field in English, and in Hindi for every label from before the Q-06 freeze', () => {
    const labels = [
      ...numberSeriesFields().flatMap((x) => [x.label, x.description, ...(x.values ?? []).map((v) => v.label)]),
      ...fiscalStartMonthOptions({ reset: 'FISCAL_YEAR' }).map((o) => o.label),
    ].filter((x): x is string => !!x);
    for (const key of labels) {
      expect(typeof lookup(en as Tree, key), `en ${key}`).toBe('string');
      if (!ENGLISH_ONLY.has(key)) expect(typeof lookup(hi as Tree, key), `hi ${key}`).toBe('string');
    }
  });
});
