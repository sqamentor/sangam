// SPDX-License-Identifier: Apache-2.0
/**
 * UI-DEMO-1 (decision 400 (3)): GET /forms/by-key answers a pack start form with the pack's form document as its layout
 * (fields[{id, type, required, label{en,hi}}]). The start screen renders it: normForm turns it into form components, a
 * money field is entered in major units and sent as exact integer minor units, a duration field is checked as ISO-8601.
 * Before the fix normForm knew only form-js layouts and data schemas, so a pack form rendered no field at all.
 */
import { describe, expect, it } from 'vitest';
import { normForm } from './adapters';
import { submitValues, validateForm } from '../components/FormRenderer';
import { DecimalValue } from '../lib/decimal';

const t = (k: string, o?: Record<string, unknown>) => (o ? `${k}:${JSON.stringify(o)}` : k);

const served = {
  key: 'sangam:demo/start',
  versionHash: 'a'.repeat(64),
  layout: {
    key: 'demo/start',
    version: '1.0.0',
    title: { en: 'Demo', hi: 'डेमो' },
    fields: [
      { id: 'examName', type: 'string', required: true, label: { en: 'Examination', hi: 'परीक्षा' } },
      { id: 'amount', type: 'money', currency: 'INR', exponent: 2, required: true, label: { en: 'Amount (INR)', hi: 'राशि' } },
      { id: 'registrationWindow', type: 'duration', required: false, label: { en: 'Registration window', hi: 'अवधि' } },
      { id: 'onDate', type: 'date', required: false, label: { en: 'Date', hi: 'तिथि' } },
      { id: 'remarks', type: 'text', required: false, label: { en: 'Remarks', hi: 'टिप्पणी' } },
      { id: 'agree', type: 'boolean', required: false, label: { en: 'Agree', hi: 'सहमत' } },
      { id: 'documents', type: 'attachments', required: false, label: { en: 'Documents', hi: 'दस्तावेज़' } },
    ],
  },
};

describe('pack start form (UI-DEMO-1)', () => {
  const schema = normForm(served);

  it('renders every field a person enters at the start, with its label, Hindi label and required flag', () => {
    expect(schema.components.map((c) => [c.key, c.type])).toEqual([
      ['examName', 'textfield'],
      ['amount', 'number'],
      ['registrationWindow', 'textfield'],
      ['onDate', 'datetime'],
      ['remarks', 'textarea'],
      ['agree', 'checkbox'],
    ]);
    const exam = schema.components[0];
    expect(exam.label).toBe('Examination');
    expect(exam.i18n?.hi?.label).toBe('परीक्षा');
    expect(exam.validate?.required).toBe(true);
    expect(schema.components[1].money).toEqual({ exponent: 2, currency: 'INR' });
  });

  it('sends money as exact integer minor units and refuses more decimals than the currency has', () => {
    expect(submitValues(schema, { examName: 'X', amount: 1250.5 })).toEqual({ examName: 'X', amount: 125050 });
    expect(submitValues(schema, { amount: new DecimalValue('0.07') }).amount).toBe(7);
    expect(validateForm(schema, { examName: 'X', amount: 10.005 }, t).amount).toBe('form.moneyScale:{"n":2}');
  });

  it('checks a duration as ISO-8601', () => {
    expect(validateForm(schema, { examName: 'X', amount: 1, registrationWindow: 'P15D' }, t).registrationWindow).toBeUndefined();
    expect(validateForm(schema, { examName: 'X', amount: 1, registrationWindow: 'PT36H' }, t).registrationWindow).toBeUndefined();
    expect(validateForm(schema, { examName: 'X', amount: 1, registrationWindow: 'two weeks' }, t).registrationWindow).toBeDefined();
    expect(validateForm(schema, { examName: 'X', amount: 1, registrationWindow: 'P' }, t).registrationWindow).toBeDefined();
  });
});
