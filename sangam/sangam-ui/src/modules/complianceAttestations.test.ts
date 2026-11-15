// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 362: the lawful-request verification, impact assessment, audit evidence and transfer evaluation forms
 * send their attestations (rankAdequate, independent, recipientSameProtectionAttested, recipientConditionMet) as explicit
 * Yes / No choices with no default; one left unanswered is refused before any call, as the server refuses it by name.
 * Owner decision 369 (h): the consent form's affirmative action is such a choice too.
 */
import { describe, expect, it } from 'vitest';
import type { FormField } from '../api/types';
import { checkChoices, consents, evidence, lawful } from './compliance';
import type { Act } from './engine';

const t = (k: string, o?: Record<string, unknown>) => (o ? `${k}:${String(o.field)}` : k);

function act(acts: Act[] | undefined, id: string): Act {
  const a = (acts ?? []).find((x) => x.id === id);
  if (!a) throw new Error(`no action ${id}`);
  return a;
}

function choice(a: Act, key: string): FormField {
  const fld = (a.fields ?? []).find((x) => x.key === key);
  if (!fld) throw new Error(`no field ${key} on ${a.id}`);
  return fld;
}

const CASES: [Act, string[]][] = [
  [act(lawful.toolbar, 'verify'), ['rankAdequate']],
  [act(evidence.toolbar, 'dpia'), ['independent']],
  [act(evidence.toolbar, 'audit'), ['independent']],
  [act(evidence.toolbar, 'transfer'), ['recipientSameProtectionAttested', 'recipientConditionMet']],
];

describe('compliance attestations (decision 362)', () => {
  it('each attestation is a required Yes / No choice sent as a JSON boolean, with no default', () => {
    for (const [a, keys] of CASES) {
      for (const k of keys) {
        const fld = choice(a, k);
        expect(fld.type, `${a.id}.${k}`).toBe('select');
        expect(fld.json, `${a.id}.${k}`).toBe(true);
        expect(fld.validate?.required, `${a.id}.${k}`).toBe(true);
        expect(fld.values?.map((v) => v.value), `${a.id}.${k}`).toEqual(['true', 'false']);
        expect((fld as { default?: unknown }).default, `${a.id}.${k}`).toBeUndefined();
      }
    }
  });

  it('an unanswered attestation is refused before any call, naming the field; false is an answer', () => {
    for (const [a, keys] of CASES) {
      expect(a.check, a.id).toBeDefined();
      const all = Object.fromEntries(keys.map((k) => [k, false]));
      expect(a.check?.(all, t), a.id).toBeUndefined();
      for (const k of keys) {
        const missing: Record<string, unknown> = { ...all };
        delete missing[k];
        expect(a.check?.(missing, t), `${a.id}.${k}`).toMatch(/^mod\.compliance\.choiceRequired:/);
        expect(a.check?.({ ...all, [k]: 'false' }, t), `${a.id}.${k} as text`).toMatch(/^mod\.compliance\.choiceRequired:/);
      }
    }
  });

  it('decision 365: the consent form asks the declared category as a required choice with no default', () => {
    const give = consents.create;
    expect(give).toBeDefined();
    const fld = choice(give as Act, 'declaredCategory');
    expect(fld.type).toBe('select');
    expect(fld.validate?.required).toBe(true);
    expect(fld.values?.map((v) => v.value)).toEqual(['ADULT', 'CHILD']);
    expect((fld as { default?: unknown }).default).toBeUndefined();
    expect(give?.check?.({ affirmativeAction: true, declaredCategory: 'CHILD' }, t)).toBeUndefined();
    expect(give?.check?.({ affirmativeAction: true, declaredCategory: 'ADULT' }, t)).toBeUndefined();
    expect(give?.check?.({ affirmativeAction: true }, t)).toBe('mod.compliance.choiceRequired:fld.declaredCategory');
    expect(give?.check?.({ affirmativeAction: true, declaredCategory: 'UNKNOWN' }, t)).toBe('mod.compliance.choiceRequired:fld.declaredCategory');
  });

  it('decision 369 (h): the consent form asks the affirmative action as a required Yes / No with no default', () => {
    const give = consents.create as Act;
    const fld = choice(give, 'affirmativeAction');
    expect(fld.type).toBe('select');
    expect(fld.json).toBe(true);
    expect(fld.validate?.required).toBe(true);
    expect(fld.values?.map((v) => v.value)).toEqual(['true', 'false']);
    expect((fld as { default?: unknown }).default).toBeUndefined();
    expect(give.check?.({ declaredCategory: 'ADULT' }, t)).toBe('mod.compliance.choiceRequired:fld.affirmativeAction');
    expect(give.check?.({ affirmativeAction: 'true', declaredCategory: 'ADULT' }, t)).toBe('mod.compliance.choiceRequired:fld.affirmativeAction');
    expect(give.check?.({ affirmativeAction: false, declaredCategory: 'ADULT' }, t)).toBeUndefined();
  });

  it('checkChoices names the given label', () => {
    expect(checkChoices([['independent', 'fld.independentAssessor']])({}, t)).toBe('mod.compliance.choiceRequired:fld.independentAssessor');
    expect(checkChoices(['rankAdequate'])({}, t)).toBe('mod.compliance.choiceRequired:fld.rankAdequate');
    expect(checkChoices(['rankAdequate'])({ rankAdequate: true }, t)).toBeUndefined();
  });
});
