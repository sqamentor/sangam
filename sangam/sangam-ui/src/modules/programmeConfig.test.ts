// SPDX-License-Identifier: Apache-2.0
// X1-01 (HIGH): the programme-config publish form sends the required fiscalYearStartMonth (ProgrammeConfig.java:221-235),
// so a publish is no longer refused for want of it. Written, not run in this phase.
import { describe, expect, it } from 'vitest';
import en from '../i18n/locales/en.json';
import { validateForm } from '../components/FormRenderer';
import { buildRequest, prefillFrom } from './engine';
import { MONTHS, programmeConfigFields } from './programmeConfig';

type Tree = { [k: string]: string | Tree };
const lookup = (t: Tree, key: string): unknown => key.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Tree)[k] : undefined), t);
const t = (k: string) => k;

describe('X1-01 programme-config publish form', () => {
  it('asks for fiscalYearStartMonth by the server name, required, months 1..12', () => {
    const month = programmeConfigFields().find((x) => x.key === 'fiscalYearStartMonth');
    expect(month?.validate?.required).toBe(true);
    expect(month?.values?.map((v) => v.value)).toEqual(MONTHS);
    expect(MONTHS).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12']);
  });

  it('refuses a publish without the month on the client too', () => {
    const errs = validateForm({ components: programmeConfigFields() }, { indicators: '[]' }, t);
    expect(errs.fiscalYearStartMonth).toBe('form.required');
  });

  it('sends the month as a top-level key of the configuration body, next to the JSON parts', () => {
    const { body, error } = buildRequest(programmeConfigFields(), { fiscalYearStartMonth: '4', indicators: '[{"key":"km"}]', programmeKinds: '[{"kind":"SCHEME"}]' }, '{"units":["km"]}');
    expect(error).toBeUndefined();
    // sent as the integer the contract types (ProgrammePublishConfigBody, 217C21BE)
    expect(body).toEqual({ fiscalYearStartMonth: 4, indicators: [{ key: 'km' }], programmeKinds: [{ kind: 'SCHEME' }], units: ['km'] });
  });

  it('owner X1-03b: offers the optional fiscalStartDay (1..28, server default 1) and sends it only when given', () => {
    const day = programmeConfigFields().find((x) => x.key === 'fiscalStartDay');
    expect(day).toMatchObject({ type: 'number', validate: { min: 1, max: 28 } });
    expect(day?.validate?.required).toBeUndefined();
    expect(buildRequest(programmeConfigFields(), { fiscalYearStartMonth: '4', fiscalStartDay: 16, indicators: '[]' }, '').body).toEqual({ fiscalYearStartMonth: 4, fiscalStartDay: 16, indicators: [] });
    expect(buildRequest(programmeConfigFields(), { fiscalYearStartMonth: '1', indicators: '[]' }, '').body).not.toHaveProperty('fiscalStartDay');
    expect(validateForm({ components: programmeConfigFields() }, { fiscalYearStartMonth: '4', fiscalStartDay: 29, indicators: '[]' }, t).fiscalStartDay).toBe('form.max');
  });

  it('C3-09: a published configuration prefills the form and publishes back unchanged', () => {
    const published = {
      fiscalYearStartMonth: 4,
      fiscalStartDay: 16,
      indicators: [{ key: 'km', unit: 'km' }],
      returns: [{ key: 'monthly' }],
      programmeKinds: [{ kind: 'SCHEME' }],
      units: ['km'],
      variance: { warnPct: 10 },
      version: 3,
      contentHash: 'abc',
    };
    const { values, json } = prefillFrom(programmeConfigFields(), published, ['version', 'contentHash']);
    expect(values.fiscalYearStartMonth).toBe('4');
    expect(values.fiscalStartDay).toBe(16);
    expect(JSON.parse(String(values.indicators))).toEqual(published.indicators);
    expect(JSON.parse(json)).toEqual({ units: ['km'], variance: { warnPct: 10 } });
    const config = Object.fromEntries(Object.entries(published).filter(([k]) => k !== 'version' && k !== 'contentHash'));
    expect(buildRequest(programmeConfigFields(), values, json).body).toEqual(config);
  });

  it('C3-09: nothing to prefill from an empty or non-object answer', () => {
    expect(prefillFrom(programmeConfigFields(), undefined)).toEqual({ values: {}, json: '' });
    expect(prefillFrom(programmeConfigFields(), [1, 2])).toEqual({ values: {}, json: '' });
    expect(prefillFrom(programmeConfigFields(), { fiscalStartDay: null })).toEqual({ values: {}, json: '' });
  });

  it('has an English label for every field and option', () => {
    for (const x of programmeConfigFields()) {
      for (const key of [x.label, x.description, ...(x.values ?? []).map((v) => v.label)].filter((k): k is string => !!k)) {
        expect(typeof lookup(en as Tree, key), key).toBe('string');
      }
    }
  });
});
