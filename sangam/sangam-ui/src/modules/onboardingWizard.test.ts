// SPDX-License-Identifier: Apache-2.0
// M-035: the guided onboarding wizard builds the OnboardingRequest the server reads. Written, not run in this phase.
import { describe, expect, it } from 'vitest';
import { STEPS, defaults, formField, fromRequest, toRequest } from './onboardingWizard';

describe('toRequest', () => {
  it('requires tenant.id, as the server record does (OnboardingRequest.java:30-31)', () => {
    expect(toRequest(defaults()).errors).toEqual({ 'tenant.id': 'form.required' });
    expect(toRequest({ 'tenant.id': '  ' }).errors['tenant.id']).toBe('form.required');
  });
  it('builds nested parts, lists, numbers, booleans and JSON', () => {
    const r = toRequest({
      ...defaults(),
      'tenant.id': 'RJ.FIN',
      'tenant.name': 'वित्त विभाग',
      'tenant.expectedStartsPerSec': '2.5',
      'tenant.names': '{"hi":"वित्त विभाग"}',
      'profile.mode': 'COPY',
      'profile.adoptFrom': '[{"profile":"RJ.HR","sections":["calendars"]}]',
      'packs.ids': 'gov.leave, gov.grievance\nfin.bill',
      'security.orgLevels': 'state,district',
      'goLive.languages': 'en',
    });
    expect(r.errors).toEqual({});
    expect(r.request).toEqual({
      tenant: { id: 'RJ.FIN', name: 'वित्त विभाग', expectedStartsPerSec: 2.5, names: { hi: 'वित्त विभाग' } },
      profile: { mode: 'COPY', adoptFrom: [{ profile: 'RJ.HR', sections: ['calendars'] }] },
      packs: { ids: ['gov.leave', 'gov.grievance', 'fin.bill'], allowUpgrade: false },
      security: { moduleDefaults: true, selfService: true, orgLevels: ['state', 'district'] },
      goLive: { languages: ['en'] },
    });
  });
  it('reports JSON of the wrong shape and bad numbers per field', () => {
    const r = toRequest({ 'tenant.id': 'A', 'tenant.quotas': '[1]', 'profile.documents': '{}', 'packs.versions': '{bad', 'tenant.expectedStartsPerSec': 'x' });
    expect(r.errors).toEqual({ 'tenant.quotas': 'onb.err.object', 'profile.documents': 'onb.err.array', 'packs.versions': 'onb.err.json', 'tenant.expectedStartsPerSec': 'onb.err.number' });
  });
  it('leaves empty values out so the server defaults apply', () => {
    expect(toRequest({ 'tenant.id': 'A', 'profile.jurisdiction': '', 'packs.ids': ' , ' }).request).toEqual({ tenant: { id: 'A' } });
  });
});

describe('fromRequest', () => {
  it('round-trips a request', () => {
    const request = { tenant: { id: 'A', quotas: { maxBatchSize: 500 } }, packs: { ids: ['p1', 'p2'], allowUpgrade: true }, compliance: { jurisdictions: ['IN'] } };
    const back = toRequest(fromRequest(request));
    expect(back.errors).toEqual({});
    // the wizard's defaults are the server's own (ProfileMode INHERIT, moduleDefaults and selfService true)
    expect(back.request).toEqual({ ...request, profile: { mode: 'INHERIT' }, security: { moduleDefaults: true, selfService: true } });
  });
});

describe('fields', () => {
  it('covers every part of the server record', () => {
    const roots = new Set(STEPS.flatMap((s) => s.fields.map((f) => f.path.split('.')[0])));
    expect([...roots].sort()).toEqual(['compliance', 'connectors', 'dmnOverrides', 'goLive', 'packs', 'profile', 'security', 'seeds', 'tenant']);
  });
  it('maps kinds to form-js field types', () => {
    expect(formField({ path: 'x.y', kind: 'bool' }).type).toBe('checkbox');
    expect(formField({ path: 'x.y', kind: 'json' })).toMatchObject({ type: 'textarea', json: true });
    expect(formField({ path: 'profile.mode', kind: 'mode' }).values?.map((v) => v.value)).toEqual(['INHERIT', 'IMPORT', 'COPY']);
    expect(formField({ path: 'tenant.id', kind: 'text', required: true }).validate).toEqual({ required: true });
  });
});
